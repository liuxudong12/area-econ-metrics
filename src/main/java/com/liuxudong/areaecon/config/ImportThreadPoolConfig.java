package com.liuxudong.areaecon.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 导入任务的线程池。
 *
 * ==========================================================================
 * 【为什么不用 @Async + Spring 默认线程池？】
 * ==========================================================================
 * Spring Boot 给 @Async 用的默认执行器（SimpleAsyncTaskExecutor 或自建的 ThreadPoolTaskExecutor）
 * 有两个问题：
 *   ① 默认**无界队列**（Integer.MAX_VALUE）。任务堆积时不会拒绝，
 *      而是无限塞进队列 —— 内存慢慢涨，最后 OOM。这比"拒绝"危险得多：
 *      拒绝你能立刻看到错误，OOM 是整个服务一起死。
 *   ② 你没法针对"导入"这个场景单独调参数。导入是重 IO、重 CPU（解析 Excel）的任务，
 *      和发短信、写日志那种轻任务混在一个池里，会互相拖累。
 *
 * 所以这里**自己 new 一个**，参数自己定，Bean 名字叫 importExecutor，
 * 注入时用 @Qualifier("importExecutor") 精确指定。
 *
 * ==========================================================================
 * 【7 个参数逐个解释（面试必问）】
 * ==========================================================================
 *   corePoolSize = 4
 *       常驻线程数。为什么是 4？—— 导入是 IO 密集 + 中等 CPU 任务，
 *       4 个并发已经能吃掉 MySQL 的大部分写能力；再多只会让数据库更慢，
 *       还会挤占其他业务请求的数据库连接（连接池总共才 10 个）。
 *
 *   maximumPoolSize = 8
 *       峰值线程数。注意 JDK 线程池的"反直觉"行为：
 *       **它先把队列塞满，才会扩容到 max**（不是核心满了就立刻扩容）。
 *       所以 4 个核心线程忙、队列 100 个还没满的时候，线程数会一直是 4。
 *
 *   keepAliveTime = 60s
 *       超过核心数的线程空闲 60 秒后被回收。发版前的一次性高峰过后能自己缩回来。
 *
 *   workQueue = LinkedBlockingQueue(100)
 *       ★ **必须有界**。100 的意思是"最多再排 100 个导入任务"。
 *       按每个任务 1 万行、10 秒算，100 个任务排队 = 最长的那个要等 1000 秒。
 *       这已经很不合理了 —— 但至少不会 OOM。
 *
 *   threadFactory
 *       给线程起名字（import-task-1/2/3...）。
 *       默认名字是 pool-1-thread-1，出问题时 jstack 里一堆 pool-N-thread-M，
 *       根本分不清哪个是导入、哪个是定时任务。**起名字是零成本的排障投资。**
 *
 *   handler = AbortPolicy
 *       ★ 队列满时的拒绝策略。这里的选择很关键，见下面的专门说明。
 *
 * ==========================================================================
 * 【★ 为什么不用 CallerRunsPolicy（这是今天最值得讲的一个坑）】
 * ==========================================================================
 * CallerRunsPolicy 的语义是：队列满了，就让"提交任务的线程"自己把任务跑完。
 * 在**后台批处理程序**里这很好（相当于自动限流，不丢任务）。
 *
 * 但在 **Web 场景**里它是个陷阱：
 *   提交任务的线程 = Tomcat 的工作线程（处理 HTTP 请求的那个）。
 *   队列一满，这个 HTTP 请求就亲自去跑一个 10 秒的导入 ——
 *   **异步接口又变成了同步接口**，而且比同步还糟：
 *   用户明明只想"接个单"，结果被卡了 10 秒；同时 Tomcat 的线程被占住，
 *   其他正常请求也没人处理了。
 *
 *   接口"秒回"的承诺被悄悄打破，前端还在轮询 taskId —— 但那个任务
 *   根本没建（或者建了在等），体验比直接报错还差。
 *
 * 所以我们用 AbortPolicy：队列满就抛 RejectedExecutionException，
 * 上层捕获后返回"系统繁忙，请稍后再试"，并且**把刚建的任务标成 FAILED**，
 * 别留一条 PENDING 永远没人跑（那种"僵尸任务"最难查）。
 *
 * 一句话总结：**CallerRunsPolicy 保护的是"任务不丢"，AbortPolicy 保护的是"请求不变慢"。**
 * Web 场景优先保后者，任务丢了可以让用户重试（而且我们有幂等，重试是安全的）。
 */
@Slf4j
@Configuration
public class ImportThreadPoolConfig {

    /** Bean 名，注入时用 @Qualifier 指定，避免和其他线程池混淆 */
    public static final String IMPORT_EXECUTOR = "importExecutor";

    @Bean(IMPORT_EXECUTOR)
    public ThreadPoolExecutor importExecutor() {
        AtomicInteger seq = new AtomicInteger(1);

        ThreadFactory threadFactory = runnable -> {
            Thread t = new Thread(runnable, "import-task-" + seq.getAndIncrement());
            // 设为非守护线程：JVM 退出前会等它把正在跑的导入做完，
            // 避免"发版时正在导的数据被硬切断"。
            // （代价是关服务要多等一会儿，所以真实项目要配优雅停机 + 超时上限。）
            t.setDaemon(false);
            // 未捕获异常的兜底日志。线程池里的任务抛异常不会打印堆栈，
            // 不加这个，任务"莫名其妙消失了"你都不知道为什么。
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("[导入线程池] 线程 {} 未捕获异常", thread.getName(), ex));
            return t;
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );

        // ★ 允许核心线程超时回收。
        //   默认 false：核心线程即使空闲也永远活着。对一个"平时没几个导入"的系统来说，
        //   4 个线程白占内存和栈空间。设为 true 后，长时间没任务能缩到 0。
        executor.allowCoreThreadTimeOut(true);

        log.info("[导入线程池] 初始化完成 core=4 max=8 queue=100 policy=AbortPolicy");
        return executor;
    }
}

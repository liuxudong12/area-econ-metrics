package com.liuxudong.areaecon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liuxudong.areaecon.entity.AsyncTask;
import com.liuxudong.areaecon.entity.AsyncTaskStatus;
import com.liuxudong.areaecon.mapper.AsyncTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * ==========================================================================
 * 重试调度器 —— 让"等待重试"和"卡死"的任务重新活过来
 * ==========================================================================
 *
 * 【它解决两个问题，两个问题都很致命】
 *
 * 问题一：退避时间到了，谁把任务重新投递？
 *   失败时我们把任务写成 RETRY_WAIT + next_retry_time（比如 1 秒后），
 *   但**没有任何人会主动来看这个时间到了没有**。
 *   importAsync 早就返回了，线程也释放了 —— 如果没有调度器，
 *   这个任务就永远躺在 RETRY_WAIT，直到数据库被 DBA 删掉。
 *
 *   所以必须有"一个人在定时巡逻"，这就是本类。
 *   这类设计有个通用名字：**轮询式延迟任务（polling-based delayed job）**。
 *
 * 问题二：服务重启后，RUNNING 的任务成了孤儿
 *   任务被标成 RUNNING 之后，如果：
 *     · 发版重启（哪怕只重启 5 秒）
 *     · 线程池被 shutdownNow 强杀
 *     · OOM 被操作系统干掉
 *     · 进程被 kill -9
 *   那么这条任务会**永远停在 RUNNING**。
 *   用户轮询到天荒地老，看到的是"任务执行中，请稍候"。
 *   这是最难查的一类问题：数据库里看着很正常，就是不动。
 *
 *   对策：用 update_time 当"心跳"。
 *   RUNNING 且 update_time 已经很久没变 → 判定为僵尸，收回来重新排队。
 *
 * ==========================================================================
 * 【执行方式：@Scheduled + fixedDelay】
 * ==========================================================================
 * fixedDelay = 上一轮**执行完**之后，再等 500ms 开始下一轮。
 * 对比 fixedRate = 每 500ms 启动一轮（不管上一轮跑完没有）。
 *
 * 为什么用 fixedDelay？
 *   fixedRate 在任务执行时间超过间隔时会**堆积**，越跑越密集 ——
 *   如果某次扫描因为数据库慢花了 3 秒，fixedRate 会立刻补跑 6 次。
 *   fixedDelay 不会：永远保证"跑完 → 歇 500ms → 再跑"，节奏可控。
 *   定时任务几乎都该用 fixedDelay。
 *
 * ⚠️ @Scheduled 默认是**单线程**的（Spring 的 TaskScheduler 默认 poolSize=1）。
 *   如果以后加了第二个 @Scheduled 方法，它们会互相排队 ——
 *   一个卡住另一个就不跑了。要并行必须自己配 TaskScheduler 的线程池。
 *   这里只有一个方法，所以不需要。
 *
 * ==========================================================================
 * 【★ 多实例部署的坑：两个调度器会同时抢到同一条任务】
 * ==========================================================================
 * 单机时代这个类不用考虑并发。但真实项目至少 2 台机器（为了高可用），
 * 两台机器的调度器会同时扫出同一条 RETRY_WAIT 任务，然后都去投递 ——
 * 同一份文件被两个线程同时导入。
 *
 * 解法不是加分布式锁（太重），而是**抢占式 UPDATE**：
 *
 *     UPDATE async_task
 *        SET status = 'PENDING', next_retry_time = NULL
 *      WHERE id = ? AND status = 'RETRY_WAIT' AND next_retry_time <= NOW()
 *
 * MySQL 的 UPDATE 会对命中的行加排他锁，"判断 + 修改"在一条语句里原子完成。
 * 两台机器同时执行，只有一台拿到 affected=1，另一台拿到 0 —— 自动去重。
 *
 * 这比 Redis 分布式锁好在：
 *   · 不需要额外组件，少一个故障点
 *   · 不需要考虑锁超时、锁误删、锁续期
 *   · 状态和锁在同一个事务边界内，不会出现"锁住了但状态没改"的中间态
 *
 * **能用一条带条件的 UPDATE 解决的并发，就别上分布式锁。**
 * 这是"数据库本身就是一个强一致的协调者"的典型用法。
 */
@Slf4j
@Component
public class ImportRetryScheduler {

    /** async_task.error_msg 的列宽，写长文案时要留余量 */
    private static final int ERROR_MSG_MAX = 950;

    private final AsyncTaskMapper asyncTaskMapper;
    private final ImportService importService;

    /** 判定 RUNNING 任务为僵尸的阈值：多久没更新就算卡死 */
    @Value("${stat.import.retry.zombie-timeout-seconds:300}")
    private long zombieTimeoutSeconds;

    /** 每轮最多捞多少条，防止一次扫出几万条把内存撑爆 */
    @Value("${stat.import.retry.batch-limit:20}")
    private int batchLimit;

    /** 线程池满时，任务退回等待多少秒再试 */
    @Value("${stat.import.retry.requeue-delay-seconds:30}")
    private long requeueDelaySeconds;

    /** 和 ImportServiceImpl 里的 maxRetry 保持同一个配置项，避免两边不一致 */
    @Value("${stat.import.retry.max-retry:3}")
    private int maxRetry;

    public ImportRetryScheduler(AsyncTaskMapper asyncTaskMapper, ImportService importService) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.importService = importService;
    }

    /**
     * 定时巡逻入口。
     *
     * 【为什么要 try-catch 包住整个方法体】
     * @Scheduled 的方法抛异常时，Spring 只会打一条 error 日志，然后**继续下一轮**
     * （fixedDelay 不会因此停止）。所以就算不 catch 也不会停摆。
     *
     * 但两个子任务必须各自 catch：
     *   如果"扫到期重试"因为一条脏数据抛异常，不能连累"回收僵尸任务"也不执行。
     *   一个功能的故障不应该拖垮另一个功能 —— 这叫**故障隔离**。
     */
    @Scheduled(fixedDelayString = "${stat.import.retry.scan-interval-ms:500}")
    public void scan() {
        try {
            redispatchDueTasks();
        } catch (Exception e) {
            log.error("[重试调度器] 扫描到期任务时异常（不影响僵尸回收）", e);
        }

        try {
            reclaimZombieTasks();
        } catch (Exception e) {
            log.error("[重试调度器] 回收僵尸任务时异常", e);
        }
    }

    // ==========================================================================
    // 一、到期重试：RETRY_WAIT → PENDING → 投递线程池
    // ==========================================================================

    private void redispatchDueTasks() {
        LocalDateTime now = LocalDateTime.now();

        // 查询条件必须是"状态 + 时间"两个条件，缺一不可：
        //   只查 next_retry_time <= now 会把还没到点的也捞出来（提前重试，退避失效）
        //   只查 status = RETRY_WAIT 会把未来才该跑的任务全捞出来（退避完全失效）
        List<AsyncTask> due = asyncTaskMapper.selectList(
                new LambdaQueryWrapper<AsyncTask>()
                        .eq(AsyncTask::getStatus, AsyncTaskStatus.RETRY_WAIT)
                        .le(AsyncTask::getNextRetryTime, now)
                        .orderByAsc(AsyncTask::getNextRetryTime)
                        // ⚠️ last() 是**字符串拼接**，不走 #{} 预编译。
                        //    这里安全是因为 batchLimit 来自配置文件（int），不是外部输入。
                        //    如果哪天要改成"用户传条数"，必须改成参数绑定，否则就是注入漏洞。
                        .last("LIMIT " + batchLimit));

        if (due.isEmpty()) {
            return;
        }

        log.info("[重试调度器] 扫到 {} 条到期任务，开始抢占投递", due.size());

        for (AsyncTask task : due) {
            // ★ 抢占：把"我看到的这条 RETRY_WAIT"变成"由我负责的 PENDING"。
            //   where 里重复了 status 和 next_retry_time —— 这不是多余的，
            //   因为在 select 和 update 之间，别的实例可能已经改过了。
            //   **基于快照的更新必须把"快照成立的条件"写进 where**，这叫
            //   乐观锁的 CAS（Compare-And-Swap）思想。
            int claimed = asyncTaskMapper.update(null,
                    new LambdaUpdateWrapper<AsyncTask>()
                            .eq(AsyncTask::getId, task.getId())
                            .eq(AsyncTask::getStatus, AsyncTaskStatus.RETRY_WAIT)
                            .le(AsyncTask::getNextRetryTime, now)
                            .set(AsyncTask::getStatus, AsyncTaskStatus.PENDING)
                            .set(AsyncTask::getNextRetryTime, null));

            if (claimed == 0) {
                // 被别人抢先了。这不是错误，是正常并发，用 debug 级别即可 ——
                // 如果打 warn，多实例部署时日志会被这个刷屏。
                log.debug("[重试调度器] 任务已被其他实例领走，跳过 taskId={}", task.getId());
                continue;
            }

            submit(task);
        }
    }

    // ==========================================================================
    // 二、僵尸回收：RUNNING 很久没动静 → 重新排队
    // ==========================================================================

    private void reclaimZombieTasks() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(zombieTimeoutSeconds);

        List<AsyncTask> zombies = asyncTaskMapper.selectList(
                new LambdaQueryWrapper<AsyncTask>()
                        .eq(AsyncTask::getStatus, AsyncTaskStatus.RUNNING)
                        .lt(AsyncTask::getUpdateTime, deadline)
                        .orderByAsc(AsyncTask::getUpdateTime)
                        .last("LIMIT " + batchLimit));

        if (zombies.isEmpty()) {
            return;
        }

        log.warn("[重试调度器] 发现 {} 条疑似僵尸任务（RUNNING 且超过 {} 秒无更新）",
                zombies.size(), zombieTimeoutSeconds);

        for (AsyncTask z : zombies) {
            int retried = z.getRetryCount() == null ? 0 : z.getRetryCount();

            // 重试次数已经用完了：不再排队，直接判 FAILED。
            // 否则僵尸任务会"回收 → 重跑 → 又卡死 → 又回收"，无限循环。
            if (retried >= maxRetry) {
                int failed = asyncTaskMapper.update(null,
                        new LambdaUpdateWrapper<AsyncTask>()
                                .eq(AsyncTask::getId, z.getId())
                                .eq(AsyncTask::getStatus, AsyncTaskStatus.RUNNING)
                                .lt(AsyncTask::getUpdateTime, deadline)
                                .set(AsyncTask::getStatus, AsyncTaskStatus.FAILED)
                                .set(AsyncTask::getNextRetryTime, null)
                                .set(AsyncTask::getErrorMsg, truncate(
                                        "任务执行中断（超过 " + zombieTimeoutSeconds
                                                + " 秒无进展，可能服务重启或线程被强杀），"
                                                + "且已重试 " + retried + " 次，判定失败。"
                                                + "可调用 POST /api/import/task/" + z.getId() + "/retry 手动重试",
                                        ERROR_MSG_MAX)));
                if (failed == 1) {
                    log.error("[重试调度器] 僵尸任务重试次数已用尽，判 FAILED taskId={} 已重试={}次",
                            z.getId(), retried);
                }
                continue;
            }

            // ★ 抢占：只有"我看到的还是 RUNNING 且依然超时"的任务才归我处理。
            //   update_time 条件也要带上 —— 如果原线程其实还活着并刚刚更新过状态，
            //   我这边的 update 会匹配 0 行，不会误杀。
            //   **误杀一个还活着的任务，会导致同一份数据被并发导入两次。**
            int claimed = asyncTaskMapper.update(null,
                    new LambdaUpdateWrapper<AsyncTask>()
                            .eq(AsyncTask::getId, z.getId())
                            .eq(AsyncTask::getStatus, AsyncTaskStatus.RUNNING)
                            .lt(AsyncTask::getUpdateTime, deadline)
                            .set(AsyncTask::getStatus, AsyncTaskStatus.RETRY_WAIT)
                            .set(AsyncTask::getRetryCount, retried + 1)
                            .set(AsyncTask::getNextRetryTime, LocalDateTime.now().plusSeconds(requeueDelaySeconds))
                            .set(AsyncTask::getErrorMsg, truncate(
                                    "任务执行中断（超过 " + zombieTimeoutSeconds
                                            + " 秒无进展，可能服务重启或线程被强杀），已自动重新排队，"
                                            + "第 " + (retried + 1) + " 次重试",
                                    ERROR_MSG_MAX)));

            if (claimed == 1) {
                log.warn("[重试调度器] 已回收僵尸任务 taskId={} 最后更新时间={} 已重试={}次，{}秒后重跑",
                        z.getId(), z.getUpdateTime(), retried + 1, requeueDelaySeconds);
            }
        }
    }

    // ==========================================================================
    // 三、投递（带"队列满"的柔性处理）
    // ==========================================================================

    /**
     * 把任务交给线程池。
     *
     * 【★ 这里和 importAsync 里的处理**故意不一样**】
     * importAsync 遇到队列满是直接返回"系统繁忙"给用户 —— 因为用户在场，
     * 他可以立刻知道并稍后重试。
     *
     * 但重试任务没有人在场。如果这里也判它 FAILED：
     *   用户第二天打开页面，看到"重试 3 次全部失败"，原因却是"队列满" ——
     *   **队列满是我方容量问题，不是数据问题，凭什么判人家死刑？**
     *
     * 所以退回 RETRY_WAIT 再等一轮。retry_count **不再加 1**，
     * 因为这次压根没真正执行，不该消耗用户的重试额度。
     *
     * 这是一个通用原则：**自动重试的额度，应该花在"真正执行过但失败"上，
     * 而不是花在"没排上队"上。**
     */
    private void submit(AsyncTask task) {
        try {
            importService.dispatch(task.getId());
            log.info("[重试调度器] 已重新投递 taskId={} 第 {} 次重试", task.getId(), task.getRetryCount());
        } catch (RejectedExecutionException e) {
            asyncTaskMapper.update(null,
                    new LambdaUpdateWrapper<AsyncTask>()
                            .eq(AsyncTask::getId, task.getId())
                            .eq(AsyncTask::getStatus, AsyncTaskStatus.PENDING)
                            .set(AsyncTask::getStatus, AsyncTaskStatus.RETRY_WAIT)
                            .set(AsyncTask::getNextRetryTime,
                                    LocalDateTime.now().plusSeconds(requeueDelaySeconds)));

            log.warn("[重试调度器] 导入线程池已满，taskId={} 退回等待 {} 秒（不消耗重试次数）",
                    task.getId(), requeueDelaySeconds);
        }
    }

    /** 截断字符串到指定长度，防止超出 VARCHAR(1000) 报错 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

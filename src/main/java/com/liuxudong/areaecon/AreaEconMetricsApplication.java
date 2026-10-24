package com.liuxudong.areaecon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 项目启动类。
 *
 * @SpringBootApplication 是启动开关，它其实是三个注解的合体：
 *   1. @SpringBootConfiguration —— 标记这是配置类
 *   2. @EnableAutoConfiguration  —— 开启自动配置（自动把 Web、数据源等装配好）
 *   3. @ComponentScan            —— 扫描「当前包及所有子包」下的 Bean
 *
 * 重要：Spring 只扫描本类所在包 com.liuxudong.areaecon 及其子包。
 *      所以 Controller / Service / Mapper 都必须放在这个包下面，否则不生效。
 *
 * ==========================================================================
 * 【Day 10 新增：@EnableScheduling】
 * ==========================================================================
 * 没有它，@Scheduled 注解**完全不起作用，而且不报任何错** ——
 * 方法就是个普通方法，永远不会被调用。这是最容易漏的一步：
 * 你写好了定时任务、加了日志，启动后一杯茶喝完发现日志一条都没有，
 * 然后开始怀疑 cron 表达式、时区、代理……其实只是少了一个注解。
 *
 * 【顺带说清 @EnableScheduling 是怎么工作的】
 *   它不是"启动一个线程"，而是往容器里注册一个 ScheduledAnnotationBeanPostProcessor。
 *   这个后置处理器在 Bean 初始化时扫描所有方法上的 @Scheduled，
 *   把它们包装成 Runnable 注册到一个 TaskScheduler 里。
 *   TaskScheduler 默认只有一个线程 —— 所以多个 @Scheduled 方法会**互相排队**。
 *   要并行必须自己提供一个 ThreadPoolTaskScheduler 的 Bean。
 *   本项目只有一个定时任务（ImportRetryScheduler#scan），所以默认单线程够用。
 */
@SpringBootApplication
@EnableScheduling
public class AreaEconMetricsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AreaEconMetricsApplication.class, args);
    }
}

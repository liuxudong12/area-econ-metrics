package com.liuxudong.areaecon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
 */
@SpringBootApplication
public class AreaEconMetricsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AreaEconMetricsApplication.class, args);
    }
}

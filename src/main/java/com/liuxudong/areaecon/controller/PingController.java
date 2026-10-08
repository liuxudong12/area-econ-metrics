package com.liuxudong.areaecon.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 健康检查接口。
 *
 * 跑通它等于一次性验证了四件事：
 *   1. 代码能编译
 *   2. Spring Boot 能启动
 *   3. 8080 端口没被占用
 *   4. HTTP 请求能被正确路由到方法上
 *
 * 四个里任何一个出问题，你都访问不到 /ping，所以它是排查问题的基准线。
 */
@RestController
public class PingController {

    /**
     * 最简版本：返回纯字符串。
     * 浏览器访问 http://localhost:8080/ping 会看到 pong
     */
    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    /**
     * 带一点信息的版本，方便确认服务是什么时候起的。
     * 访问 http://localhost:8080/ping/detail
     */
    @GetMapping("/ping/detail")
    public String detail() {
        return "area-econ-metrics is UP at "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

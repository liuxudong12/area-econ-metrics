package com.liuxudong.areaecon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 最基础的冒烟测试：如果 Spring 容器能加载起来，这个测试就会通过。
 *
 * 它在帮你做一件事：确认「所有 Bean 都能被正确创建」。
 * 比如第 3 天你写了一个 Service 忘了加 @Service，或者 Mapper 配错，
 * 这个测试会第一时间报错，不用等你启动整个应用才发现。
 */
@SpringBootTest
class AreaEconMetricsApplicationTests {

    @Test
    void contextLoads() {
        // 空方法即可，能跑通就说明容器启动成功
    }
}

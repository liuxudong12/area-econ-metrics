package com.liuxudong.areaecon.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类。
 *
 * @Configuration 表示"这是一个配置类"，Spring 启动时会读它，把里面 @Bean 方法返回的对象
 *                注册成"容器管理的组件"（也就是 Bean）。
 *
 * @MapperScan("com.liuxudong.areaecon.mapper")
 *   告诉 MyBatis："去这个包下面找所有 Mapper 接口，给它们生成实现类"。
 *   有了它，理论上连 @Mapper 注解都可以不写（但我们两个都写了，更明确、更保险）。
 */
@Configuration
@MapperScan("com.liuxudong.areaecon.mapper")
public class MybatisPlusConfig {

    /**
     * 分页插件。
     *
     * 【为什么必须显式注册？】
     * 因为分页不是"框架天然会"的。你不注册这个插件，
     * selectPage 会把 total 查成 0、也不会自动加 LIMIT，等于分页失效。
     *
     * 【它背后的原理（面试常问）】
     * 你在代码里写 selectPage(page, wrapper) 时并没有写 LIMIT。
     * 这个插件会拦截即将执行的 SQL，做两件事：
     *   ① 先复制一份改写成 COUNT(*) 查询，拿到总条数；
     *   ② 再给原 SQL 拼上 LIMIT offset, size。
     * 也就是说 —— 一次分页查询实际发了 2 条 SQL。
     * 所以数据量很大时，count 也会慢，这时可以关闭 count 查询（用不查总数的分页）。
     *
     * ⚠️ 关键坑：MyBatis-Plus 3.5.9 起，这个插件的依赖（JSqlParser）被拆出去了，
     *    pom.xml 里必须额外加 mybatis-plus-jsqlparser，否则启动就报类找不到。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

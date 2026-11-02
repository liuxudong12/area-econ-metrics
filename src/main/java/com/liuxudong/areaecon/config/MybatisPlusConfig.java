package com.liuxudong.areaecon.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.liuxudong.areaecon.security.DataScopeInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
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
     * 行级数据权限拦截器。
     *
     * 单独拆成一个 @Bean（而不是在下面直接 new），是为了让"权限标注清单"接口
     * 能拿到同一个实例来查询标注情况 —— 两个不同实例的缓存各算各的，虽然结果一样，
     * 但没有必要。
     *
     * 开关 {@code stat.data-scope.enabled} 只为做 A/B 对比实验而存在（手册实验 E7）。
     * 生产环境必须为 true —— 关掉它 = 所有人都能看到全部数据。
     */
    @Bean
    public DataScopeInnerInterceptor dataScopeInnerInterceptor(
            @Value("${stat.data-scope.enabled:true}") boolean enabled) {
        return new DataScopeInnerInterceptor(enabled);
    }

    /**
     * 插件链。
     *
     * ⚠️⚠️ **最容易被忽略的一个细节：addInnerInterceptor 的顺序 = 执行顺序。**
     *
     * MyBatis-Plus 会按你 add 的顺序，依次调用每个 InnerInterceptor 的 beforeQuery，
     * 后一个拿到的 BoundSql 是前一个的**返回值**。所以：
     *
     *   ✓ 正确顺序（现在这样）：先数据权限、后分页
     *       ① DataScope   把 WHERE 改成 `... AND f.region_code LIKE '4301%'`
     *       ② Pagination  基于**改写后**的 SQL 去生成 COUNT(*) 和加 LIMIT
     *       → COUNT 只数长沙市的行，total = 35904。分页正确。
     *
     *   ✗ 反过来的后果：先分页、后权限
     *       ① Pagination  先执行 COUNT 拿到 **489600**（全量！），
     *                      再把 SQL 改成带 LIMIT 的形式返回给下一个插件
     *       ② DataScope   在已经带 LIMIT 的 SQL 上加过滤条件
     *       → 数据行是对的（只返回长沙的），但 **total 报的是全省 48 万**。
     *         前端页码器会显示 2 万多页，翻到第 10 页就全是空的。
     *
     * 这个 bug 的形态很有意思：**数据没错，只有总数错**。
     * 接口 200、数据合法、用户翻两页就发现不对了。手册里"实验 E6"能实测出这个差异。
     *
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DataScopeInnerInterceptor dataScopeInnerInterceptor) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 顺序不能反，理由见上面的注释
        interceptor.addInnerInterceptor(dataScopeInnerInterceptor);
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}

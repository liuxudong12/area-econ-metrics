package com.liuxudong.areaecon.config;

import com.liuxudong.areaecon.security.UserContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：把"解析当前用户"的拦截器挂上去。
 *
 * ============================================================================
 * 一、为什么用 HandlerInterceptor，而不用 Filter / AOP
 * ============================================================================
 *   · Filter：在 Servlet 层，**拿不到"这次请求要调哪个 Controller"**。
 *     而且它拿不到 Spring 的管理对象，想注入 Service/Mapper 得绕一圈。
 *   · AOP：能加在 Controller 方法上，但"清理"要靠 try-finally 手写，
 *     而且切面里抛异常时的行为比拦截器更难控制。
 *   · HandlerInterceptor：正好在"进入 Controller 之前、离开之后"两个点上都有钩子，
 *     而且 afterCompletion 保证执行（哪怕 Controller 抛异常）。
 *     **有明确前/后置钩子的地方，就用拦截器，别硬套 AOP。**
 *
 * ============================================================================
 * 二、拦截哪些、不拦哪些（这一段是安全评审的重点）
 * ============================================================================
 * 用"白名单排除"还是"黑名单排除"，结果完全不同：
 *
 *   ✗ addPathPatterns("/api/stat/**", "/api/export/**", ...)  ← 白名单
 *     以后新增一个 /api/report/chart 接口，忘了加进列表 → **这个接口没有身份**
 *     → 走到 Mapper 拦截器时抛 403（我们写了 fail-closed，所以还算安全）
 *     → 但如果那个接口不查标了 @DataScope 的表呢？那就彻底裸奔了。
 *
 *   ✓ addPathPatterns("/api/**") + excludePathPatterns(少数几个)  ← 黑名单
 *     新增接口**默认受保护**，要放行必须显式加一行。
 *     这才是安全该有的默认值：**默认拒绝，例外显式声明**。
 *     这和前面 buildCondition 里"辖区为空就返回 1=0"是同一个原则。
 *
 * 这里排除的两个：
 *   · /api/auth/users —— "现在系统里有哪些用户 id"，是实验用的说明书，
 *     不含业务数据，而且没有它就没法开始实验（鸡生蛋问题）。
 *   · /api/auth/data-scope —— 权限标注清单，纯静态信息。
 *
 * ⚠️ 真实项目里这两个接口**都该删掉或加管理员权限**：
 *    把"系统里有哪些账号"暴露给未登录用户，本身就是一份攻击情报。
 * ============================================================================
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final UserContextInterceptor userContextInterceptor;

    public WebConfig(UserContextInterceptor userContextInterceptor) {
        this.userContextInterceptor = userContextInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userContextInterceptor)
                // 默认全部受保护
                .addPathPatterns("/api/**")
                // 例外：实验辅助接口
                .excludePathPatterns("/api/auth/users", "/api/auth/data-scope")
                // 顺序值越小越先执行。这里只有一个拦截器，写出来是为了将来加
                // "限流拦截器""审计拦截器"时有个明确的排序依据。
                .order(1);
    }
}

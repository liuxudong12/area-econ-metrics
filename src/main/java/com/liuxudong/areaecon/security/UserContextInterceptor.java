package com.liuxudong.areaecon.security;

import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.entity.DataScopeType;
import com.liuxudong.areaecon.entity.SysUser;
import com.liuxudong.areaecon.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 从请求头里解析出"我是谁"，塞进 {@link DataScopeContext}。
 *
 * ============================================================================
 * 一、它在整个链路的哪个位置
 * ============================================================================
 * <pre>
 *   HTTP 请求
 *     ↓
 *   Tomcat 线程从池里取一个 → UserContextInterceptor.preHandle()
 *       ① 读请求头 X-User-Id
 *       ② 查 sys_user 拿到这个人的 data_scope / region_code
 *       ③ 组装成 LoginUser 放进 ThreadLocal
 *     ↓
 *   Controller → Service → Mapper
 *     （Mapper 拦截器从 ThreadLocal 里取身份，改写 SQL）
 *     ↓
 *   UserContextInterceptor.afterCompletion()  → **清空 ThreadLocal**
 *     ↓
 *   线程归还给线程池
 * </pre>
 *
 * 第三步那个 afterCompletion 是整个类里最关键的一行 —— 理由见
 * {@link DataScopeContext} 的类注释。**没有它，权限系统会在高并发下随机串号。**
 *
 * ============================================================================
 * 二、⚠️ X-User-Id 只是个教学替身，绝不能上生产
 * ============================================================================
 * 请求头是客户端可以随便改的：用 Postman 把 X-User-Id 从 3 改成 1，
 * 立刻就是省厅管理员的权限。所以这个方案只用于"把注意力集中在权限过滤逻辑上"。
 *
 * 真实项目里"当前用户"只有三种可靠来源（面试可以答这一段）：
 *   ① JWT：token 用服务端密钥签名，改一个字节就验签失败；用户信息在 token 里，不用查库；
 *   ② Session：服务端存，客户端只拿一个随机 sessionId，伪造不了；
 *   ③ OAuth2 / 企业 SSO：交给统一认证中心，业务系统只验 token。
 *
 * 那为什么"必须查库"这件事跑不掉？因为 data_scope / region_code 是**管理员随时能改**的，
 * 不能固化在 token 里（改了要等 token 过期才生效，安全上是漏洞）。
 * 折中是：token 里只放 userId，每次请求按 userId 查一次用户信息 —— 这就是本项目做的事，
 * 也是绝大多数企业系统的实际做法。查一次主键约 0.1~0.3ms，完全可接受（手册里有实测）。
 */
@Slf4j
@Component
public class UserContextInterceptor implements HandlerInterceptor {

    /** 请求头名字。全项目只有这一个地方读写它，改名字只改这里 */
    public static final String HEADER_USER_ID = "X-User-Id";

    private final SysUserMapper sysUserMapper;

    public UserContextInterceptor(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        // ★ 第一件事就把 ThreadLocal 清空。
        //   看起来多余（反正 afterCompletion 会清），但这是**防御性的一手**：
        //   如果上一次请求因为某种原因没走到 afterCompletion（比如容器 kill 了线程、
        //   或者有人手写了 try-finally 漏了），这里能保证不会带着上一个人的身份开工。
        //   代价是一次 remove()，可以忽略。
        DataScopeContext.clear();

        String raw = request.getHeader(HEADER_USER_ID);
        if (raw == null || raw.isBlank()) {
            // 【为什么这里必须报错，而不是"当匿名用户放行"】
            // 因为一旦放行，后续拦截器会发现"没有用户"，然后有两种写法：
            //   · 不加过滤条件   → 匿名请求能看到全量数据（灾难）
            //   · 抛空指针       → 变成 500，排查半天才发现是"没带身份"
            // 两者都不可接受。**在入口就明确拒绝**，让错误一眼可见 —— 还是 fail fast。
            throw new BizException(401, "缺少身份信息：请在请求头带上 " + HEADER_USER_ID + "（本项目未实现登录，测试时手填用户 id）");
        }

        long userId;
        try {
            userId = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // 把原始输入放进错误信息里。注意这里是**回显用户自己的输入**，
            // 虽然有一个极小的反射型 XSS 风险（如果前端用了 innerHTML），
            // 但本项目没有前端，且这个字段本身也进不了 SQL。真实项目里建议脱敏。
            throw new BizException(401, HEADER_USER_ID + " 必须是数字用户 id，本次传入：" + raw);
        }

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "用户不存在或已删除：" + userId);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            // 停用用户的处理：401 而不是 403。
            // 语义上"你的账号不能用了"和"你没登录"对前端是同一件事 —— 都是去登录页。
            throw new BizException(401, "用户已停用：" + user.getUsername());
        }

        DataScopeType scope = DataScopeType.parse(user.getDataScope());
        if (scope == null) {
            // 数据库里存了枚举里没有的值（比如有人手工改成了 'ALLOW'）。
            // ⚠️ 这里**绝不能默认成 ALL** —— 配置错误的方向必须是"更严格"，
            // 而不是"更宽松"。返回 403 让人去修数据，比"悄悄给出全量数据"安全一万倍。
            throw new BizException(403, "用户的数据范围配置无效：" + user.getDataScope() + "，请联系管理员");
        }

        LoginUser loginUser = new LoginUser(
                user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRoleCode(), scope, trimToNull(user.getRegionCode()));

        DataScopeContext.set(loginUser);

        // 只在 debug 级别打身份日志：正常业务日志已经够多了，不该被这行淹没。
        // 但如果排查"为什么这个人看到的数据不对"，打开 debug 就能看到他的范围到底是什么。
        if (log.isDebugEnabled()) {
            log.debug("[权限] 用户 id={} name={} scope={} region={} uri={}",
                    loginUser.id(), loginUser.username(), loginUser.dataScope(),
                    loginUser.regionCode(), request.getRequestURI());
        }
        return true;
    }

    /**
     * 请求结束（无论成功、失败、抛异常）都会走这里 —— 是清理 ThreadLocal 唯一可靠的位置。
     *
     * 【为什么不在 postHandle 里清？】
     * postHandle 只在 Controller **正常返回**后执行；抛异常时不会执行。
     * 也就是说"出错的那个请求"会把身份留在 ThreadLocal 里 —— 而恰恰是出错的请求
     * 更容易被重试、被并发，泄漏的概率更高。**清理逻辑必须挂在"一定会执行"的钩子上。**
     *
     * 【afterCompletion 真的"一定"执行吗】
     * 只要 preHandle 返回过 true，就一定会执行（包括异常）。
     * 如果 preHandle 抛了异常，本拦截器的 afterCompletion 不会执行 —— 但那时还没 set，
     * 所以没有泄漏。这个分析必须做完整，否则"以为清了其实没清"。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        DataScopeContext.clear();
        if (ex != null) {
            // 顺手把"哪个接口出错"记下来。业务异常已在 GlobalExceptionHandler 记过，
            // 这里只补一个 URI 上下文，方便定位。
            log.debug("[权限] 请求结束(有异常) uri={} 原因={}", request.getRequestURI(), ex.getMessage());
        }
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

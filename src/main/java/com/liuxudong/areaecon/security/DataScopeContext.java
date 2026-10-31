package com.liuxudong.areaecon.security;

/**
 * 当前请求的用户上下文 —— 一个 ThreadLocal。
 *
 * ============================================================================
 * 一、它解决什么问题
 * ============================================================================
 * 数据权限的过滤条件必须出现在**每一条**受约束的 SQL 里。
 * 但如果把"当前用户"当成参数一层层往下传，就会变成这样：
 *
 * <pre>
 *   Controller → Service.method(category, periodType, periodCodes, currentUser)
 *              → Mapper.selectXxx(category, periodType, periodCodes, currentUser)
 * </pre>
 *
 * 每个方法签名都多一个参数，而且业务方法根本不关心这个人是谁，只是"顺手带过去"。
 * 这叫**参数污染**。ThreadLocal 的作用就是：让"当前用户"像全局变量一样可取，
 * 但**每个线程各有一份**，互不干扰。
 *
 * ============================================================================
 * 二、⚠️ 这个类最大的风险：忘记清理
 * ============================================================================
 * Tomcat 的线程是**复用**的。一个线程处理完请求 A，回头就去处理请求 B。
 * 如果处理 A 的时候放进 ThreadLocal 却没删，那么 B 进来时读到的还是 **A 的身份**。
 *
 * 后果有多严重？—— 长沙市的管理员 A 发完请求，下一个请求（可能是张家界的管理员 B，
 * 甚至是没登录的匿名请求）恰好落到同一个线程上，就会**顶着 A 的身份**查数据。
 * 这叫"串号"，是 ThreadLocal 使用不当最经典的事故，而且它**偶发**：
 * 压测时线程不空闲，几乎复现不出来；线上用户一多就偶发出现别人的数据。
 *
 * 所以本项目的规矩是：**set 和 clear 必须配对，而且 clear 写在 finally 里**。
 * 位置在 {@link UserContextInterceptor#afterCompletion} —— 那个方法
 * **只要请求进入了 preHandle，无论成功失败都会执行**，是唯一可靠的位置。
 *
 * 补充：Spring 自己的 RequestContextHolder、SecurityContextHolder 也是同样的套路，
 * 它们同样依赖过滤器里的 finally 来做清理。
 *
 * ============================================================================
 * 三、为什么不是 static 变量、不是 @RequestScope Bean
 * ============================================================================
 *   · static 变量：所有线程共用一份 → 并发请求互相覆盖，比不清理还糟；
 *   · @RequestScope Bean：能用，但它需要被代理注入，在有异步/线程池的场景里
 *     拿到的是"另一个请求的代理"，容易踩坑，也不方便在 Mapper 拦截器里直接用；
 *   · ThreadLocal：零成本获取、天然线程隔离，正是这个场景的标准解法。
 *
 * 真正的代价在**跨线程**：线程池里的任务不会继承 ThreadLocal。
 * 手册里的"实验 E7"专门验证这一条 —— 异步线程里读到的必然是 null，
 * 而 null 在权限判定里意味着什么，取决于你有没有写那行 else。
 */
public final class DataScopeContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private DataScopeContext() {
    }

    /** 由 {@link UserContextInterceptor} 在请求进入时写入 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 取当前用户。返回 null 表示"这个线程上没有身份信息" */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 清理。**必须调用**，理由见类注释第二节。
     *
     * 用 remove() 而不是 set(null)：后者只是把值置空，Entry 还挂在线程的
     * ThreadLocalMap 里；如果这个 ThreadLocal 对象本身被回收了，Entry 的 key 变弱引用、
     * value 却还在，就成了"key 为 null 的脏 Entry"。remove() 才是干净的做法。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 【给异步场景用的手工传递】
     *
     * 线程池里的任务拿不到 ThreadLocal，所以提交任务的一方要"抓一份当时的身份"
     * 一起带过去，在目标线程里 set 完、用完再恢复。
     * 标准做法是给线程池加 TaskDecorator（Spring 提供的钩子），
     * 这样业务代码完全无感知 —— 见 {@code ImportThreadPoolConfig}。
     *
     * 这里把"抓"和"恢复"抽成两个方法，是为了让那两行逻辑只有一份实现。
     */
    public static LoginUser capture() {
        return HOLDER.get();
    }

    /** 恢复。传 null 等价于 clear */
    public static void restore(LoginUser user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }
}

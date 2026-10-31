package com.liuxudong.areaecon.security;

import com.liuxudong.areaecon.entity.DataScopeType;

/**
 * 当前登录用户 —— 一次请求全程只需要这一份"我是谁、我能看多少"的信息。
 *
 * 【为什么用 record 而不是普通的 @Data 类】
 * 因为它**在整条链路里只读**：拦截器构造它，后面所有地方都只读不写。
 * record 天然不可变（字段 final、没有 setter），正好表达这个语义：
 * 谁都不能在处理请求的中途把 regionCode 改掉 —— 那等于临时给自己扩权。
 *
 * 用 Integer 之类的包装类做字段、又提供 setter 的写法，在权限对象上是个隐患：
 * 只要有一处代码 {@code user.setRegionCode(null)}，后面所有 SQL 就都不过滤了。
 * **权限对象应该是不可变的**，这条不是洁癖，是防守。
 */
public record LoginUser(
        Long id,
        String username,
        String displayName,
        String roleCode,
        DataScopeType dataScope,
        String regionCode
) {

    /** 是否全量可见（不受区域过滤） */
    public boolean isAllScope() {
        return dataScope == DataScopeType.ALL;
    }

    /**
     * 用于日志和缓存 key 的"权限指纹"。
     *
     * <pre>
     *   ALL            → "ALL"      全量
     *   REGION + 4301  → "R4301"    长沙
     *   REGION + null  → "NONE"     什么也看不到
     * </pre>
     *
     * 【为什么要单独搞一个"指纹"】
     * 因为缓存 key 里不能直接放 {@code regionCode} —— null 会变成字符串 "null"，
     * 更要命的是 **ALL 和 REGION+null 都会拼出同一个串**，
     * 而这两者的结果一个是全量、一个是零行，差着 48 万行。
     * （手册里"实验 E6：缓存串味"就是拿这个差异做验证的。）
     * 把语义差异收敛到这一个方法里，调用方就不会写错。
     */
    public String scopeKey() {
        if (dataScope == DataScopeType.ALL) {
            return "ALL";
        }
        if (regionCode == null || regionCode.isBlank()) {
            return "NONE";
        }
        return "R" + regionCode;
    }
}

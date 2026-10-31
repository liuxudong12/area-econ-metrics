package com.liuxudong.areaecon.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记在 Mapper 方法上：**这条 SQL 需要按数据权限过滤**。
 *
 * <pre>
 *   &#64;DataScope(alias = "f")
 *   List&lt;RegionMetricStatVO&gt; selectCategorySummary(...);
 * </pre>
 *
 * ============================================================================
 * 一、为什么用"注解"而不是"自动检测所有 SQL"
 * ============================================================================
 * 有两种设计思路：
 *
 *   思路 A（自动）：拦截所有 SQL，只要 FROM 里出现了 fact_indicator_value，
 *                   就自动给它加 region_code 过滤。
 *   思路 B（显式）：让开发者主动标记哪条 SQL 要受权限约束。
 *
 * 我选了 B，理由有三条，都能在面试里讲：
 *
 *   ① **不是所有 SQL 都该被过滤**。比如"补区域名称"那条
 *      {@code SELECT * FROM dim_region WHERE region_code IN (...)}，
 *      它的入参已经是过滤后的结果，再加一遍纯属浪费；
 *      又比如统计"全省排名"时，分母要不要算别的市？业务上说不清 ——
 *      这种必须让**人**来决定，不能让框架猜。
 *
 *   ② **自动检测靠表名匹配，改表名就静默失效**。有人把表名改成 fact_indicator_value_2025，
 *      拦截器还在按老名字找 —— 不报错，权限直接没了。这类"静默失效"是安全类 bug 里最可怕的。
 *
 *   ③ **显式标记本身就是一份清单**。想知道"哪些接口受数据权限约束"，
 *      全局搜索 {@code @DataScope} 就行，不用读一遍拦截器逻辑再自己推断。
 *      做安全评审时，这份清单能直接交上去。
 *
 * 代价也很明确：**漏标 = 漏权限**。所以手册里给了"旁路扫描"的思路：
 * 启动时把所有 Mapper 方法列出来，逐个提示"有没有标"，让漏标可见。
 *
 * ============================================================================
 * 二、alias 这个属性为什么必须有
 * ============================================================================
 * 因为同一张表在不同 SQL 里别名可以不同，而且 SQL 里经常有子查询。
 * 举个我们项目里真实存在的例子（第 6 天的 TOP N 排行）：
 *
 * <pre>
 *   SELECT t.regionCode, RANK() OVER (...) FROM (
 *       SELECT f.region_code, SUM(...) FROM fact_indicator_value f
 *       WHERE f.deleted = 0 ...            ← 过滤条件必须加在**这一层**
 *       GROUP BY f.region_code
 *   ) t
 *   ORDER BY t.totalValue DESC LIMIT 10
 * </pre>
 *
 * 如果拦截器把 {@code AND f.region_code LIKE '4301%'} 加到**最外层**的 WHERE 上，
 * 会直接报错 "Unknown column 'f.region_code'" —— 外层根本没有 f 这个别名，只有 t。
 *
 * 所以拦截器必须按 alias **递归找到正确的层级**再改。
 * 这就是手册里"实验 E4：子查询/窗口函数是数据权限的第一个坟场"要讲的东西。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {

    /**
     * 表别名。比如 XML 里写的是 {@code FROM fact_indicator_value f}，这里就填 "f"。
     *
     * 留空字符串表示"不指定别名"，此时生成的条件不带表前缀（{@code region_code LIKE ?}）。
     * 只有在**单表查询且确定不会有歧义**时才这么用 —— 多表 JOIN 时不带前缀会报
     * "Column 'region_code' in where clause is ambiguous"。
     */
    String alias() default "";

    /**
     * 用来做范围过滤的列名。默认 region_code，允许改是为了兼容别的表
     * （比如以后加一张按月分区的表，列名可能叫 area_code）。
     */
    String column() default "region_code";
}

package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 同比增长（YoY）的结果对象。
 *
 * 一次查询同时给出"当期"和"基期"两个值，以及它们的差和比率：
 *
 *     区域      当期值(2025)   基期值(2024)    增长额      增长率
 *     长沙市     1200000        1000000       200000      20.00%
 *     株洲市      880000         900000       -20000      -2.22%
 *
 * 【同比 (YoY) 和 环比 (MoM) 的区别】
 *   同比：和**去年同期**比（2025年3月 vs 2024年3月）→ 能排除季节性影响
 *   环比：和**上一个周期**比（2025年3月 vs 2025年2月）→ 更能反映近期变化
 *
 * 但注意：**这两个在代码里是同一件事**，只是传进来的两个周期编码不同而已。
 *   currentPeriod=2025    basePeriod=2024     → 同比
 *   currentPeriod=2026-03 basePeriod=2026-02  → 环比
 * 所以接口只做一个，参数化了就能覆盖两种口径。**这就是接口设计的"正交"原则。**
 */
@Data
public class RegionYoyVO {

    /** 区域编码 */
    private String regionCode;

    /** 区域名称（同样是 Service 侧批量补的，不是 SQL 查出来的） */
    private String regionName;

    /** 当期值 */
    private BigDecimal currentValue;

    /** 基期值（去年同期 / 上一周期） */
    private BigDecimal baseValue;

    /**
     * 增长额 = currentValue - baseValue。
     * 可能为负数（下降）。金额类字段一律 BigDecimal，绝不用 double。
     */
    private BigDecimal growth;

    /**
     * 增长率（%）= growth / baseValue × 100，保留 2 位小数。
     *
     * ⚠️ 基期为 0 时这个值**没有意义**（任何数除以 0 都不是无穷大，是"未定义"），
     *    所以那时返回 null，前端显示成 "--"。
     *    这是真实业务里必须处理的分支，不是可选优化。
     */
    private BigDecimal growthRate;
}

package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 按周期汇总的结果对象（大屏"趋势图"用）。
 *
 * 和 RegionMetricStatVO 是一对兄弟：
 *   RegionMetricStatVO  → 按**区域**分组（横向对比：谁高谁低）
 *   PeriodMetricStatVO  → 按**周期**分组（纵向走势：涨了还是跌了）
 *
 * 同一张事实表，换个 GROUP BY 字段，就是大屏上的另一张图。
 * 这就是"事实表 + 维表"建模的好处 —— 加一张图不用改表结构。
 */
@Data
public class PeriodMetricStatVO {

    /** 周期编码，如 2024 / 2024Q1 / 2024-01 */
    private String periodCode;

    /** 该周期下的汇总值 */
    private BigDecimal totalValue;
}

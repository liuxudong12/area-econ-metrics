package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 周期维表（dim_period）。
 *
 * 【为什么"时间"也要单独建一张表】
 * 新手常见写法：值表里直接存一个 date 字段，查询时自己算"今年""本季度"。
 * 问题是口径不统一 —— 有人按自然年、有人按财年；有人算到 3 月 31 日、有人算到 4 月 30 日。
 * 一旦口径不一致，就会出现"两个报表数字对不上"这类经典事故。
 *
 * 正确做法：把所有周期（年 / 季 / 月）提前生成好，一张表管住。
 *   period_code   2026 / 2026Q1 / 2026-01   人看的
 *   period_type   Y / Q / M                 程序筛选用
 *   period_year   所属年份                   按年聚合用
 *   period_index  年内序号（Q1=1, Q2=2…）    排序、取"最近 N 期"用
 *   start_date / end_date                   这个周期的起止日期
 *
 * 这就是"统一时间口径"的落点，也是你简历上「统计口径治理」那条的同类思路。
 *
 * 注意：这张表**没有 deleted 字段**，所以这个实体类里也不写。
 *      MyBatis-Plus 的全局逻辑删除配置是"实体里有这个属性才生效"，没有就不管。
 */
@Data
@TableName("dim_period")
public class Period {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 周期编码：2026 / 2026Q1 / 2026-01 */
    private String periodCode;

    /** 周期类型：Y 年 / Q 季 / M 月 */
    private String periodType;

    /** 所属年份 */
    private Integer periodYear;

    /** 年内序号，用于排序，如 2026Q1 → 1 */
    private Integer periodIndex;

    /** 周期开始日期 */
    private LocalDate startDate;

    /** 周期结束日期 */
    private LocalDate endDate;
}

package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标值事实表（fact_indicator_value）—— 本项目最核心、也是唯一会"变大"的表。
 *
 * 【它长什么样】
 * 一行 = 某个区域 + 某个指标 + 某个周期 的一个值。
 * 举例：
 *   娄底市 · GDP · 2025年  →  1980.5000
 *   娄星区 · 规上工业增加值 · 2025Q2  →  42.3000
 *
 * 【为什么金额要用 BigDecimal 而不是 double】
 * double 是二进制浮点数，存不了精确的十进制小数，经典例子：
 *     0.1 + 0.2 == 0.30000000000000004
 * 钱一旦算错，就是事故。所以金额、财务、指标值一律用 BigDecimal。
 *
 * 面试常问：BigDecimal 用的时候要注意什么？
 * 答：① 比较大小要用 compareTo()，不能用 equals()（equals 会比较精度位数，1.0 和 1.00 不相等）；
 *     ② 除法必须指定精度和舍入模式，否则除不尽会抛 ArithmeticException；
 *     ③ 优先用 String 构造（new BigDecimal("0.1")），别用 double 构造（会带进误差）。
 *
 * 【表上那个唯一索引 uk_fact(region_code, indicator_code, period_code)】
 * 它保证"同一区域同一指标同一周期只有一条数据"。
 * 有两层作用：
 *   ① 数据质量兜底 —— 重复导入不会产生脏数据；
 *   ② 第 11 天做"异步导入 + 幂等"时，它是防并发重复插入的最后一道闸门。
 *
 * 【★ 第 6-8 天的实验对象】
 * 这张表现在**故意没有建 period_type 相关的索引**，所以大屏那种聚合查询会走全表扫描。
 * 到时候你会亲手用 EXPLAIN 看到它慢，然后加索引让它变快 —— 数字是你自己测出来的。
 */
@Data
@TableName("fact_indicator_value")
public class FactIndicatorValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 区域编码 */
    private String regionCode;

    /** 指标编码 */
    private String indicatorCode;

    /** 周期编码 */
    private String periodCode;

    /** 周期类型：Y / Q / M —— 冗余存一份，避免聚合时还要 join 周期表 */
    private String periodType;

    /** 指标值（金额/数值，用 BigDecimal 保证精度） */
    private BigDecimal indicatorValue;

    /** 数据来源：手工 / 导入 / 接口 */
    private String dataSource;

    /** 备注 */
    private String remark;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

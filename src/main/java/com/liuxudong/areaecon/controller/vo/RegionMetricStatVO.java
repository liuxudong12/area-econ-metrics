package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 聚合统计的结果对象 —— 注意它**不是一个数据库表**。
 *
 * 【为什么要单独建一个类？】
 * 因为聚合查询的结果"长不成一张表的样子"。
 * 数据库里没有"区域汇总"这张表，它是 SUM() 算出来的临时结构：
 *
 *     区域编码    区域名称   汇总值
 *     430100     长沙市     123456.7890
 *     430200     株洲市      98765.4321
 *
 * 这跟 Region 实体（一行 = 一条记录）完全不是一回事。
 * 所以返回结果也要用专门的类来装，这叫 VO（也有人叫 DTO / Response）。
 *
 * 【一个常见误区】
 * 新手会图省事，把聚合结果塞进实体类里，或者用 Map<String,Object> 接收。
 * 前者会让实体多出莫名其妙的字段；后者没有类型信息，IDE 不提示、写错不报错。
 * 正确做法就是：**一个查询场景，配一个 VO**。
 *
 * 【字段名必须和 SQL 里的别名对上】
 * SQL 里写 `AS regionCode`，这里就是 `regionCode`；
 * 如果 SQL 里写的是 `region_code`，那就靠 `map-underscore-to-camel-case: true` 自动转。
 * 两种都行，但**必须能对上**，对不上就是 null —— 而且不报错，这是最常见的坑。
 */
@Data
public class RegionMetricStatVO {

    /** 区域编码 */
    private String regionCode;

    /** 区域名称 */
    private String regionName;

    /** 汇总值：SUM(indicator_value)。金额/数值一律用 BigDecimal */
    private BigDecimal totalValue;
}

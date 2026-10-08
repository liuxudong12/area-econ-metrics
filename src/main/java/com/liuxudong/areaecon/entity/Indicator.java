package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标维表（dim_indicator）。
 *
 * 【这张表是整个项目的"灵魂"】
 * 区域经济运行有几十上百个指标：GDP、工业增加值、用电量、财政收入、社零……
 * 如果每个指标建一张表，就是几十张表；每加一个指标就要建表、改代码、发版。
 *
 * 换个思路：只建"一张值表"（fact_indicator_value），
 * 再用这张 dim_indicator 表把"有哪些指标"描述出来。
 * 想加指标？插一行数据就行，代码一行都不用改。
 *
 * 这叫"维度建模"，是数据仓库/BI 项目的标准做法。
 * 面试时能说出这一句，比背十个八股有用：
 *   「我没有为每个指标建表，而是抽象了指标维表 + 值事实表，用指标编码体系横向扩展。」
 *
 * 【indicator_code 的层级设计】
 *   '1'      综合经济
 *   '1-1'    GDP          （parent_code = '1'）
 *   '1-1-1'  第一产业增加值  （parent_code = '1-1'）
 * 用编码前缀天然表达了树形层级，比单独加一个 parent_id 更容易人工识别和导入。
 */
@Data
@TableName("dim_indicator")
public class Indicator {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 指标编码，如 1-1 */
    private String indicatorCode;

    /** 指标名称，如 GDP */
    private String indicatorName;

    /** 上级指标编码，顶层为 null */
    private String parentCode;

    /** 分类：综合经济 / 工业 / 财政 / 民生 / 投资 */
    private String category;

    /** 单位：亿元 / % / 家 / 万元…… */
    private String unit;

    /** 值类型：1 数值 / 2 百分比 / 3 金额 —— 前端按这个决定怎么格式化显示 */
    private Integer valueType;

    /** 排序号 */
    private Integer sort;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

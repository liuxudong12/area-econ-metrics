package com.liuxudong.areaecon.controller.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 明细导出的一行（对应 Excel 的一行）。
 *
 * ==========================================================================
 * 【为什么要单独建一个 VO，不能直接导出 FactIndicatorValue 实体】
 * ==========================================================================
 * 三个原因，一个比一个实际：
 *
 * ① 实体里有多余字段
 *    实体有 id、deleted、create_time、update_time —— 用户不需要，也不该看到。
 *    尤其 deleted（逻辑删除标记）和 id（数据库主键），泄露出去毫无意义还增加理解成本。
 *
 * ② 实体里**缺**用户需要的字段
 *    用户看"430102"不知道是哪个区，需要 region_name；
 *    看"1-1-1"不知道是什么指标，需要 indicator_name 和 unit。
 *    这些字段在维表里，不在事实表里 → 必须补。
 *
 * ③ 列的顺序和名字是"给人看的产品决策"，不是"数据库结构"
 *    数据库里列叫 region_code，用户看到的应该是"区域编码"；
 *    顺序应该是"先看是谁、再看是什么指标、最后看值"，而不是建表时的物理顺序。
 *
 * 一句话：**实体是"数据怎么存"，VO 是"数据怎么给人看"。两者不该是同一个类。**
 */
@Data
public class FactExportRow {

    @ExcelProperty("区域编码")
    @ColumnWidth(14)
    private String regionCode;

    @ExcelProperty("区域名称")
    @ColumnWidth(20)
    private String regionName;

    @ExcelProperty("指标编码")
    @ColumnWidth(14)
    private String indicatorCode;

    @ExcelProperty("指标名称")
    @ColumnWidth(28)
    private String indicatorName;

    @ExcelProperty("单位")
    @ColumnWidth(10)
    private String unit;

    @ExcelProperty("周期编码")
    @ColumnWidth(14)
    private String periodCode;

    @ExcelProperty("周期类型")
    @ColumnWidth(12)
    private String periodType;

    @ExcelProperty("指标值")
    @ColumnWidth(18)
    private BigDecimal indicatorValue;

    @ExcelProperty("数据来源")
    @ColumnWidth(14)
    private String dataSource;
}

package com.liuxudong.areaecon.controller.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.math.BigDecimal;

/** 区域汇总导出行（对应 /api/stat/category-summary 的结果） */
@Data
public class CategoryStatExportRow {

    @ExcelProperty("区域编码")
    @ColumnWidth(14)
    private String regionCode;

    @ExcelProperty("区域名称")
    @ColumnWidth(20)
    private String regionName;

    @ExcelProperty("汇总值")
    @ColumnWidth(20)
    private BigDecimal totalValue;
}

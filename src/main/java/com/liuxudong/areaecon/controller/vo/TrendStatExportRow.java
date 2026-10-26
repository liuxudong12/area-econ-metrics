package com.liuxudong.areaecon.controller.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.math.BigDecimal;

/** 周期趋势导出行（对应 /api/stat/trend 的结果） */
@Data
public class TrendStatExportRow {

    @ExcelProperty("周期编码")
    @ColumnWidth(14)
    private String periodCode;

    @ExcelProperty("汇总值")
    @ColumnWidth(20)
    private BigDecimal totalValue;
}

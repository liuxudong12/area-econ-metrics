package com.liuxudong.areaecon.controller.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.math.BigDecimal;

/** 同比导出行（对应 /api/stat/yoy 的结果） */
@Data
public class YoyStatExportRow {

    @ExcelProperty("区域编码")
    @ColumnWidth(14)
    private String regionCode;

    @ExcelProperty("区域名称")
    @ColumnWidth(20)
    private String regionName;

    @ExcelProperty("基期值")
    @ColumnWidth(18)
    private BigDecimal baseValue;

    @ExcelProperty("当期值")
    @ColumnWidth(18)
    private BigDecimal currentValue;

    @ExcelProperty("增长额")
    @ColumnWidth(18)
    private BigDecimal growth;

    /** 增长率，单位：%。基期为 0 时是 null（不是 0，也不是无穷大） */
    @ExcelProperty("增长率(%)")
    @ColumnWidth(14)
    private BigDecimal growthRate;
}

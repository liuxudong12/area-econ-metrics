package com.liuxudong.areaecon.controller.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.math.BigDecimal;

/** TOP N 排行导出行（对应 /api/stat/top-regions 的结果） */
@Data
public class TopStatExportRow {

    @ExcelProperty("排名")
    @ColumnWidth(10)
    private Integer rankNo;

    @ExcelProperty("区域编码")
    @ColumnWidth(14)
    private String regionCode;

    @ExcelProperty("区域名称")
    @ColumnWidth(20)
    private String regionName;

    @ExcelProperty("汇总值")
    @ColumnWidth(20)
    private BigDecimal totalValue;

    /** 占比，单位：%。已经是 12.34 这样的数字，不是 0.1234 */
    @ExcelProperty("占比(%)")
    @ColumnWidth(12)
    private BigDecimal percent;
}

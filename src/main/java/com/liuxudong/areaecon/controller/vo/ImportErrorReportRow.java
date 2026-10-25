package com.liuxudong.areaecon.controller.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 错误报告里的一行（导出成 Excel 给用户看）。
 *
 * ==========================================================================
 * 【列的顺序是刻意设计的】
 * ==========================================================================
 *   Excel行号 | 错误类型 | 错误原因 | 区域编码 | 指标编码 | 周期编码 | 周期类型 | 指标值
 *   ↑ 前三列是"诊断信息"，后五列是"原始数据"
 *
 * 为什么把诊断信息放最左边？
 *   因为用户打开文件的第一眼，要看到的是"第 1003 行，周期类型非法，实际填的是「月」"。
 *   如果把这些放在最右边的 S 列，他要一直往右拉才能看到 —— 而左边全是看不懂的编码。
 *   **眼睛先落在最有用的列上**，这是所有表格设计的常识。
 *
 * ==========================================================================
 * 【为什么后 5 列和导入模板的列名完全一致】
 * ==========================================================================
 * 因为用户拿这个文件是**要直接改完重传的**。
 * 只要这 5 个表头一字不差，EasyExcel 重新读取时就能正确绑定；
 * 前面 3 列（行号 / 类型 / 原因）找不到对应的 @ExcelProperty，会被自动忽略 —— 不报错。
 *
 * 这就是"改完直接传"能成立的技术前提。
 * ⚠️ 代价是：用户如果**删掉或改了**这 5 个表头中的任何一个，就会绑定失败、
 *    字段全是 null，然后报"区域编码不能为空"。所以模板下载页要明确写一句
 *    "请勿修改表头"。（前端文案的事，但根源在这里。）
 *
 * ==========================================================================
 * 【为什么指标值在这里是 String】
 * ==========================================================================
 * 因为错误行里的指标值可能根本不是数字 —— "abc"、"12,000"、"1.5万"。
 * 如果是 BigDecimal，EasyExcel 在**写出**时就会尝试转换然后失败，
 * 于是"生成错误报告"这个动作自己报错了。
 * 又是那句话：**描述错误的模型，不能假设错误数据是合法的。**
 */
@Data
public class ImportErrorReportRow {

    /**
     * Excel 里的行号。
     *
     * 【为什么这一列对用户这么重要】
     * 用户在 1 万行的文件里找一行，靠"区域编码=430102、指标编码=1-1-1"是很难找的
     * （要 Ctrl+F 很多次）。但按行号找是精确的：点名称框输入 1003，回车就到了。
     * 这一个数字能把"定位成本"从几十秒降到 1 秒。
     */
    @ExcelProperty(value = "Excel行号", index = 0)
    private Integer rowNum;

    /** 错误类型的中文名（不是数据库里的枚举 code —— 那是给程序看的） */
    @ExcelProperty(value = "错误类型", index = 1)
    private String errorTypeLabel;

    /** 详细原因 */
    @ExcelProperty(value = "错误原因", index = 2)
    private String errorMsg;

    // ---------- 下面 5 列：和导入模板的表头完全一致 ----------
    @ExcelProperty(value = "区域编码", index = 3)
    private String regionCode;

    @ExcelProperty(value = "指标编码", index = 4)
    private String indicatorCode;

    @ExcelProperty(value = "周期编码", index = 5)
    private String periodCode;

    @ExcelProperty(value = "周期类型", index = 6)
    private String periodType;

    @ExcelProperty(value = "指标值", index = 7)
    private String indicatorValue;
}

package com.liuxudong.areaecon.controller.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Excel 导入时的"一行"。
 *
 * 【为什么要单独建一个类，不直接用 FactIndicatorValue 实体？】
 * 这是 Day 3 讲过的"VO 参数白名单"思路，在导入场景里同样成立，而且更明显：
 *
 *   ① Excel 里只有 5 列，而实体有 11 个字段（id / deleted / createTime / updateTime ...）。
 *      直接用实体接收，等于把 id、deleted 这些字段暴露给上传文件的人 ——
 *      有人在 Excel 里加一列 "deleted" 并写个 1，就能批量逻辑删除数据。
 *
 *   ② 实体字段名是 regionCode，Excel 表头写的是"区域编码"。
 *      这层"列名 → 字段"的映射本来就要有，放在一个专门的类里最清楚。
 *
 *   ③ 将来 Excel 模板改版（比如加一列"备注"），只需要动这个类，
 *      不用碰核心业务实体。
 *
 * 【@ExcelProperty 是干什么的】
 * EasyExcel 用它做"表头名 → 字段"的绑定。读取时它会先读第一行表头，
 * 发现"区域编码"这个标题，就把它下面的值塞进 regionCode 字段。
 *
 * ⚠️ 两个容易踩的点：
 *   1. 表头文字必须**一字不差**（包括空格、全角/半角括号）。
 *      写"区域代码"就绑不上，字段会是 null —— 而且**不报错**，只是数据全空。
 *      （更隐蔽：如果所有字段都没绑上，你会得到一堆全是 null 的对象。）
 *   2. 中文表头不要带 BOM 或前后空格。用 Excel 另存为 CSV 再转回来时特别容易出现。
 *
 * 【为什么指标值用 BigDecimal 而不是 Double / double】
 * 和第 8 天的规矩同源：这是金额类的经济指标，用 double 会有精度误差
 * （0.1 + 0.2 != 0.3）。数据库列也是 DECIMAL(20,4)，Java 侧用 BigDecimal 类型才对得上。
 * EasyExcel 读 BigDecimal 时会做转换；如果单元格里写的是 "abc"，
 * 会抛 ExcelDataConvertException，里面有出错的行号 —— 这个我们后面要捕获。
 */
@Data
public class FactImportRow {

    /** 区域编码，如 430102 */
    @ExcelProperty("区域编码")
    private String regionCode;

    /** 指标编码，如 2-1 */
    @ExcelProperty("指标编码")
    private String indicatorCode;

    /** 周期编码，如 2027-01 */
    @ExcelProperty("周期编码")
    private String periodCode;

    /** 周期类型：Y 年 / Q 季 / M 月 */
    @ExcelProperty("周期类型")
    private String periodType;

    /** 指标值（金额/数值） */
    @ExcelProperty("指标值")
    private BigDecimal indicatorValue;
}

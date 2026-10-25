package com.liuxudong.areaecon.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.liuxudong.areaecon.controller.vo.FactImportRow;
import com.liuxudong.areaecon.controller.vo.ImportErrorReportRow;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 导出工具：导入模板 + 错误报告。
 *
 * ==========================================================================
 * 【为什么导出要单独一个类，不塞进 Service】
 * ==========================================================================
 * 因为这里全是"Excel 怎么写"的细节：用哪个 handler、列宽、样式、多 sheet。
 * 这些细节和业务（哪个任务、哪些错误行）没有关系 —— 给一份数据，就能写成 Excel。
 *
 * 分开的好处是：Service 里只读业务逻辑，一眼看懂；
 * 哪天要换成 CSV 导出、或者加个"导出成 PDF"，只动这里，不碰业务。
 * **能用一个简单的函数说清楚的事，就不要让它跟别的事混在一起。**
 */
public final class ImportExcelExporter {

    private ImportExcelExporter() {
    }

    /**
     * 写导入模板。
     *
     * 两个 sheet：
     *   ① 数据填写 —— 表头 + 一行示例
     *   ② 填写说明 —— 每一列怎么填、常见错误
     *
     * 【为什么模板里一定要放示例行】
     * 因为"区域编码填什么"这个问题，用一句话解释是解释不清的（"6 位数字"？哪来的 6 位？）。
     * 但给一行真实示例（430102 = 长沙市芙蓉区），用户一看就懂。
     * 示例的成本是一行数据，收益是少掉一半的"我填的对不对"的咨询。
     *
     * 【示例行会不会被误当成用户数据导入】
     * 会 —— 如果用户直接在示例行下面填，示例行还留着，那 430102 那条就被导进去了。
     * 但这条数据本身是合法的（区域 430102、指标 1-1-1 都在维表里），
     * 而且唯一索引会拦住重复。所以危害是"多一条脏数据"，不是"导入失败"。
     * 真实项目里一般会在说明里写明"请删除示例行"，或者干脆不给示例数据、只给格式。
     * 我们保留示例行，因为第一次填的人更需要它。
     *
     * @param out 一般是 HttpServletResponse.getOutputStream()
     */
    public static void writeTemplate(OutputStream out) {
        ExcelWriter writer = EasyExcel.write(out).build();
        try {
            // ------------------------------------------------------------
            // Sheet 1：数据填写
            // ------------------------------------------------------------
            // ★ 关键：把"周期编码"这一列（索引 2）设成文本格式
            //   否则用户在格子里输入 2027-01，Excel 立刻把它变成 2027/1/1。
            //   详见 TextColumnStyleHandler 的注释。
            //
            //   同理"区域编码"（0）和"指标编码"（1）也设成文本：
            //   区域编码 430102 是 6 位数，如果哪天有 043102 这种前导 0 的编码，
            //   数字格式会把 0 吃掉，变成 43102 —— 又是一类静默的数据损坏。
            //   **所有"看起来像数字但其实是编码"的列，都必须是文本。**
            WriteSheet dataSheet = EasyExcel.writerSheet(0, "数据填写")
                    .head(FactImportRow.class)
                    .registerWriteHandler(new TextColumnStyleHandler(0, 1, 2))
                    .build();

            List<FactImportRow> sample = new ArrayList<>();
            sample.add(sampleRow());
            writer.write(sample, dataSheet);

            // ------------------------------------------------------------
            // Sheet 2：填写说明
            // ------------------------------------------------------------
            // 这个 sheet 用 List<List<String>> 手写，不建 VO ——
            // 因为它是一次性的静态说明，建一个类反而是负担。
            // （判断标准：这份数据结构会不会在别处复用？不会就用 List。）
            List<List<String>> hintHead = List.of(
                    List.of("列名"),
                    List.of("是否必填"),
                    List.of("格式"),
                    List.of("示例"),
                    List.of("说明")
            );
            List<List<String>> hints = List.of(
                    List.of("区域编码", "必填", "文本", "430102",
                            "和系统里「区域管理」的编码一致。湖南省 = 430000，长沙市 = 430100，芙蓉区 = 430102"),
                    List.of("指标编码", "必填", "文本", "1-1-1",
                            "来自「指标管理」。1 = 综合经济，1-1 = GDP，1-1-1 = 第一产业"),
                    List.of("周期编码", "必填", "文本", "2027-01",
                            "年度填 2027，季度填 2027-Q1，月度填 2027-01。★ 本列已设为文本，"
                                    + "如果显示成 2027/1/1，说明格式被改了，请重新下载模板"),
                    List.of("周期类型", "必填", "单个字母", "M",
                            "只能填 Y（年）/ Q（季）/ M（月）。填「年」「月」等中文会报错"),
                    List.of("指标值", "必填", "数字", "12345.67",
                            "不能为负数，不能填单位（如「123万」）。最多 4 位小数"),
                    List.of("", "", "", "",
                            "★ 表头行不要修改、不要删除，否则无法识别"),
                    List.of("", "", "", "",
                            "★ 单次最多 50000 行；文件格式为 .xlsx")
            );
            WriteSheet hintSheet = EasyExcel.writerSheet(1, "填写说明")
                    .head(hintHead)
                    .build();
            writer.write(hints, hintSheet);
        } finally {
            // ★ finish() 必须调用，而且必须在 finally 里。
            //   它会写入 xlsx 的结尾结构（zip 的 central directory）。
            //   如果中间抛异常没调用，生成的文件是**损坏的** ——
            //   Excel 打开会提示"无法读取此文件"，但文件大小看起来是正常的，
            //   排查时很容易怀疑是"下载不完整"，实际是写的时候没走完。
            writer.finish();
        }
    }

    /** 模板里的那一行示例数据 */
    private static FactImportRow sampleRow() {
        FactImportRow row = new FactImportRow();
        row.setRegionCode("430102");
        row.setIndicatorCode("1-1-1");
        row.setPeriodCode("2027-01");
        row.setPeriodType("M");
        row.setIndicatorValue(new BigDecimal("12345.67"));
        return row;
    }

    /**
     * 写错误报告（一批一批往外写，避免一次性把几万行全放内存）。
     *
     * 调用方负责分批查数据，这里只管写。
     *
     * @param writer 外部创建的 ExcelWriter（这样调用方可以多次 write 到同一个 sheet）
     * @param rows   这一批要写的行
     */
    public static void writeReportBatch(ExcelWriter writer, WriteSheet sheet,
                                        List<ImportErrorReportRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        writer.write(rows, sheet);
    }

    /**
     * 创建错误报告的 ExcelWriter + WriteSheet。
     *
     * 【为什么把 writer 和 sheet 的创建也暴露出来，不让调用方自己写】
     * 因为"周期编码要设成文本格式"这件事必须和 writer 一起配置。
     * 如果让调用方自己 EasyExcel.write(...)，很容易漏掉这个 handler，
     * 然后错误报告里的 2027-01 全变成 2027/1/1 —— 用户照着改，改出来还是错的。
     * **容易漏的配置，就封在一个地方，不要给调用方漏的机会。**
     */
    public static ExcelWriter createReportWriter(OutputStream out) {
        return EasyExcel.write(out, ImportErrorReportRow.class)
                // 周期编码在第 5 列，同样要防日期化
                .registerWriteHandler(new TextColumnStyleHandler(3, 4, 5))
                .build();
    }

    /**
     * 错误报告的 sheet。
     *
     * 名字按导出范围区分：
     *   scope=error → "错误明细"（只有错误行）
     *   scope=all   → "全部数据"（所有行，正确行的原因列为空）
     * 用户下载后一眼能看出这是哪种，不会下错。
     */
    public static WriteSheet reportSheet(String sheetName) {
        return EasyExcel.writerSheet(sheetName).build();
    }

    public static WriteSheet reportSheet() {
        return reportSheet("错误明细");
    }
}

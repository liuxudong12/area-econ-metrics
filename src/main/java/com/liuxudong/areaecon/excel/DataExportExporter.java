package com.liuxudong.areaecon.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 通用数据导出器：流式写 Excel + 自动分 sheet。
 *
 * ==========================================================================
 * 【这个类解决两个问题，一个是内存，一个是格式上限】
 * ==========================================================================
 *
 * 问题一：内存不能跟着数据量涨
 * ---------------------------------
 * 反面做法：
 *      List&lt;FactExportRow&gt; all = mapper.selectList(...);   // 48 万条对象，约 100MB+
 *      EasyExcel.write(out, FactExportRow.class).sheet().doWrite(all);
 * 在本地 8G 内存的机器上可能"能跑"，但并发 3 个人同时导出就直接 OOM ——
 * 而且 OOM 是**整个进程挂掉**，不是这一个请求失败，会把所有在线用户一起带走。
 *
 * 正确做法是分页取、边取边写：
 *      for (每页 2000 条) { 从库里查一页 → 写进 Excel → 丢掉对象 }
 * 内存里永远只有一页，跟总行数无关。这就是"内存恒定（O(1) 内存）"。
 *
 * 问题二：xlsx 单 sheet 最多 1,048,576 行
 * ---------------------------------
 * 这是 OOXML 格式写死的限制（列上限 16,384）。超过后 POI 会抛：
 *      IllegalStateException: Invalid row number (1048576) outside allowable range
 * 而我们的表已经 48 万行，几轮导入就会逼近百万。
 *
 * 对策是**自动切 sheet**：写到 100 万行就换个新 sheet 接着写。
 * 用户拿到的还是一个文件，只是里面有多个标签页（数据-1、数据-2）。
 *
 * ==========================================================================
 * 【为什么这个类是泛型 + 可关闭的】
 * ==========================================================================
 * 泛型 &lt;T&gt; 是因为它要能写任何 VO（明细、统计、维表）。
 * 如果给每种数据都写一个 Exporter，会有 8 份几乎一样的 finish()/分 sheet 逻辑，
 * 改一处漏七处 —— 这是最典型的"复制粘贴式技术债"。
 *
 * AutoCloseable 是因为 xlsx 是 zip 包，必须写"中央目录"（central directory）才算完整。
 * 忘记调用 finish() 生成的文件是**损坏的**（Excel 提示"无法读取此文件"），
 * 而文件大小看起来完全正常 —— 排查时极容易误判成"下载不完整"。
 * 用 try-with-resources 把关闭动作交给编译器保证，比靠人记得关靠谱。
 *
 * 用法：
 * <pre>
 *   try (var w = DataExportExporter.open(out, FactExportRow.class, spec)) {
 *       long lastId = 0;
 *       while (true) {
 *           List&lt;FactIndicatorValue&gt; page = mapper.selectForExportByCursor(query, lastId, 2000);
 *           if (page.isEmpty()) break;
 *           w.write(page.stream().map(this::toRow).toList());
 *           lastId = page.get(page.size() - 1).getId();
 *           if (page.size() &lt; 2000) break;
 *       }
 *   }
 * </pre>
 * 注意 try 块结束时（也就是数据全部写完、finish 之后）才关闭输出流 ——
 * 在 try 里面提前 close 流，Excel 的尾部结构就写不出去了。
 */
public final class DataExportExporter {

    /** xlsx 的 MIME 类型。写错的话浏览器会把它当成未知文件，Chrome 会提示"可能有害" */
    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Excel 单 sheet 的硬上限：1,048,576 行（含表头）。留 1 行给表头 */
    public static final int EXCEL_MAX_DATA_ROWS = 1_048_575;

    private DataExportExporter() {
    }

    /**
     * sheet 的规格。用 record 是因为它就是"一组不可变的配置"，不需要 setter。
     *
     * @param namePrefix    sheet 名前缀，多个 sheet 时会变成 "前缀-1"、"前缀-2"
     * @param rowsPerSheet  每个 sheet 最多写多少数据行（不含表头）
     * @param textColumns   哪些列要设成文本格式（区域/指标/周期这类编码列）
     * @param columnWidths  各列宽度（字符数），不够的部分用默认宽度
     */
    public record SheetSpec(String namePrefix,
                            int rowsPerSheet,
                            int[] textColumns,
                            int[] columnWidths) {

        /**
         * 规范化：把不合法的 rowsPerSheet 拉回安全范围。
         *
         * 【为什么要做这个防御】
         * rowsPerSheet 是配置项，可能被人手改成 200 万（"我想一个 sheet 装完"）。
         * 那不叫配置，那叫让 POI 抛异常。配置的边界值应该在**读取时**就夹紧，
         * 而不是等运行时炸了再回来看配置。
         * 这种"把非法输入挡在入口"的做法，比在几十处调用点做防御便宜得多。
         */
        public SheetSpec {
            if (rowsPerSheet <= 0 || rowsPerSheet > EXCEL_MAX_DATA_ROWS) {
                rowsPerSheet = EXCEL_MAX_DATA_ROWS;
            }
        }
    }

    /**
     * 打开一个流式写入器。
     *
     * @param out       响应的输出流（或任何 OutputStream）
     * @param headClass 表头对应的 VO 类（靠 @ExcelProperty 决定列名和顺序）
     * @param spec      sheet 规格
     */
    public static <T> PagedWriter<T> open(OutputStream out, Class<T> headClass, SheetSpec spec) {
        ExcelWriter writer = EasyExcel.write(out)
                // handler 注册在 writer 级别 → 对之后创建的每一个 sheet 都生效，
                // 不用在切 sheet 的时候重新注册（漏注册就会导致第 2 个 sheet 的编码列变日期）
                .registerWriteHandler(new ExportSheetStyleHandler(spec.textColumns(), spec.columnWidths()))
                .build();
        return new PagedWriter<>(writer, headClass, spec);
    }

    /**
     * 一次性写完全部数据（不游标、不分 sheet），用于**小结果集**。
     *
     * ==========================================================================
     * 【为什么有了 PagedWriter 还要这个方法】
     * ==========================================================================
     * 因为维表只有 1350 / 18 / 72 行。给它套一套游标分页 + 切 sheet 的机制，
     * 属于"用大炮打蚊子" —— 代码更复杂，性能一点没提升。
     *
     * 这里体现的是一个判断力：**同一个项目里两种写法并存，不是不统一，
     * 而是按数据量级选择**。
     *   事实表（48 万行、还会继续涨）→ 必须流式 + 分 sheet
     *   维表（一千多行、基本不变）  → 一次写完，代码短、看得懂
     *
     * 为什么这里不用游标分页？因为数据量只有 1350 行，
     * 分页带来的复杂度大于收益" —— 这比"我从头到尾只用一种写法"更能说明你在思考。
     *
     * @param head 表头，形如 [[列1],[列2],...]。用 List 而不是 VO，
     *             因为维表的"列"就是表的列本身，是数据驱动的，不需要再建三个 VO 类
     * @param rows 数据行，每行是 List&lt;Object&gt;，顺序要和 head 一致
     */
    public static void writeSimple(OutputStream out,
                                   List<List<String>> head,
                                   List<List<Object>> rows,
                                   SheetSpec spec) {
        ExcelWriter writer = EasyExcel.write(out)
                .registerWriteHandler(new ExportSheetStyleHandler(spec.textColumns(), spec.columnWidths()))
                .build();
        try {
            WriteSheet sheet = EasyExcel.writerSheet(0, spec.namePrefix())
                    .head(head)
                    .build();
            writer.write(rows, sheet);
        } finally {
            // 和 PagedWriter 同理：不 finish 就是损坏的 xlsx。
            writer.finish();
        }
    }

    /**
     * 分页写入器。调用方按页调用 {@link #write(List)}，内部负责切 sheet 和统计。
     */
    public static final class PagedWriter<T> implements AutoCloseable {

        private final ExcelWriter writer;
        private final Class<T> headClass;
        private final SheetSpec spec;

        /** 当前是第几个 sheet（0 开始） */
        private int sheetNo = -1;
        /** 当前 sheet 已经写了多少数据行 */
        private int rowsInSheet;
        /** 总共写了多少数据行 */
        private long totalRows;

        /** 当前 sheet 对象。为 null 表示"下次写之前要先建 sheet" */
        private WriteSheet currentSheet;

        private boolean finished;

        private PagedWriter(ExcelWriter writer, Class<T> headClass, SheetSpec spec) {
            this.writer = writer;
            this.headClass = headClass;
            this.spec = spec;
        }

        /**
         * 写一批数据。会自动处理"这一批跨过了 sheet 边界"的情况。
         *
         * 【为什么要处理"跨边界"】
         * 假设每 sheet 上限 1000 行，当前 sheet 已写 900 行，这一批有 300 行。
         * 如果直接整批写进去 → 变成 1200 行 → 超限。
         * 所以必须拆成 100 行给旧 sheet、200 行给新 sheet。
         *
         * 这个细节很容易漏：只在"每页条数刚好整除每 sheet 行数"的测试数据下看不出来，
         * 一旦换成真实数据（比如 1063 行）就会抛 IllegalStateException。
         */
        public void write(List<T> rows) {
            if (rows == null || rows.isEmpty()) {
                return;
            }
            int from = 0;
            int size = rows.size();
            while (from < size) {
                ensureSheet();
                int remainInSheet = spec.rowsPerSheet() - rowsInSheet;
                int to = Math.min(from + remainInSheet, size);

                // subList 返回的是原 List 的**视图**（不复制元素），
                // EasyExcel 会在 write 内部同步遍历完，所以这里不会出现
                // "视图失效"或"数据被改动"的问题。2000 条数据一个元素都不拷贝。
                writer.write(rows.subList(from, to), currentSheet);

                int n = to - from;
                rowsInSheet += n;
                totalRows += n;
                from = to;

                if (rowsInSheet >= spec.rowsPerSheet()) {
                    // 满了 → 标记下次写之前换新 sheet。
                    // 不在这里立刻建：如果这批刚好写完且没有下一批，
                    // 提前建 sheet 会多出一个**空 sheet**（Excel 里显示一个空白标签页）。
                    currentSheet = null;
                }
            }
        }

        /** 需要的时候创建下一个 sheet */
        private void ensureSheet() {
            if (currentSheet != null) {
                return;
            }
            sheetNo++;
            rowsInSheet = 0;
            String name = sheetNo == 0
                    ? spec.namePrefix()
                    : spec.namePrefix() + "-" + (sheetNo + 1);
            currentSheet = EasyExcel.writerSheet(sheetNo, name)
                    .head(headClass)
                    .build();
        }

        /** 已写入的数据总行数（不含表头） */
        public long getTotalRows() {
            return totalRows;
        }

        /** 生成了几个 sheet */
        public int getSheetCount() {
            return Math.max(sheetNo + 1, 0);
        }

        /**
         * 收尾：flush + finish。
         *
         * 注意**不要**在这里关 OutputStream —— 那是容器（Tomcat）的事。
         * 我们只负责把 xlsx 的尾部结构写完整。
         */
        @Override
        public void close() {
            if (finished) {
                return;
            }
            finished = true;
            // finish() 内部会 flush POI 的缓冲、写 zip 中央目录。
            // 必须执行，且必须在 finally 路径上执行。
            writer.finish();
        }
    }

    /**
     * 生成 Content-Disposition 头（支持中文文件名）。
     *
     * ==========================================================================
     * 【为什么不能直接 setHeader("Content-Disposition", "attachment; filename=报表.xlsx")】
     * ==========================================================================
     * HTTP 头只能放 ASCII 字符。中文字节直接塞进去会被 Tomcat 拒绝（抛
     * IllegalArgumentException: 不可用于 HTTP 头的字符），或者变成乱码。
     *
     * 正确写法是 RFC 5987 定义的双写：
     *      attachment; filename="fallback"; filename*=UTF-8''URL编码后的名字
     *
     *  · filename=      给老浏览器看的（ASCII，这里放 URL 编码后的串）
     *  · filename*=    给现代浏览器看的（明确声明字符集是 UTF-8）
     *
     * ⚠️ URLEncoder 有两个坑：
     *   ① 它按 application/x-www-form-urlencoded 规则编码，**空格会变成加号 +**，
     *      而 + 在文件名里就是字面意义上的加号 → 用户下载到 "销售 报表.xlsx" 变成 "销售+报表.xlsx"。
     *      所以要 .replace("+", "%20")。
     *   ② 它会把 "." 保留、把 "(" ")" 等也编码，这没关系，浏览器能解回来。
     *
     * 【跨域还要加一个头】
     * 前端用 axios/fetch 想读 Content-Disposition 拿文件名时，跨域下浏览器默认
     * **不暴露**这个头，读到的是 null。必须服务端显式声明：
     *      Access-Control-Expose-Headers: Content-Disposition
     * 这个坑在做前后端分离时几乎必踩，而且前端只会说"拿不到文件名"，
     * 很难联想到是服务端少了一个响应头。
     */
    public static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * 把对象转成"人能读的 JSON 字符串"，只用于写日志（不参与反序列化）。
     *
     * 刻意不引 Jackson：为了一条日志引入序列化依赖不值得，
     * 而且 Jackson 遇到循环引用/特殊类型会抛异常 —— 日志逻辑不该有抛异常的可能。
     */
    public static String simpleJson(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            if (v == null) {
                sb.append("null");
            } else {
                sb.append('"').append(String.valueOf(v).replace("\"", "'")).append('"');
            }
        }
        return sb.append('}').toString();
    }
}

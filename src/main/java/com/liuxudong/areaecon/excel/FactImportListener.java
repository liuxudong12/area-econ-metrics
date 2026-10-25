package com.liuxudong.areaecon.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.exception.ExcelDataConvertException;
import com.alibaba.excel.metadata.Cell;
import com.alibaba.excel.metadata.data.CellData;
import com.liuxudong.areaecon.controller.vo.FactImportRow;
import com.liuxudong.areaecon.entity.ImportErrorRow;
import com.liuxudong.areaecon.entity.ImportErrorType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * EasyExcel 的行监听器：每读到一行，EasyExcel 就回调一次 {@link #invoke}。
 *
 * ==========================================================================
 * ★★★ 最重要的一条：这个类**绝对不能被注册成 Spring 单例 Bean** ★★★
 * ==========================================================================
 * 因为它是有状态的：{@code buffer}（攒行的篮子）、{@code totalRows}（计数）、
 * {@code errors}（错误列表）—— 这些是"本次导入"的私有数据。
 *
 * 如果写成 {@code @Component} 单例，会发生什么？
 *   用户 A 上传 1 万行，读到第 300 行（buffer 里攒了 300 行还没到 500 的阈值）；
 *   用户 B 同时上传另一份文件，EasyExcel 拿到的是**同一个 Listener 实例**，
 *   于是 B 的行被塞进 A 的篮子 —— 两份文件的数据混在一起，还互相覆盖计数。
 * 这种 bug 偶发、难复现、日志看起来完全正常，是最难受的一类。
 *
 * 正确做法：**每次导入都 new 一个**（见 ImportServiceImpl 里的用法）。
 * 判断标准很简单：这个对象的状态，是不是"只属于一次请求"？是，就不能做单例。
 * （Spring 单例 Bean 适合无状态的 Service / Mapper / 工具类。）
 *
 * ==========================================================================
 * 【为什么用 Listener 而不是先把整个 Excel 读成 List 再处理？】
 * ==========================================================================
 * EasyExcel 的读取是**流式（SAX）**的：读一行 → 回调一行 → 丢掉这一行。
 * 全程内存里只有"当前这一批"（batchSize 行），跟文件总行数无关。
 *
 * 对比一下：如果用 POI 的 XSSFWorkbook 或 EasyExcel 的 doReadSync() 一次性读成 List，
 * 10 万行的对象全堆在堆内存里，几百 MB 起步，很容易 OOM。
 * 这就是"流式处理"和"全量加载"的区别，也是面试常问的点。
 *
 * ==========================================================================
 * 【Day 11 改造：错误明细从"5 条字符串"变成"结构化对象"】
 * ==========================================================================
 * 改造前：errors 是 List&lt;String&gt;，最多存 5 条（MAX_ERRORS=5）。
 *   一个 1 万行、3000 行有错的文件，用户只能看到 5 条，
 *   剩下的 2995 行要靠他自己肉眼找 —— 等于没有错误处理。
 *
 * 改造后：errors 是 List&lt;ImportErrorRow&gt;，默认最多记 10000 条，
 *   每条带【行号 + 原始 5 列的值 + 错误类型 + 原因】，落库后可以导出成 Excel。
 *   用户在 Excel 里改完直接重传，形成闭环。
 *
 * 为什么原来只存 5 条？因为当时错误信息是拼成一个字符串塞进 async_task.error_msg
 * （VARCHAR(1000)），一条错误 60 多字符，十几个就撑爆了。
 * **数据结构的上限，决定了产品能力的上限。**
 */
@Slf4j
public class FactImportListener extends AnalysisEventListener<FactImportRow> {

    /** 攒够多少行往外交一次 */
    private final int batchSize;

    /**
     * 攒够一批之后交给谁。
     *
     * 这个类**不关心**拿到的那批行会被怎么处理，只负责"解析 + 校验 + 攒批 + 交出去"。
     * 调用方可以：
     *   - 直接写库（边读边写）
     *   - 只收进内存（先全部校验、零错误才开始写，本项目用的就是这个）
     *   - 塞进消息队列
     * 把"解析"和"怎么处理"解耦，是 Listener 模式的价值所在。
     */
    private final Consumer<List<FactImportRow>> batchHandler;

    /** 攒行用的篮子 */
    private final List<FactImportRow> buffer = new ArrayList<>();

    /**
     * 错误明细最多收集多少条（Day 11 从硬编码 5 改成可配置，默认 10000）。
     *
     * 【为什么还要设上限？直接不限量不行吗】
     * 一个 10 万行全错的文件会产生 10 万条错误记录：
     *   · 内存里 10 万个对象（约 30~50MB）
     *   · 数据库里 10 万行（一次导入就写 10 万条错误日志，是灾难）
     * 而且用户面对 10 万条错误也修不过来 —— 这种文件本身就该重做，而不是修。
     * 所以设 10000 为界：够用，又有兜底。
     * 超过时把 truncated 标记置 true，界面上会显示"仅记录前 10000 条"。
     */
    private final int maxErrorRows;

    /** 已读取的总行数（含格式错误的行） */
    @Getter
    private long totalRows;

    /** 成功交给业务层处理的行数 */
    @Getter
    private long handledRows;

    /** 校验/转换失败的行数（**真实总数**，不受 maxErrorRows 限制） */
    @Getter
    private long errorRows;

    /** 错误明细是否被截断（errorRows &gt; errors.size()） */
    @Getter
    private boolean truncated;

    /** 结构化错误明细 */
    private final List<ImportErrorRow> errors = new ArrayList<>();

    /**
     * 表头名 → 列索引的映射，在 {@link #invokeHeadMap} 里填充。
     *
     * 【为什么需要它】
     * 有一个尴尬的场景：某一行"指标值"填了 "abc"，EasyExcel 转换 BigDecimal 失败，
     * 走的是 onException 分支 —— **这时候根本没有 FactImportRow 对象**，
     * 拿不到这一行的其他列写了什么。
     *
     * 但用户需要看到"第 1003 行：区域 430102、指标 1-1、值填了 abc"，
     * 只给一个"第 1003 行数值格式错误"太单薄了 —— 他要回去翻 Excel 才知道是哪一条。
     *
     * 解法：预先记下"区域编码"在第 0 列、"指标值"在第 4 列，
     * 出错时直接从原始单元格里把文本抠出来。这样即使转换失败，
     * 也能把整行的原貌展示给用户。
     */
    private final Map<String, Integer> headerIndex = new HashMap<>();

    /** 模板里的 5 个表头，和 FactImportRow 的 @ExcelProperty 必须一字不差 */
    private static final String H_REGION = "区域编码";
    private static final String H_INDICATOR = "指标编码";
    private static final String H_PERIOD = "周期编码";
    private static final String H_PERIOD_TYPE = "周期类型";
    private static final String H_VALUE = "指标值";

    /** 数据库列宽，写入前统一截断，避免"记录错误时又因为字段超长失败" */
    private static final int CODE_MAX = 64;
    private static final int TYPE_MAX = 32;
    private static final int MSG_MAX = 500;

    public FactImportListener(int batchSize, Consumer<List<FactImportRow>> batchHandler) {
        this(batchSize, batchHandler, 10000);
    }

    public FactImportListener(int batchSize, Consumer<List<FactImportRow>> batchHandler, int maxErrorRows) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于 0");
        }
        this.batchSize = batchSize;
        this.batchHandler = batchHandler;
        this.maxErrorRows = maxErrorRows <= 0 ? 10000 : maxErrorRows;
    }

    /**
     * 表头行回调（整个文件只调一次）。
     *
     * headMap 的 key 是列索引，value 是表头文字。
     * 我们把它反过来存成 "表头文字 → 列索引"，后面 {@link #rawText} 要用。
     */
    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        headMap.forEach((idx, name) -> {
            if (name != null) {
                headerIndex.put(name.trim(), idx);
            }
        });
        log.debug("[Excel解析] 表头映射={}", headerIndex);
    }

    /**
     * 每读到一行数据回调一次。
     *
     * ⚠️ 注意：表头行**不会**进来。EasyExcel 默认把第一行当表头，从第二行开始回调。
     *    所以 context 给的行号是 0-based，必须 +1 才能和用户在 Excel 里看到的一致。
     */
    @Override
    public void invoke(FactImportRow row, AnalysisContext context) {
        totalRows++;
        int rowNum = context.readRowHolder().getRowIndex() + 1;

        // ---- 逐行业务校验 ----
        ImportErrorType error = validate(row);
        if (error != null) {
            collect(rowNum, error, reasonOf(error, row, rowNum), row, context);
            return; // 这一行跳过，继续读下一行
        }

        // 顺手把行号记在对象上：导出"全部数据"时要靠它把错误原因贴到对应行
        row.setRowNum(rowNum);

        buffer.add(row);
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    /**
     * 整个文件读完后回调一次 —— 把最后不满一批的"尾巴"刷出去。
     *
     * ⚠️ 这个方法的调用**绝对不能漏**。假设文件有 10,800 行、batchSize = 500：
     *    前 10,500 行在 invoke 里被刷出去 21 次，最后剩下 300 行躺在 buffer 里。
     *    如果不在 doAfterAllAnalysed 里 flush，这 300 行就被**静默丢弃**了
     *    —— 导入"成功了"，但少了 300 行数据，这是非常典型的丢数据 bug。
     */
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush();
        log.info("[Excel解析] 完成。总行数={} 已处理={} 错误={} 错误明细={}{}",
                totalRows, handledRows, errorRows, errors.size(),
                truncated ? "（已截断）" : "");
    }

    /**
     * 行数据转换出错时回调（比如"指标值"那一列填了 "abc"，转不了 BigDecimal）。
     *
     * 【默认行为 vs 我们重写的行为】
     *   EasyExcel 的默认实现是：把异常原样抛出去 → 整个导入立即中断。
     *   我们改成"记下第几行错了，继续读下一行"，这样用户能一次看到所有错误，
     *   而不是改一行、传一次、再发现一个错 —— 那种体验会让人崩溃。
     *
     * 【★ 这里有一个关键的分寸：什么异常能"跳过"，什么必须"中断"】
     *   能跳过：ExcelDataConvertException —— 只是这一行的单元格格式不对，
     *           跟别的行没关系，记下来继续读完全合理。
     *   必须中断：其他所有异常，尤其是 batchHandler 里抛出来的数据库异常
     *           （比如唯一索引冲突、连接池耗尽）。那种情况再继续读下去，
     *           后面每一行都会失败，只是白白把文件读完、刷一堆日志。
     *
     *   所以这里**只吞 ExcelDataConvertException，其余原样抛出**。
     *   早期版本我把所有异常都吞了，结果数据库挂了的时候导入"成功结束"、
     *   报告 0 行导入 —— 这才是最危险的：静默失败比报错可怕得多。
     *
     * 【Day 11 改进：转换失败时也要拿到整行原貌】
     * 这条路径上 EasyExcel 没构造出 FactImportRow 对象，
     * 所以用 {@link #rawText} 从原始单元格里抠值（靠 headerIndex 定位列）。
     */
    @Override
    public void onException(Exception exception, AnalysisContext context) throws Exception {
        if (exception instanceof ExcelDataConvertException e) {
            // 这一行虽然转换失败了，但它确实是文件里的一行 —— totalRows 要算上，
            // 否则用户会看到"文件 2 行，只有 1 行出错"，剩下 1 行去哪了没人解释得清。
            // （invoke 和 onException 对同一行不会都触发：转换失败就不会进 invoke。）
            totalRows++;
            int rowNum = e.getRowIndex() + 1;

            // 【★ 这里踩过一次坑】取"用户到底填了什么"不能只靠 getCellData().getData()。
            // 因为转换失败的时候，EasyExcel 还没把值转成目标类型，
            // getData() 往往返回 null —— 于是在错误消息里看到的是「」，用户一脸懵。
            // 正确做法是回到原始单元格，按列索引取文本（cellMap 里存的是原文）。
            String badText = rawTextByIndex(context, e.getColumnIndex());
            if (badText == null) {
                Object raw = e.getCellData() == null ? null : e.getCellData().getData();
                badText = raw == null ? "" : String.valueOf(raw);
            }

            collect(rowNum, ImportErrorType.BAD_NUMBER,
                    String.format("第 %d 行的「%s」填了「%s」，无法识别为数字",
                            rowNum, headerNameOf(e.getColumnIndex()), truncate(badText, 40)),
                    null, context);
            // 不调用 context.interrupt()，让 EasyExcel 继续读下一行
            return;
        }
        // 数据库异常、IO 异常等：直接抛出去，中断整个导入
        throw exception;
    }

    /**
     * 把篮子里的行交出去，然后清空。
     *
     * ⚠️ 传出去的是**新 List 的拷贝**（new ArrayList<>(buffer)），不是 buffer 本身。
     *    如果直接传 buffer，紧接着的 clear() 会把业务层拿到的列表也清空
     *    （两边是同一个对象引用）—— 又是一类"看起来正常、数据却没了"的 bug。
     */
    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<FactImportRow> batch = new ArrayList<>(buffer);
        buffer.clear();
        // 这一步可能抛异常（比如数据库唯一索引冲突）。让它往上冒泡，
        // 由 EasyExcel 包成 ExcelAnalysisException，Service 层统一处理。
        batchHandler.accept(batch);
        handledRows += batch.size();
    }

    // ==========================================================================
    // 校验
    // ==========================================================================

    /**
     * 单行校验。返回 null 表示通过，否则返回错误类型。
     *
     * 【Day 11 的改动：返回值从 String（错误消息）变成 ImportErrorType（错误类型）】
     *
     * 这不是"为了用枚举而用枚举"。改造前，错误信息是一句话，无法聚合；
     * 改造后有了类型，才能回答"3000 行错误里，哪一类最多"这个问题。
     *
     * 具体消息仍然要生成（用户要看"实际是：月"），所以分成两步：
     *   validate() 只判断"是什么类型"
     *   reasonOf() 负责生成"给人看的话"
     * 判断和措辞分开，后面要改文案、要做国际化，都只动 reasonOf。
     */
    private ImportErrorType validate(FactImportRow row) {
        if (row == null) {
            return ImportErrorType.EMPTY_ROW;
        }
        // --- 必填校验：4 个编码类字段 + 指标值 ---
        // 合并成一个判断，是因为它们的错误类型相同（EMPTY_REQUIRED），
        // 具体是哪个字段为空，在 reasonOf 里再区分。
        if (isBlank(row.getRegionCode()) || isBlank(row.getIndicatorCode())
                || isBlank(row.getPeriodCode()) || isBlank(row.getPeriodType())) {
            return ImportErrorType.EMPTY_REQUIRED;
        }

        // 周期类型只允许 Y / Q / M —— 和统计接口的入参白名单保持同一套规则。
        // 这一类"格式约束"必须在入库前挡住，否则脏数据会一路流到统计报表里，
        // 到时候"大屏数字对不上"根本查不出是哪一条脏数据引起的。
        String type = row.getPeriodType().trim().toUpperCase();
        if (!"Y".equals(type) && !"Q".equals(type) && !"M".equals(type)) {
            return ImportErrorType.BAD_PERIOD_TYPE;
        }
        row.setPeriodType(type);

        if (row.getIndicatorValue() == null) {
            return ImportErrorType.EMPTY_REQUIRED;
        }
        if (row.getIndicatorValue().compareTo(BigDecimal.ZERO) < 0) {
            return ImportErrorType.NEGATIVE_VALUE;
        }
        return null;
    }

    /** 根据错误类型生成给人看的具体原因（这一步才去分辨"到底哪个字段为空"） */
    private String reasonOf(ImportErrorType type, FactImportRow row, int rowNum) {
        switch (type) {
            case EMPTY_ROW:
                return "第 " + rowNum + " 行整行为空";
            case EMPTY_REQUIRED: {
                List<String> missing = new ArrayList<>(5);
                if (row == null) {
                    return "第 " + rowNum + " 行：必填项为空";
                }
                if (isBlank(row.getRegionCode())) missing.add("区域编码");
                if (isBlank(row.getIndicatorCode())) missing.add("指标编码");
                if (isBlank(row.getPeriodCode())) missing.add("周期编码");
                if (isBlank(row.getPeriodType())) missing.add("周期类型");
                if (row.getIndicatorValue() == null) missing.add("指标值");
                return "第 " + rowNum + " 行：" + String.join("、", missing) + " 不能为空";
            }
            case BAD_PERIOD_TYPE:
                return "第 " + rowNum + " 行：周期类型只能是 Y / Q / M，实际填的是「"
                        + row.getPeriodType() + "」";
            case NEGATIVE_VALUE:
                return "第 " + rowNum + " 行：指标值不能为负数，实际填的是 " + row.getIndicatorValue();
            case OVER_LIMIT:
                return "第 " + rowNum + " 行：超出导入行数上限";
            default:
                return "第 " + rowNum + " 行：数据不合法";
        }
    }

    // ==========================================================================
    // 错误收集
    // ==========================================================================

    /**
     * 记录一条错误。
     *
     * @param rowNum  用户看到的 Excel 行号（1-based，含表头）
     * @param type    错误类型
     * @param msg     详细原因
     * @param row     转换成功的对象；转换失败（onException）时传 null
     * @param context 用于在 row 为 null 时从原始单元格取值
     */
    private void collect(int rowNum, ImportErrorType type, String msg,
                         FactImportRow row, AnalysisContext context) {
        errorRows++;
        log.warn("[Excel解析] {}", msg);

        // 超过上限：只计数、不记录明细。
        // ★ 注意 errorRows 一定要继续加 —— 用户需要知道"总共错了多少行"，
        //   这个数字不能因为"明细存不下"而变小，否则他会以为改完这 1 万行就好了。
        if (errors.size() >= maxErrorRows) {
            if (!truncated) {
                truncated = true;
                log.warn("[Excel解析] 错误明细已达上限 {} 条，后续只统计数量不再记录明细", maxErrorRows);
            }
            return;
        }

        ImportErrorRow e = new ImportErrorRow();
        e.setRowNum(rowNum);
        e.setErrorType(type.name());
        e.setErrorMsg(truncate(msg, MSG_MAX));

        if (row != null) {
            e.setRegionCode(truncate(row.getRegionCode(), CODE_MAX));
            e.setIndicatorCode(truncate(row.getIndicatorCode(), CODE_MAX));
            e.setPeriodCode(truncate(row.getPeriodCode(), CODE_MAX));
            e.setPeriodType(truncate(row.getPeriodType(), TYPE_MAX));
            e.setIndicatorValue(row.getIndicatorValue() == null ? null
                    : truncate(row.getIndicatorValue().toPlainString(), CODE_MAX));
        } else {
            // 转换失败的分支：从原始单元格取，保证用户能看到这一行原本写了什么
            e.setRegionCode(truncate(rawText(context, H_REGION), CODE_MAX));
            e.setIndicatorCode(truncate(rawText(context, H_INDICATOR), CODE_MAX));
            e.setPeriodCode(truncate(rawText(context, H_PERIOD), CODE_MAX));
            e.setPeriodType(truncate(rawText(context, H_PERIOD_TYPE), TYPE_MAX));
            e.setIndicatorValue(truncate(rawText(context, H_VALUE), CODE_MAX));
        }
        errors.add(e);
    }

    /**
     * 从当前行的原始单元格里按表头名取文本。
     *
     * 【为什么要判断这么多种类型】
     * 因为 CellData 是"还没被转成 Java 类型"的原始格子，它可能是：
     *   字符串格子 → getStringValue() 有值
     *   数字格子   → getNumberValue() 有值，getStringValue() 返回 null
     *   布尔格子   → getBooleanValue()
     *   公式格子   → getData() 返回计算结果
     * 只调 getStringValue() 的话，数字格子会得到 null ——
     * 于是错误报告里"指标值"那一列全是空的，用户不知道原来填的是多少。
     */
    private String rawText(AnalysisContext context, String header) {
        Integer idx = headerIndex.get(header);
        if (idx == null) {
            return null;
        }
        return rawTextByIndex(context, idx);
    }

    /** 按列索引取原始单元格文本（onException 路径拿不到表头时用） */
    private String rawTextByIndex(AnalysisContext context, int columnIndex) {
        if (context == null || context.readRowHolder() == null) {
            return null;
        }
        Map<Integer, Cell> cells = context.readRowHolder().getCellMap();
        if (cells == null) {
            return null;
        }
        Cell cell = cells.get(columnIndex);
        // getCellMap() 返回的是 Cell 接口（CellData 是它的实现）。
        // 读取阶段拿到的实际类型是 CellData，但不能直接当 CellData 用 ——
        // 写个 instanceof 判断，万一以后 EasyExcel 换了实现，这里不会 ClassCastException。
        if (!(cell instanceof CellData cd)) {
            return null;
        }
        if (cd.getStringValue() != null) {
            return cd.getStringValue();
        }
        if (cd.getNumberValue() != null) {
            return cd.getNumberValue().toPlainString();
        }
        if (cd.getBooleanValue() != null) {
            return String.valueOf(cd.getBooleanValue());
        }
        return cd.getData() == null ? null : String.valueOf(cd.getData());
    }

    /** 列索引 → 表头名，用于"「指标值」填了 abc"这种提示 */
    private String headerNameOf(int columnIndex) {
        for (Map.Entry<String, Integer> en : headerIndex.entrySet()) {
            if (en.getValue() != null && en.getValue() == columnIndex) {
                return en.getKey();
            }
        }
        return "第 " + (columnIndex + 1) + " 列";
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ==========================================================================
    // 对外暴露
    // ==========================================================================

    /**
     * 错误明细（结构化）。
     *
     * 【注意这里是 Collections.unmodifiableList 包了一层】
     * 调用方拿到之后如果直接 add/remove，会顺便改掉 Listener 内部的状态。
     * 包一层视图，让"想改"立刻抛异常，把 bug 暴露在开发阶段，
     * 而不是等到线上出现"错误数量对不上"时才发现。
     */
    public List<ImportErrorRow> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    /** 错误明细总数（落库时用来分批） */
    public int errorSize() {
        return errors.size();
    }

    /** 错误明细分批取出（落库用，避免一次性 1 万条塞进一条 SQL） */
    public List<ImportErrorRow> errorChunk(int from, int size) {
        int to = Math.min(from + size, errors.size());
        if (from >= to) {
            return Collections.emptyList();
        }
        return errors.subList(from, to);
    }

    /**
     * 错误摘要（一句话，写进 async_task.error_msg，任务列表里能直接看到）。
     *
     * 这里只放前 3 条，不是 5 条、也不是全部：
     *   · error_msg 只有 VARCHAR(1000)，放多了会被截断
     *   · 3 条足够让用户判断"是哪一类问题"
     *   · 要看全部，去下载错误报告 —— 那才是为"看全部"设计的东西
     * **同一份信息在不同位置有不同的合适粒度**，这是界面设计的基本功。
     */
    public String errorSummary() {
        if (errors.isEmpty()) {
            return "";
        }
        List<String> first = new ArrayList<>();
        for (int i = 0; i < Math.min(3, errors.size()); i++) {
            first.add(errors.get(i).getErrorMsg());
        }
        String joined = String.join("；", first);
        if (errorRows > first.size()) {
            // ★ 这里**只加省略号，不再重复"共 N 行"**。
            //   调用方（BizException 的消息）本来就会写"共 N 行数据不合法"，
            //   如果这里也写一遍，用户会看到
            //     "共 2989 行数据不合法……（共 2989 行有误，仅列出前 3 条）"
            //   同一句话说了两遍，读起来像程序在结巴。
            //   信息的层次要分清：**数量由上层说一次，这里只负责"举例"。**
            joined += "……";
        }
        return joined;
    }
}

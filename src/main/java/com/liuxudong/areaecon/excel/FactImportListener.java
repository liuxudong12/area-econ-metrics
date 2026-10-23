package com.liuxudong.areaecon.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.exception.ExcelDataConvertException;
import com.alibaba.excel.read.listener.ReadListener;
import com.liuxudong.areaecon.controller.vo.FactImportRow;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
 */
@Slf4j
public class FactImportListener implements ReadListener<FactImportRow> {

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

    /** 错误信息最多收集这么多条，避免一个格式全错的文件产生几万条日志 */
    private static final int MAX_ERRORS = 5;

    /** 已读取的总行数（含格式错误的行） */
    @Getter
    private long totalRows;

    /** 成功交给业务层处理的行数 */
    @Getter
    private long handledRows;

    /** 校验/转换失败的行数 */
    @Getter
    private long errorRows;

    /** 具体的错误描述（前 MAX_ERRORS 条） */
    private final List<String> errors = new ArrayList<>();

    public FactImportListener(int batchSize, Consumer<List<FactImportRow>> batchHandler) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于 0");
        }
        this.batchSize = batchSize;
        this.batchHandler = batchHandler;
    }

    /**
     * 每读到一行数据回调一次。
     *
     * ⚠️ 注意：表头行**不会**进来。EasyExcel 默认把第一行当表头，从第二行开始回调。
     *    所以这里的行号是"数据行号"，跟 Excel 里看到的行号差 1。
     */
    @Override
    public void invoke(FactImportRow row, AnalysisContext context) {
        totalRows++;

        // ---- 逐行业务校验 ----
        String error = validate(row);
        if (error != null) {
            errorRows++;
            addError(context, error);
            return; // 这一行跳过，继续读下一行
        }

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
        log.info("[Excel解析] 完成。总行数={} 已处理={} 错误={}", totalRows, handledRows, errorRows);
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
     * 【★ 另一个必须知道的取舍】
     *   收集完错误后，到底要不要"部分成功"？
     *   我们的选择是：**只要有一行错，整批不导入，返回所有错误行号让用户改。**
     *   理由：数据导入是"要么全对，要么全不动"。如果允许部分成功，
     *   再传一次就会出现重复数据 —— 而唯一索引 uk_fact 会拦住，
     *   于是用户看到一半成功一半报错，根本不知道到底导进去哪些。
     *   宁可全失败，也不要留在"半吊子"状态。
     */
    @Override
    public void onException(Exception exception, AnalysisContext context) throws Exception {
        if (exception instanceof ExcelDataConvertException e) {
            // 这一行虽然转换失败了，但它确实是文件里的一行 —— totalRows 要算上，
            // 否则用户会看到"文件 2 行，只有 1 行出错"，剩下 1 行去哪了没人解释得清。
            // （invoke 和 onException 对同一行不会都触发：转换失败就不会进 invoke。）
            totalRows++;
            errorRows++;
            addError(context, String.format("第 %d 行第 %d 列格式错误：'%s' 无法转换为目标类型",
                    e.getRowIndex() + 1, e.getColumnIndex() + 1, e.getCellData().getStringValue()));
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

    /** 单行校验。返回 null 表示通过，否则返回错误原因。 */
    private String validate(FactImportRow row) {
        if (row == null) {
            return "整行为空";
        }
        if (isBlank(row.getRegionCode())) {
            return "区域编码不能为空";
        }
        if (isBlank(row.getIndicatorCode())) {
            return "指标编码不能为空";
        }
        if (isBlank(row.getPeriodCode())) {
            return "周期编码不能为空";
        }
        if (isBlank(row.getPeriodType())) {
            return "周期类型不能为空";
        }
        // 周期类型只允许 Y / Q / M —— 和统计接口的入参白名单保持同一套规则。
        // 这一类"格式约束"必须在入库前挡住，否则脏数据会一路流到统计报表里，
        // 到时候"大屏数字对不上"根本查不出是哪一条脏数据引起的。
        String type = row.getPeriodType().trim().toUpperCase();
        if (!"Y".equals(type) && !"Q".equals(type) && !"M".equals(type)) {
            return "周期类型只能是 Y / Q / M，实际是：" + row.getPeriodType();
        }
        row.setPeriodType(type);
        if (row.getIndicatorValue() == null) {
            return "指标值不能为空";
        }
        if (row.getIndicatorValue().compareTo(BigDecimal.ZERO) < 0) {
            return "指标值不能为负数：" + row.getIndicatorValue();
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void addError(AnalysisContext context, String msg) {
        // 行号统一转成"用户看到的 Excel 行号"（从 1 开始）
        String withRow = msg.startsWith("第 ") ? msg
                : "第 " + (context.readRowHolder().getRowIndex() + 1) + " 行：" + msg;
        log.warn("[Excel解析] {}", withRow);
        if (errors.size() < MAX_ERRORS) {
            errors.add(withRow);
        }
    }

    /** 错误明细（最多 MAX_ERRORS 条） */
    public List<String> getErrors() {
        return errors;
    }

    /** 错误条数超过展示上限时，用这个拼一句提示 */
    public String errorSummary() {
        if (errors.isEmpty()) {
            return "";
        }
        String joined = String.join("；", errors);
        if (errorRows > errors.size()) {
            joined += "……（共 " + errorRows + " 行有误，仅列出前 " + errors.size() + " 条）";
        }
        return joined;
    }
}

package com.liuxudong.areaecon.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.controller.vo.ErrorSummaryVO;
import com.liuxudong.areaecon.controller.vo.ImportErrorReportRow;
import com.liuxudong.areaecon.entity.AsyncTask;
import com.liuxudong.areaecon.entity.ImportErrorRow;
import com.liuxudong.areaecon.entity.ImportErrorType;
import com.liuxudong.areaecon.excel.ImportExcelExporter;
import com.liuxudong.areaecon.mapper.AsyncTaskMapper;
import com.liuxudong.areaecon.mapper.ImportErrorRowMapper;
import com.liuxudong.areaecon.service.ImportReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ImportReportServiceImpl implements ImportReportService {

    /** 从数据库往外查，一页多少条。1000 条大约是 200KB 对象，内存无压力 */
    private static final int EXPORT_PAGE = 1000;

    /** 往 Excel 里写，攒多少行刷一次 */
    private static final int FLUSH_BATCH = 1000;

    /** 模板里 5 个表头。和 FactImportRow 的 @ExcelProperty 必须一致 */
    private static final String H_REGION = "区域编码";
    private static final String H_INDICATOR = "指标编码";
    private static final String H_PERIOD = "周期编码";
    private static final String H_PERIOD_TYPE = "周期类型";
    private static final String H_VALUE = "指标值";

    private final AsyncTaskMapper asyncTaskMapper;
    private final ImportErrorRowMapper importErrorRowMapper;

    @Value("${stat.import.max-error-rows:10000}")
    private int maxErrorRows;

    public ImportReportServiceImpl(AsyncTaskMapper asyncTaskMapper,
                                   ImportErrorRowMapper importErrorRowMapper) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.importErrorRowMapper = importErrorRowMapper;
    }

    // ==========================================================================
    // 模板
    // ==========================================================================

    @Override
    public void writeTemplate(OutputStream out) {
        // 模板是纯静态的，没有任何数据库依赖 —— 但它要生成 xlsx，
        // 所以还是走 Service 而不是直接放在 Controller：Controller 不该关心 Excel 怎么写。
        ImportExcelExporter.writeTemplate(out);
        log.info("[导入-模板] 已生成模板");
    }

    // ==========================================================================
    // 错误统计
    // ==========================================================================

    @Override
    public ErrorSummaryVO getErrorSummary(Long taskId) {
        AsyncTask task = requireTask(taskId);

        long recorded = importErrorRowMapper.countByTaskId(taskId);

        // 【主口径是 async_task.error_rows，不是 count(明细)】
        // 因为明细有上限。一个 5 万行全错的文件，明细只存 1 万条，
        // 但用户必须知道"错了 5 万行"——否则他改完这 1 万行重传还是失败。
        long errorRows = task.getErrorRows() == null ? 0 : task.getErrorRows();
        // 兜底：Day 10 及更早的任务没有 error_rows 这一列（值为 0），
        // 但明细可能存在。这时用明细条数，总比显示 0 强。
        if (errorRows < recorded) {
            errorRows = recorded;
        }

        ErrorSummaryVO vo = new ErrorSummaryVO();
        vo.setTaskId(taskId);
        vo.setFileName(task.getFileName());
        vo.setStatus(task.getStatus());
        vo.setTotalRows(task.getTotalRows());
        vo.setErrorRows(errorRows);
        vo.setRecordedRows(recorded);
        // 明细条数达到上限 = 可能被截断。
        // 这是个**保守判断**：正好 10000 条错误时也会标 true，但那不影响使用
        // （用户只会看到多一句"可能还有更多"）。
        vo.setTruncated(recorded >= maxErrorRows && errorRows > recorded);

        List<ImportErrorVOBuilder> builders = new ArrayList<>();
        for (Map<String, Object> row : importErrorRowMapper.countByType(taskId)) {
            String type = row.get("errorType") == null ? null : String.valueOf(row.get("errorType"));
            long cnt = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).longValue();
            builders.add(new ImportErrorVOBuilder(type, cnt));
        }

        // 【百分比的分母用哪个？—— 这是个容易想当然的地方】
        // 直觉是用 errorRows（真实总数 2989），但如果明细被截断成 100 条，
        // 各类数量加起来只有 100，百分比总和只有 3.3% —— 用户看到会以为数据坏了。
        //
        // 正确做法：分母用 **recordedRows**（已记录明细条数），
        // 这样各类占比之和正好 100%，语义是"**在已记录的 N 条明细里，各类占多少**"。
        // 截断时前端要额外提示一句"基于前 100 条明细统计"，
        // 用户才知道这不是全量的分布。
        long total = recorded <= 0 ? 1 : recorded;
        List<ErrorSummaryVO.ErrorTypeStat> stats = new ArrayList<>(builders.size());
        for (ImportErrorVOBuilder b : builders) {
            ErrorSummaryVO.ErrorTypeStat s = new ErrorSummaryVO.ErrorTypeStat();
            s.setErrorType(b.type);
            s.setErrorTypeLabel(ImportErrorType.labelOf(b.type));
            s.setCount(b.count);
            // 保留两位小数。用 double 而不是 BigDecimal 是因为这只是展示用的比例，
            // 不参与金额计算、不参与累加校验。**不是所有数字都要 BigDecimal。**
            s.setPercent(Math.round(b.count * 10000.0 / total) / 100.0);
            stats.add(s);
        }
        vo.setByType(stats);
        return vo;
    }

    /** 只是为了让上面那段代码别写得太挤，一个内部的小载体 */
    private record ImportErrorVOBuilder(String type, long count) {
    }

    // ==========================================================================
    // 错误报告
    // ==========================================================================

    @Override
    public void writeErrorReport(Long taskId, String scope, OutputStream out) {
        AsyncTask task = requireTask(taskId);
        String s = scope == null ? "error" : scope.trim().toLowerCase();
        switch (s) {
            case "all" -> writeAllRowsReport(task, out);
            case "error" -> writeErrorRowsReport(taskId, out);
            default -> throw new BizException("scope 只能是 error（仅错误行）或 all（全部数据）");
        }
    }

    /**
     * scope = error：只导出错误行。
     *
     * 【实现要点：游标分页 + 边查边写】
     * 不是"先把 1 万条全查出来再写"，而是每次查 1000 条、写一次、再查下一千条。
     * 内存里永远只有 1000 条对象，跟错误行总数无关。
     *
     * 这在数据量小时看不出区别（1 万条全查也就几十 MB），
     * 但一旦上限调到 10 万，一次全查就是 OOM。
     * **写代码时的"量级意识"：先想清楚它最大可能有多大，再决定怎么取。**
     */
    private void writeErrorRowsReport(Long taskId, OutputStream out) {
        long start = System.currentTimeMillis();
        ExcelWriter writer = ImportExcelExporter.createReportWriter(out);
        long written = 0;
        try {
            WriteSheet sheet = ImportExcelExporter.reportSheet();
            long lastId = 0L;
            while (true) {
                List<ImportErrorRow> page =
                        importErrorRowMapper.selectPageByCursor(taskId, lastId, EXPORT_PAGE);
                if (page.isEmpty()) {
                    break;
                }
                List<ImportErrorReportRow> outRows = page.stream().map(this::toReportRow).toList();
                ImportExcelExporter.writeReportBatch(writer, sheet, outRows);
                written += outRows.size();
                lastId = page.get(page.size() - 1).getId();
                if (page.size() < EXPORT_PAGE) {
                    break;
                }
            }
        } finally {
            // finish() 必须执行，否则生成的是损坏的 xlsx（zip 结构不完整）。
            // 即使用户中途断开连接、这里抛了异常，也要走到 finish —— 虽然写出去的
            // 数据也没人接了，但不 finish 会让 POI 的临时资源泄漏。
            writer.finish();
        }
        log.info("[导入-错误报告] scope=error taskId={} 导出行数={} 耗时={}ms",
                taskId, written, System.currentTimeMillis() - start);
    }

    /**
     * scope = all：导出全部数据 + 错误原因列。
     *
     * ==========================================================================
     * 【为什么这个 scope 才是"改完重传"能成立的关键】
     * ==========================================================================
     * 假设原文件 10000 行，其中 3000 行有错，导入时**一行都没写进去**（全部回滚）。
     *
     * 如果只给用户 3000 行错误明细：
     *   他改完这 3000 行、重传 —— 数据库里只有这 3000 行，
     *   另外 7000 行**永远不会被导入**，而用户以为"改完就好了"。这是最坑的。
     *
     * 给全量 10000 行 + 错误原因列：
     *   他在同一个文件里改掉标红的那 3000 行，整体重传 —— 一次成功。
     *   错误原因列留空的行就是没问题的行，他不用管。
     *
     * ==========================================================================
     * 【为什么重新读原文件，而不是从错误表里拼】
     * ==========================================================================
     * 因为错误表只存了错误行。正确行的数据只在原文件里。
     * 所以必须重新读一遍上传时落盘的那个文件（async_task.file_path）。
     *
     * 【为什么用 Map&lt;Integer,String&gt; 读，而不是 FactImportRow】
     * 因为 FactImportRow 会做类型转换：指标值那一列填了 "abc" 就会抛
     * ExcelDataConvertException，而我们要导出**恰恰是这些行**。
     * 用 Map 模式读：每一格都是字符串，不做任何转换，**永远不会失败**。
     * 这正好是我们需要的 —— 我们要的就是"用户当初填的原文"。
     */
    private void writeAllRowsReport(AsyncTask task, OutputStream out) {
        Long taskId = task.getId();
        long start = System.currentTimeMillis();

        // ---------- ① 先把错误行按行号装进 Map ----------
        // 行号 → 错误。最多 1 万条，Map 完全放得下。
        Map<Integer, ImportErrorRow> errMap = new HashMap<>();
        long lastId = 0L;
        while (true) {
            List<ImportErrorRow> page =
                    importErrorRowMapper.selectPageByCursor(taskId, lastId, EXPORT_PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (ImportErrorRow e : page) {
                if (e.getRowNum() != null) {
                    errMap.put(e.getRowNum(), e);
                }
            }
            lastId = page.get(page.size() - 1).getId();
            if (page.size() < EXPORT_PAGE) {
                break;
            }
        }

        Path file = Paths.get(task.getFilePath() == null ? "" : task.getFilePath());
        if (!Files.exists(file)) {
            throw new BizException("原始上传文件已不存在（" + file + "），无法导出全部数据。"
                    + "请改用 scope=error 下载错误明细。");
        }

        ExcelWriter writer = ImportExcelExporter.createReportWriter(out);
        WriteSheet sheet = ImportExcelExporter.reportSheet("全部数据");
        List<ImportErrorReportRow> buffer = new ArrayList<>(FLUSH_BATCH);
        Map<String, Integer> headerIndex = new HashMap<>();
        long[] written = {0};

        try (InputStream in = Files.newInputStream(file)) {
            EasyExcel.read(in)
                    .registerReadListener(new AnalysisEventListener<Map<Integer, String>>() {

                        @Override
                        public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                            // 记下"表头名 → 列索引"。用表头名定位而不是写死 0/1/2/3/4，
                            // 是因为用户可能在模板里插了列 —— 那样写死索引就会整体错位。
                            headMap.forEach((idx, name) -> {
                                if (name != null) {
                                    headerIndex.put(name.trim(), idx);
                                }
                            });
                        }

                        @Override
                        public void invoke(Map<Integer, String> data, AnalysisContext context) {
                            int rowNum = context.readRowHolder().getRowIndex() + 1;
                            ImportErrorReportRow r = new ImportErrorReportRow();
                            r.setRowNum(rowNum);
                            r.setRegionCode(cellText(data, headerIndex, H_REGION));
                            r.setIndicatorCode(cellText(data, headerIndex, H_INDICATOR));
                            r.setPeriodCode(cellText(data, headerIndex, H_PERIOD));
                            r.setPeriodType(cellText(data, headerIndex, H_PERIOD_TYPE));
                            r.setIndicatorValue(cellText(data, headerIndex, H_VALUE));

                            ImportErrorRow err = errMap.get(rowNum);
                            if (err != null) {
                                r.setErrorTypeLabel(ImportErrorType.labelOf(err.getErrorType()));
                                r.setErrorMsg(err.getErrorMsg());
                            }
                            // 正确行：错误类型和原因都留空。
                            // ★ 不要写"正常"或"OK" —— 用户可能按这一列筛选不等于空的行，
                            //   留空最干净。**"没有问题"在数据里应该表现为"没有值"，不是一个特殊值。**

                            buffer.add(r);
                            if (buffer.size() >= FLUSH_BATCH) {
                                flushBuffer(writer, sheet, buffer, written);
                            }
                        }

                        @Override
                        public void doAfterAllAnalysed(AnalysisContext context) {
                            flushBuffer(writer, sheet, buffer, written);
                        }
                    })
                    .sheet()
                    .doRead();
        } catch (IOException e) {
            throw new BizException("读取原始文件失败：" + e.getMessage(), e);
        } finally {
            writer.finish();
        }

        log.info("[导入-错误报告] scope=all taskId={} 导出行数={} 其中错误={} 耗时={}ms",
                taskId, written[0], errMap.size(), System.currentTimeMillis() - start);
    }

    /** 攒够一批写一次，然后清空 */
    private void flushBuffer(ExcelWriter writer, WriteSheet sheet,
                             List<ImportErrorReportRow> buffer, long[] written) {
        if (buffer.isEmpty()) {
            return;
        }
        // 拷一份再写：EasyExcel 的 write 内部可能会持有这个 List，
        // 直接 clear 原来的可能写到一半数据就没了。
        List<ImportErrorReportRow> copy = new ArrayList<>(buffer);
        ImportExcelExporter.writeReportBatch(writer, sheet, copy);
        written[0] += copy.size();
        buffer.clear();
    }

    /**
     * 从 Map 模式的一行里按表头名取文本。
     *
     * Map 模式的 key 是列索引，单元格为空时那一项**可能根本不存在**（返回 null 而不是空串）——
     * 所以不能直接 get 然后 trim，必须判空。
     */
    private static String cellText(Map<Integer, String> data, Map<String, Integer> headerIndex, String header) {
        Integer idx = headerIndex.get(header);
        if (idx == null) {
            return null;
        }
        return data.get(idx);
    }

    /** 数据库行 → 导出行 */
    private ImportErrorReportRow toReportRow(ImportErrorRow e) {
        ImportErrorReportRow r = new ImportErrorReportRow();
        r.setRowNum(e.getRowNum());
        // 数据库里存的是枚举 code（BAD_PERIOD_TYPE），导出给用户的是中文（周期类型非法）。
        // 转换放在这一层，而不是"存库时就存中文"—— 因为文案会改，数据不该跟着改。
        r.setErrorTypeLabel(ImportErrorType.labelOf(e.getErrorType()));
        r.setErrorMsg(e.getErrorMsg());
        r.setRegionCode(e.getRegionCode());
        r.setIndicatorCode(e.getIndicatorCode());
        r.setPeriodCode(e.getPeriodCode());
        r.setPeriodType(e.getPeriodType());
        r.setIndicatorValue(e.getIndicatorValue());
        return r;
    }

    private AsyncTask requireTask(Long taskId) {
        if (taskId == null) {
            throw new BizException("taskId 不能为空");
        }
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("任务不存在：" + taskId);
        }
        return task;
    }
}

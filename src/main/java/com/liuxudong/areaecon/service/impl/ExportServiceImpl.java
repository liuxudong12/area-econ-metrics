package com.liuxudong.areaecon.service.impl;

import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.controller.vo.CategoryStatExportRow;
import com.liuxudong.areaecon.controller.vo.FactExportQuery;
import com.liuxudong.areaecon.controller.vo.FactExportRow;
import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionRankVO;
import com.liuxudong.areaecon.controller.vo.RegionYoyVO;
import com.liuxudong.areaecon.controller.vo.StatExportQuery;
import com.liuxudong.areaecon.controller.vo.TopStatExportRow;
import com.liuxudong.areaecon.controller.vo.TrendStatExportRow;
import com.liuxudong.areaecon.controller.vo.YoyStatExportRow;
import com.liuxudong.areaecon.entity.ExportLog;
import com.liuxudong.areaecon.entity.ExportType;
import com.liuxudong.areaecon.entity.FactIndicatorValue;
import com.liuxudong.areaecon.entity.Indicator;
import com.liuxudong.areaecon.entity.Period;
import com.liuxudong.areaecon.entity.Region;
import com.liuxudong.areaecon.excel.DataExportExporter;
import com.liuxudong.areaecon.mapper.ExportLogMapper;
import com.liuxudong.areaecon.mapper.FactIndicatorValueMapper;
import com.liuxudong.areaecon.mapper.PeriodMapper;
import com.liuxudong.areaecon.service.ExportService;
import com.liuxudong.areaecon.service.IndicatorService;
import com.liuxudong.areaecon.service.RegionService;
import com.liuxudong.areaecon.service.StatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 导出服务实现。
 *
 * 这里是导出链路的完整落地：
 *   ① 游标分页（不是 LIMIT offset）—— 见 {@link #exportFact}
 *   ② 流式写出、内存恒定         —— 见 {@link #exportFact} 的 while 循环
 *   ③ 所有校验前置于 setHeader    —— 见 {@link #begin}
 *   ④ 并发限流保护数据库          —— 见 {@link #acquirePermit}
 *   ⑤ 旁路日志不影响主链路        —— 见 {@link #saveLog}
 */
@Slf4j
@Service
public class ExportServiceImpl implements ExportService {

    /** 文件名里的时间戳。固定格式，避免每次导出的文件名不一样导致排序混乱 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final FactIndicatorValueMapper factMapper;
    private final ExportLogMapper exportLogMapper;
    private final RegionService regionService;
    private final IndicatorService indicatorService;
    private final PeriodMapper periodMapper;
    private final StatService statService;

    /**
     * 并发导出许可。
     *
     * ==========================================================================
     * 【为什么导出必须限流 —— 今天最"生产"的一个设计】
     * ==========================================================================
     * 导出是只读操作，看起来"很安全"。但它和普通查询有一个致命区别：
     * **它会把结果集完整地扫一遍**。48 万行的顺序扫描，在数据库上就是
     * 几十万个页的读取 + 几十 MB 的 IO。
     *
     * 一个人在导没问题；三个人同时导，数据库的 buffer pool 会被这几张大表
     * 反复冲刷，导致大屏那些"本该 10ms 返回"的统计查询也变慢 ——
     * 这就是典型的「一个大客户把所有人拖慢」。
     *
     * 所以导出通道必须**独立限流**，宁可让第 3 个人看到"当前导出人数较多，请稍后再试"，
     * 也不能让在线功能陪葬。
     *
     * ==========================================================================
     * 【为什么用 Semaphore 而不是线程池】
     * ==========================================================================
     * 线程池解决的是"同时干几件事"，Semaphore 解决的是"同时允许几件事通过"。
     * 导出的执行线程由 Tomcat 提供（一个请求一个线程），我们不需要自己造线程，
     * 只需要在入口处限制"同时进入的请求数"。这正是 Semaphore 的语义。
     *
     * ⚠️ 这个是**单机限流**。多实例部署时每个实例各限各的，
     *    集群总并发 = 实例数 × max-concurrent。要全局限流就得用
     *    Redis 计数器（INCR + EXPIRE）或者网关层限流。
     *
     * 公平模式（fair=true）：先到的先拿许可。默认的非公平模式允许"插队"，
     * 高并发下可能出现某个请求一直抢不到（饿死）。
     */
    private final Semaphore permits;

    /** 并发上限，只用于拼错误提示（Semaphore 本身不告诉你"总数是多少"） */
    private final int maxConcurrent;

    /** 每页从库里取多少条。太小往返多，太大吃内存。2000 条约 400KB 对象 */
    @Value("${stat.export.page-size:2000}")
    private int pageSize;

    /** 每个 sheet 最多多少数据行 */
    @Value("${stat.export.rows-per-sheet:1000000}")
    private int rowsPerSheet;

    /** 单次导出允许的最大行数，超过直接拒绝（不是截断！） */
    @Value("${stat.export.max-rows:2000000}")
    private int maxRows;

    /** 拿许可最多等多久。0 = 不等，立刻拒绝 */
    @Value("${stat.export.acquire-timeout-ms:0}")
    private long acquireTimeoutMs;

    public ExportServiceImpl(FactIndicatorValueMapper factMapper,
                             ExportLogMapper exportLogMapper,
                             RegionService regionService,
                             IndicatorService indicatorService,
                             PeriodMapper periodMapper,
                             StatService statService,
                             @Value("${stat.export.max-concurrent:2}") int maxConcurrent) {
        this.factMapper = factMapper;
        this.exportLogMapper = exportLogMapper;
        this.regionService = regionService;
        this.indicatorService = indicatorService;
        this.periodMapper = periodMapper;
        this.statService = statService;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.permits = new Semaphore(this.maxConcurrent, true);
    }

    // ==========================================================================
    // 一、明细导出（今天的主力）
    // ==========================================================================

    @Override
    public void exportFact(FactExportQuery query, HttpServletResponse response) {
        ExportLog logRow = newLog(ExportType.FACT, describe(query));
        long start = System.currentTimeMillis();
        boolean acquired = false;
        // committed = 是否已经往响应里写过字节。写过了就不能再抛异常（见 catch）
        boolean committed = false;

        try {
            // ---------- ① 限流 ----------
            acquired = acquirePermit(ExportType.FACT, logRow);

            // ---------- ② 先数一遍：这就是"所有校验前置"的具体做法 ----------
            long total = factMapper.countForExport(query);
            if (total == 0) {
                throw new BizException("没有符合条件的数据，请检查筛选条件（区域编码前缀 / 指标编码 / 周期区间）");
            }
            if (total > maxRows) {
                throw new BizException("本次筛选出 " + total + " 行，超过单次导出上限 " + maxRows
                        + " 行。请缩小范围（比如按年份区间分批导出）。");
            }
            logRow.setTotalRows((int) total);

            // ---------- ③ 到这一步才开始碰 HTTP 响应 ----------
            String fileName = "指标值明细_" + LocalDateTime.now().format(TS) + ".xlsx";
            setExcelHeaders(response, fileName);
            logRow.setFileName(fileName);
            committed = true;

            // ---------- ④ 维表装进内存当字典 ----------
            // 区域 1350 行、指标 18 行 —— 一次查完放 Map，之后每行只用 O(1) 查表。
            // 为什么不在 SQL 里 JOIN？前面的实验已经证明过：JOIN 会让 48 万行**每一行**
            // 都去维表回表一次（48 万次随机 IO）；而放内存里就是 48 万次哈希查找。
            // 前提是**维表足够小且变化不频繁** —— 这个前提一定要说清楚。
            Map<String, String> regionNames = loadRegionNames();
            Map<String, Indicator> indicators = loadIndicators();

            // ---------- ⑤ 游标分页 + 流式写 ----------
            long before = usedHeapMb();
            long lastId = 0L;
            long written = 0L;
            try (OutputStream out = response.getOutputStream();
                 DataExportExporter.PagedWriter<FactExportRow> writer =
                         DataExportExporter.open(out, FactExportRow.class, factSpec())) {

                while (true) {
                    List<FactIndicatorValue> page =
                            factMapper.selectForExportByCursor(query, lastId, pageSize);
                    if (page.isEmpty()) {
                        break;
                    }
                    List<FactExportRow> rows = new ArrayList<>(page.size());
                    for (FactIndicatorValue f : page) {
                        rows.add(toFactRow(f, regionNames, indicators));
                    }
                    // 写进去之后，这一批对象立刻可以被 GC 回收 ——
                    // rows / page 都只是循环内的局部变量，下一轮就没人引用了。
                    writer.write(rows);

                    written += page.size();
                    lastId = page.get(page.size() - 1).getId();
                    // 不足一页 = 已经到最后一页，不用再多查一次"空的"。
                    // 这个判断能省掉最后一次无意义的查询（48 万行 ≈ 245 页，省 1 次不多，
                    // 但如果是 200 行的小表，就省掉了一半的查询次数）
                    if (page.size() < pageSize) {
                        break;
                    }
                }

                logRow.setTotalRows((int) written);
                logRow.setSheetCount(writer.getSheetCount());

                log.info("[导出-明细] 完成 行数={} sheet={} 耗时={}ms 堆占用 {}MB→{}MB",
                        written, writer.getSheetCount(), System.currentTimeMillis() - start,
                        before, usedHeapMb());
            }

            logRow.setStatus(ExportType.OK);

        } catch (BizException e) {
            // 业务异常 = 我们主动拒绝（限流 / 超上限 / 无数据）。这些都是**预期内**的，
            // 而且全部发生在写响应之前，所以可以安全地抛出去让全局异常处理器返回 JSON。
            logRow.setStatus(ExportType.REJECTED);
            logRow.setErrorMsg(e.getMessage());
            throw e;
        } catch (Exception e) {
            logRow.setStatus(ExportType.FAILED);
            logRow.setErrorMsg(truncate(msg(e), 500));
            if (committed) {
                // ============================================================
                // 【最难看但必须处理的分支：已经开始写字节了，然后失败了】
                // ============================================================
                // 此时 HTTP 状态码和响应头早就发出去了，无法改成 500，
                // 也不能再写一段 JSON 错误体 —— 那会变成"xlsx 文件里混进 JSON"，
                // 更别说 Spring 的异常处理器还会尝试往里写第二次，直接报
                // "getOutputStream() has already been called"。
                //
                // 唯一正确的做法：把原因记进日志（这是事后唯一的线索），
                // 然后**安静地结束响应**。用户会得到一个打不开的 xlsx。
                //
                // 真实项目里怎么减少这种局面？
                //   ① 前置校验尽量全（我们做了限流 + 计数）
                //   ② 导出行数上限设小一点
                //   ③ 关键报表用"先落临时文件、成功后再回传"，用磁盘换可靠性
                //   ④ 前端做文件完整性校验（比如返回前追加一个校验位）
                // 这个取舍在手册里专门有一节讨论。
                log.error("[导出-明细] 已在输出中途失败，客户端会拿到不完整文件", e);
                return;
            }
            // 能走到这里，说明是真正的系统异常（磁盘满、数据库连接断、POI 写失败）。
            // 包成 RuntimeException 抛出去，由 GlobalExceptionHandler 兜底成
            // "系统内部错误" + 500 状态码 —— 用户不该看到 SQL 或文件路径。
            //
            // 为什么用 IllegalStateException 而不是自定义异常：
            // 它不是"业务规则不满足"，而是"系统状态出了问题"，语义对得上，
            // 而且全局兜底处理器会把它当系统异常记 error 日志（带完整堆栈）。
            throw new IllegalStateException("导出失败：" + msg(e), e);
        } finally {
            logRow.setCostMs((int) (System.currentTimeMillis() - start));
            if (acquired) {
                // ★ 必须在 finally 里 release。少释放一次，许可就永久少一个，
                //   几次之后所有人都导不出来了 —— 而且现象是"越来越多人被拒绝"，
                //   极难联想到是信号量泄漏。
                permits.release();
            }
            // 旁路日志：失败不影响导出结果
            saveLog(logRow);
        }
    }

    // ==========================================================================
    // 二、统计结果导出（小结果集，一次性写完）
    // ==========================================================================

    @Override
    public void exportStat(StatExportQuery q, HttpServletResponse response) {
        String kind = q.getKind() == null ? "" : q.getKind().trim().toLowerCase();
        ExportLog logRow = newLog(typeOfStat(kind), describe(q));
        long start = System.currentTimeMillis();
        boolean acquired = false;
        boolean committed = false;

        try {
            acquired = acquirePermit(typeOfStat(kind), logRow);

            // ---------- 参数校验 + 取数（都在 setHeader 之前） ----------
            requireText(q.getCategory(), "category（指标分类）");
            List<String> codes = q.getPeriodCodes() == null ? List.of() : q.getPeriodCodes();

            switch (kind) {
                case "category" -> {
                    requirePeriodCodes(codes);
                    List<RegionMetricStatVO> data =
                            statService.categorySummary(q.getCategory(), q.getPeriodType(), codes);
                    List<CategoryStatExportRow> rows = new ArrayList<>(data.size());
                    for (RegionMetricStatVO v : data) {
                        CategoryStatExportRow r = new CategoryStatExportRow();
                        r.setRegionCode(v.getRegionCode());
                        r.setRegionName(v.getRegionName());
                        r.setTotalValue(v.getTotalValue());
                        rows.add(r);
                    }
                    logRow.setFileName("区域汇总_" + LocalDateTime.now().format(TS) + ".xlsx");
                    setExcelHeaders(response, logRow.getFileName());
                    committed = true;
                    writeSmall(response, CategoryStatExportRow.class, "区域汇总",
                            new int[]{0, 1}, new int[]{14, 20, 20}, rows, logRow);
                }
                case "trend" -> {
                    requirePeriodCodes(codes);
                    List<PeriodMetricStatVO> data =
                            statService.categoryTrend(q.getCategory(), q.getPeriodType(), codes);
                    List<TrendStatExportRow> rows = new ArrayList<>(data.size());
                    for (PeriodMetricStatVO v : data) {
                        TrendStatExportRow r = new TrendStatExportRow();
                        r.setPeriodCode(v.getPeriodCode());
                        r.setTotalValue(v.getTotalValue());
                        rows.add(r);
                    }
                    logRow.setFileName("周期趋势_" + LocalDateTime.now().format(TS) + ".xlsx");
                    setExcelHeaders(response, logRow.getFileName());
                    committed = true;
                    writeSmall(response, TrendStatExportRow.class, "周期趋势",
                            new int[]{0}, new int[]{14, 20}, rows, logRow);
                }
                case "top" -> {
                    requirePeriodCodes(codes);
                    Integer topN = q.getTopN() == null ? 10 : q.getTopN();
                    if (topN < 1 || topN > 100) {
                        throw new BizException("topN 必须在 1~100 之间，本次传入 " + topN);
                    }
                    List<RegionRankVO> data =
                            statService.topRegions(q.getCategory(), q.getPeriodType(), codes, topN);
                    List<TopStatExportRow> rows = new ArrayList<>(data.size());
                    for (RegionRankVO v : data) {
                        TopStatExportRow r = new TopStatExportRow();
                        r.setRankNo(v.getRankNo());
                        r.setRegionCode(v.getRegionCode());
                        r.setRegionName(v.getRegionName());
                        r.setTotalValue(v.getTotalValue());
                        // share 已经是"百分数"（12.34 表示 12.34%），直接用，别再乘 100
                        r.setPercent(v.getShare());
                        rows.add(r);
                    }
                    logRow.setFileName("区域排行TOP" + topN + "_" + LocalDateTime.now().format(TS) + ".xlsx");
                    setExcelHeaders(response, logRow.getFileName());
                    committed = true;
                    writeSmall(response, TopStatExportRow.class, "区域排行",
                            new int[]{1, 2}, new int[]{10, 14, 20, 20, 12}, rows, logRow);
                }
                case "yoy" -> {
                    requireText(q.getBasePeriod(), "basePeriod（基期）");
                    requireText(q.getCurrentPeriod(), "currentPeriod（当期）");
                    if (q.getBasePeriod().trim().equals(q.getCurrentPeriod().trim())) {
                        throw new BizException("基期和当期不能是同一个周期，否则增长率恒为 0");
                    }
                    List<RegionYoyVO> data = statService.regionYoy(q.getCategory(), q.getPeriodType(),
                            q.getBasePeriod().trim(), q.getCurrentPeriod().trim());
                    List<YoyStatExportRow> rows = new ArrayList<>(data.size());
                    for (RegionYoyVO v : data) {
                        YoyStatExportRow r = new YoyStatExportRow();
                        r.setRegionCode(v.getRegionCode());
                        r.setRegionName(v.getRegionName());
                        r.setBaseValue(v.getBaseValue());
                        r.setCurrentValue(v.getCurrentValue());
                        r.setGrowth(v.getGrowth());
                        r.setGrowthRate(v.getGrowthRate());
                        rows.add(r);
                    }
                    logRow.setFileName("区域同比_" + q.getBasePeriod().trim() + "_vs_"
                            + q.getCurrentPeriod().trim() + "_" + LocalDateTime.now().format(TS) + ".xlsx");
                    setExcelHeaders(response, logRow.getFileName());
                    committed = true;
                    writeSmall(response, YoyStatExportRow.class, "区域同比",
                            new int[]{0, 1}, new int[]{14, 20, 18, 18, 18, 14}, rows, logRow);
                }
                default -> throw new BizException(
                        "kind 只能是 category（区域汇总）/ trend（周期趋势）/ top（排行）/ yoy（同比），本次传入：" + kind);
            }

            logRow.setStatus(ExportType.OK);

        } catch (BizException e) {
            logRow.setStatus(ExportType.REJECTED);
            logRow.setErrorMsg(e.getMessage());
            throw e;
        } catch (Exception e) {
            logRow.setStatus(ExportType.FAILED);
            logRow.setErrorMsg(truncate(msg(e), 500));
            if (committed) {
                log.error("[导出-统计] 已在输出中途失败 kind={}", kind, e);
                return;
            }
            // 能走到这里，说明是真正的系统异常（磁盘满、数据库连接断、POI 写失败）。
            // 包成 RuntimeException 抛出去，由 GlobalExceptionHandler 兜底成
            // "系统内部错误" + 500 状态码 —— 用户不该看到 SQL 或文件路径。
            //
            // 为什么用 IllegalStateException 而不是自定义异常：
            // 它不是"业务规则不满足"，而是"系统状态出了问题"，语义对得上，
            // 而且全局兜底处理器会把它当系统异常记 error 日志（带完整堆栈）。
            throw new IllegalStateException("导出失败：" + msg(e), e);
        } finally {
            logRow.setCostMs((int) (System.currentTimeMillis() - start));
            if (acquired) {
                permits.release();
            }
            saveLog(logRow);
        }
    }

    // ==========================================================================
    // 三、维表导出（数据量最小，一次写完）
    // ==========================================================================

    @Override
    public void exportDict(String type, HttpServletResponse response) {
        String t = type == null ? "" : type.trim().toLowerCase();
        ExportLog logRow = newLog("DICT_" + t.toUpperCase(), "type=" + t);
        long start = System.currentTimeMillis();
        boolean acquired = false;
        boolean committed = false;

        try {
            acquired = acquirePermit(logRow.getExportType(), logRow);

            // 维表导出用**动态表头**（List<List<String>>），不建 VO。
            // 理由：这三个导出的"列"就是表结构本身，是一次性的展示需求，
            // 建三个只有字段没有行为的 VO 类属于纯负担。
            // 判断标准：这份数据会不会在别的地方被复用？不会 → 就用 List。
            List<List<String>> head;
            List<List<Object>> rows;
            String sheetName;
            String fileName;
            int[] textColumns;
            int[] widths;

            switch (t) {
                case "region" -> {
                    List<Region> list = regionService.list();
                    head = head("区域编码", "区域名称", "上级编码", "层级", "排序");
                    rows = new ArrayList<>(list.size());
                    for (Region r : list) {
                        rows.add(row(r.getRegionCode(), r.getRegionName(), r.getParentCode(),
                                r.getRegionLevel(), r.getSort()));
                    }
                    sheetName = "区域";
                    fileName = "区域维表_" + LocalDateTime.now().format(TS) + ".xlsx";
                    textColumns = new int[]{0, 1, 2};
                    widths = new int[]{14, 20, 14, 8, 8};
                    logRow.setTotalRows(list.size());
                }
                case "indicator" -> {
                    List<Indicator> list = indicatorService.list();
                    head = head("指标编码", "指标名称", "上级编码", "分类", "单位", "值类型", "排序");
                    rows = new ArrayList<>(list.size());
                    for (Indicator i : list) {
                        rows.add(row(i.getIndicatorCode(), i.getIndicatorName(), i.getParentCode(),
                                i.getCategory(), i.getUnit(), valueTypeLabel(i.getValueType()), i.getSort()));
                    }
                    sheetName = "指标";
                    fileName = "指标维表_" + LocalDateTime.now().format(TS) + ".xlsx";
                    textColumns = new int[]{0, 1, 2, 3, 4};
                    widths = new int[]{14, 28, 14, 14, 10, 10, 8};
                    logRow.setTotalRows(list.size());
                }
                case "period" -> {
                    List<Period> list = periodMapper.selectList(null);
                    head = head("周期编码", "周期类型", "年份", "年内序号", "开始日期", "结束日期");
                    rows = new ArrayList<>(list.size());
                    for (Period p : list) {
                        // ★ 日期转成字符串再写。
                        //   如果直接把 LocalDate 交给 EasyExcel，它写的是"日期数值 + 日期格式"，
                        //   而我们又把整列设成了文本格式 → 显示出来会是一串数字（46023 之类）。
                        //   格式化和格式设置打架时，**最省心的做法是自己先转成字符串**，
                        //   让单元格里从头到尾就只有一种类型。
                        rows.add(row(p.getPeriodCode(), p.getPeriodType(), p.getPeriodYear(),
                                p.getPeriodIndex(),
                                p.getStartDate() == null ? "" : p.getStartDate().toString(),
                                p.getEndDate() == null ? "" : p.getEndDate().toString()));
                    }
                    sheetName = "周期";
                    fileName = "周期维表_" + LocalDateTime.now().format(TS) + ".xlsx";
                    textColumns = new int[]{0, 1, 4, 5};
                    widths = new int[]{14, 12, 10, 12, 14, 14};
                    logRow.setTotalRows(list.size());
                }
                default -> throw new BizException(
                        "type 只能是 region（区域）/ indicator（指标）/ period（周期），本次传入：" + type);
            }

            if (rows.isEmpty()) {
                throw new BizException("维表里没有数据，无需导出");
            }

            String file = fileName;
            setExcelHeaders(response, file);
            logRow.setFileName(file);
            committed = true;

            try (OutputStream out = response.getOutputStream()) {
                DataExportExporter.writeSimple(out, head, rows,
                        new DataExportExporter.SheetSpec(sheetName, rowsPerSheet, textColumns, widths));
            }
            logRow.setSheetCount(1);
            logRow.setStatus(ExportType.OK);

        } catch (BizException e) {
            logRow.setStatus(ExportType.REJECTED);
            logRow.setErrorMsg(e.getMessage());
            throw e;
        } catch (Exception e) {
            logRow.setStatus(ExportType.FAILED);
            logRow.setErrorMsg(truncate(msg(e), 500));
            if (committed) {
                log.error("[导出-维表] 已在输出中途失败 type={}", t, e);
                return;
            }
            // 能走到这里，说明是真正的系统异常（磁盘满、数据库连接断、POI 写失败）。
            // 包成 RuntimeException 抛出去，由 GlobalExceptionHandler 兜底成
            // "系统内部错误" + 500 状态码 —— 用户不该看到 SQL 或文件路径。
            //
            // 为什么用 IllegalStateException 而不是自定义异常：
            // 它不是"业务规则不满足"，而是"系统状态出了问题"，语义对得上，
            // 而且全局兜底处理器会把它当系统异常记 error 日志（带完整堆栈）。
            throw new IllegalStateException("导出失败：" + msg(e), e);
        } finally {
            logRow.setCostMs((int) (System.currentTimeMillis() - start));
            if (acquired) {
                permits.release();
            }
            saveLog(logRow);
        }
    }

    // ==========================================================================
    // 下面是内部工具方法
    // ==========================================================================

    /**
     * 拿并发许可。拿不到就抛业务异常（用户看到的是"当前导出人数较多"）。
     *
     * 【为什么默认"不等"（timeout=0）】
     * 因为导出是一个耗时几秒到几十秒的操作。让它排队等，用户在前端就是一个转圈的页面，
     * 等到超时（浏览器/网关通常是 30~60 秒），可能页面都关了 —— 白占一个 Tomcat 线程。
     * 直接拒绝 + 明确的提示，让用户 3 秒后重试，体验反而更好。
     *
     * 这就是分布式系统里常说的「快速失败（fail fast）」：
     * **在知道做不成的时候立刻说做不成，比让对方一直等更有价值。**
     */
    private boolean acquirePermit(String type, ExportLog logRow) {
        try {
            boolean ok;
            if (acquireTimeoutMs <= 0) {
                ok = permits.tryAcquire();
            } else {
                ok = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            }
            if (!ok) {
                throw new BizException("当前导出任务较多（并发上限 " + maxConcurrent + " 个），"
                        + "请稍后重试。如需紧急导出，请联系管理员。");
            }
            return true;
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉 InterruptedException 而不恢复标记，
            // 会让上层"停止这个线程"的意图丢失 —— 这是并发编程的经典错误。
            Thread.currentThread().interrupt();
            throw new BizException("导出被中断，请重试");
        }
    }

    /** 维表：区域编码 → 区域名称 */
    private Map<String, String> loadRegionNames() {
        List<Region> list = regionService.list();
        Map<String, String> map = new HashMap<>(list.size() * 2);
        for (Region r : list) {
            map.put(r.getRegionCode(), r.getRegionName());
        }
        return map;
    }

    /** 维表：指标编码 → 指标对象（要拿名称和单位两个字段） */
    private Map<String, Indicator> loadIndicators() {
        List<Indicator> list = indicatorService.list();
        Map<String, Indicator> map = new HashMap<>(list.size() * 2);
        for (Indicator i : list) {
            map.put(i.getIndicatorCode(), i);
        }
        return map;
    }

    /**
     * 事实表一行 → 导出行。
     *
     * 【这里有个必须做决定的地方：维表里查不到编码怎么办？】
     * 可能是区域被删了、或者历史脏数据。
     * 三种选择：
     *   ① 抛异常 → 一条脏数据害得整张表导不出来，最差
     *   ② 名称留空 → 用户看到"430999 | (空)"，不知道自己少了什么
     *   ③ 填一个明确的提示 → 用户能看到"这里有个区域已经不存在了"，还能自己判断
     * 选 ③。**导出文件的职责是"如实反映数据现状"，不是"只展示漂亮的数据"。**
     */
    private FactExportRow toFactRow(FactIndicatorValue f,
                                    Map<String, String> regionNames,
                                    Map<String, Indicator> indicators) {
        FactExportRow r = new FactExportRow();
        r.setRegionCode(f.getRegionCode());
        r.setRegionName(regionNames.getOrDefault(f.getRegionCode(), "（区域已不存在）"));

        Indicator ind = indicators.get(f.getIndicatorCode());
        r.setIndicatorCode(f.getIndicatorCode());
        r.setIndicatorName(ind == null ? "（指标已不存在）" : ind.getIndicatorName());
        r.setUnit(ind == null ? "" : ind.getUnit());

        r.setPeriodCode(f.getPeriodCode());
        r.setPeriodType(f.getPeriodType());
        r.setIndicatorValue(f.getIndicatorValue());
        r.setDataSource(f.getDataSource());
        return r;
    }

    /** 明细导出的 sheet 规格：区域编码(0)/指标编码(2)/周期编码(5)/周期类型(6) 都是文本 */
    private DataExportExporter.SheetSpec factSpec() {
        return new DataExportExporter.SheetSpec(
                "指标值明细", rowsPerSheet,
                new int[]{0, 2, 5, 6},
                new int[]{14, 20, 14, 28, 10, 14, 12, 18, 14});
    }

    /** 小结果集：一次写完，并回填行数/sheet 数 */
    private void writeSmall(HttpServletResponse response, Class<?> head, String sheetName,
                            int[] textColumns, int[] widths, List<?> rows, ExportLog logRow)
            throws IOException {
        try (OutputStream out = response.getOutputStream();
             DataExportExporter.PagedWriter<Object> writer = cast(DataExportExporter.open(out, head, 
                     new DataExportExporter.SheetSpec(sheetName, rowsPerSheet, textColumns, widths)))) {
            writer.write(new ArrayList<>(rows));
            logRow.setTotalRows((int) writer.getTotalRows());
            logRow.setSheetCount(writer.getSheetCount());
        }
    }

    /**
     * 泛型擦除的小工具：head 是 Class&lt;?&gt; 时拿不到 Class&lt;T&gt;，
     * 只能在这里做一次 unchecked 转换。
     *
     * 为什么允许这次"不安全"的转换？因为 head 和 rows 是**同一个 switch 分支**里
     * 一起决定的，两者一定匹配。这类"局部保证正确"的地方加 @SuppressWarnings 是合理的，
     * 而不是全局关掉警告。
     */
    @SuppressWarnings("unchecked")
    private static DataExportExporter.PagedWriter<Object> cast(DataExportExporter.PagedWriter<?> w) {
        return (DataExportExporter.PagedWriter<Object>) w;
    }

    /** 设置 Excel 下载的响应头（必须在写第一个字节之前调用） */
    private void setExcelHeaders(HttpServletResponse response, String fileName) {
        response.setContentType(DataExportExporter.XLSX_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", DataExportExporter.contentDisposition(fileName));
        // 前端跨域读文件名必需；不加的话 axios 拿到的 Content-Disposition 是 null
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        // ⚠️ 刻意不设 Content-Length：
        //   流式导出的总字节数要等 Excel 全部生成完才知道，提前设只能是瞎猜。
        //   不设的代价是 Tomcat 用 chunked 编码，浏览器**显示不了下载进度百分比**
        //   （只显示"已下载 xx MB"）。这是流式方案固定的体验代价。
    }

    // ---------------- 日志相关（旁路，绝不抛异常） ----------------

    private ExportLog newLog(String type, String params) {
        ExportLog l = new ExportLog();
        l.setExportType(type);
        l.setParamJson(truncate(params, 500));
        l.setCreateTime(LocalDateTime.now());
        l.setClientIp(currentIp());
        l.setTotalRows(0);
        l.setSheetCount(0);
        l.setCostMs(0);
        l.setStatus("UNKNOWN");
        return l;
    }

    /**
     * 写导出日志。
     *
     * 【★ 这个方法里的 try-catch 是刻意写的，不是偷懒 ★】
     * 日志是旁路：它失败了，不该让用户已经下载好的文件作废。
     * 所以这里**吞掉异常、只打 warn**。
     *
     * 反过来判断：如果一个操作失败会导致业务状态不一致（比如"导入任务状态"），
     * 那它就**必须**和主流程同成败，不能吞异常。
     * 「旁路可以吞，记账不能吞」—— 这条判断标准比记某个具体写法有用。
     */
    private void saveLog(ExportLog l) {
        try {
            if (l.getCreateTime() == null) {
                l.setCreateTime(LocalDateTime.now());
            }
            exportLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[导出-日志] 写入失败，不影响导出结果 type={} err={}", l.getExportType(), msg(e));
        }
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest req = attrs.getRequest();
            // 有反向代理时 getRemoteAddr 拿到的是代理 IP，真实 IP 在这两个头里。
            // X-Forwarded-For 可能是逗号分隔的列表（客户端, 代理1, 代理2），取第一个。
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private static String typeOfStat(String kind) {
        return switch (kind) {
            case "category" -> ExportType.STAT_CATEGORY;
            case "trend" -> ExportType.STAT_TREND;
            case "top" -> ExportType.STAT_TOP;
            case "yoy" -> ExportType.STAT_YOY;
            default -> "STAT_" + kind.toUpperCase();
        };
    }

    private static String describe(FactExportQuery q) {
        return DataExportExporter.simpleJson(
                "regionCodePrefix", q.getRegionCodePrefix(),
                "indicatorCode", q.getIndicatorCode(),
                "periodType", q.getPeriodType(),
                "periodFrom", q.getPeriodFrom(),
                "periodTo", q.getPeriodTo());
    }

    private static String describe(StatExportQuery q) {
        return DataExportExporter.simpleJson(
                "kind", q.getKind(),
                "category", q.getCategory(),
                "periodType", q.getPeriodType(),
                "periodCodes", q.getPeriodCodes() == null ? null : String.join(",", q.getPeriodCodes()),
                "basePeriod", q.getBasePeriod(),
                "currentPeriod", q.getCurrentPeriod(),
                "topN", q.getTopN());
    }

    private static List<List<String>> head(String... names) {
        List<List<String>> h = new ArrayList<>(names.length);
        for (String n : names) {
            h.add(Collections.singletonList(n));
        }
        return h;
    }

    private static List<Object> row(Object... values) {
        List<Object> r = new ArrayList<>(values.length);
        Collections.addAll(r, values);
        return r;
    }

    /** 值类型转中文。数据库存 1/2/3，导给用户看的应该是"数值/百分比/金额" */
    private static String valueTypeLabel(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case 2 -> "百分比";
            case 3 -> "金额";
            default -> "数值";
        };
    }

    private static void requireText(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new BizException(name + " 不能为空");
        }
    }

    private static void requirePeriodCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BizException("periodCodes（周期编码）不能为空，如 2024,2025,2026");
        }
    }

    private static String msg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * 当前堆内存占用（MB）。
     *
     * 【为什么日志里要打这个】
     * 因为"流式导出内存恒定"这个说法，如果不打点，就只是一句口号。
     * 打出前后两次的堆占用，你才能在下一次改代码时**立刻发现**自己是不是
     * 一不小心把整表查进内存了（堆占用会从 30MB 跳到 300MB）。
     *
     * 注意：这是"已用堆"，包含垃圾对象，所以数字会有波动，不是精确的对象大小。
     * 要精确测量得用 JFR / jmap。这里的用途只是"看趋势有没有失控"。
     */
    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
    }
}

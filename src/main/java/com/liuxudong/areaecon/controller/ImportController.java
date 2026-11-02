package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.controller.vo.ErrorSummaryVO;
import com.liuxudong.areaecon.controller.vo.ImportResultVO;
import com.liuxudong.areaecon.service.ImportReportService;
import com.liuxudong.areaecon.service.ImportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Excel 导入接口。
 *
 * 【这个 Controller 承担了一个额外职责：演示"同一功能的两种交互模型"】
 *
 *   POST /api/import/sync   同步：请求一直挂着，等到全部入库才返回
 *   POST /api/import/async  异步：200ms 内返回 taskId，后台慢慢跑
 *
 * 两个接口都留着，用同一份文件打，耗时差距是最有说服力的数据。
 */
@Slf4j
@RestController
@RequestMapping("/api/import")
public class ImportController {

    /** batchSize 的允许范围。上限 5000 是防止有人传 10 万，拼出一条 20MB 的 SQL 把 MySQL 打挂 */
    private static final int BATCH_SIZE_MIN = 1;
    private static final int BATCH_SIZE_MAX = 5000;

    private final ImportService importService;
    private final ImportReportService importReportService;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public ImportController(ImportService importService, ImportReportService importReportService) {
        this.importService = importService;
        this.importReportService = importReportService;
    }

    /**
     * 同步导入（改造前的版本）。
     *
     * 用 curl 打的时候注意：这个接口会阻塞几十秒，
     * curl 默认没有超时，能等到；但浏览器和多数网关（Nginx 默认 60s、
     * 很多公司的 API 网关是 10s 或 30s）会直接掐断连接。
     * **"用户等不到结果"正是我们要改成异步的唯一原因。**
     *
     * @param file      表单字段名必须是 file（curl 用 -F "file=@xxx.xlsx"）
     * @param batchSize 每批写多少行。默认 1（逐行插入，最慢）；传 500 就是批量插入
     */
    @PostMapping("/sync")
    public Result<ImportResultVO> importSync(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "batchSize", defaultValue = "1") Integer batchSize) {

        if (batchSize == null || batchSize < BATCH_SIZE_MIN || batchSize > BATCH_SIZE_MAX) {
            throw new BizException("batchSize 必须在 " + BATCH_SIZE_MIN + " ~ " + BATCH_SIZE_MAX + " 之间");
        }

        log.info("[导入-同步] 收到文件 name={} size={}KB batchSize={}",
                file.getOriginalFilename(), file.getSize() / 1024, batchSize);

        return Result.ok(importService.importSync(file, batchSize));
    }

    /**
     * 异步导入 —— **接口只接单，200ms 内返回**。
     *
     * 返回的 data.taskId 就是"取号单"。前端拿着它轮询 {@code GET /api/import/task/{id}}。
     *
     * 【前端怎么用（真实项目就这么写）】
     *   1. POST 上传 → 拿到 taskId
     *   2. 每 1~2 秒 GET 一次状态，直到 status 变成 SUCCESS 或 FAILED
     *   3. 记得加"最大轮询次数"（比如 60 次 = 2 分钟），
     *      否则后台任务卡死时前端会无限轮询 —— 用户永远看到"加载中"。
     *
     * 【为什么不用 WebSocket / SSE 主动推送】
     * 也可以，而且体验更好。但轮询实现最简单、最不容易出问题，
     * 对"导入"这种低频操作完全够用（1 万行也就轮询十几次）。
     * 技术选型的常识：**能用简单方案解决的，不要上复杂方案。**
     */
    @PostMapping("/async")
    public Result<ImportResultVO> importAsync(@RequestParam("file") MultipartFile file) {
        log.info("[导入-异步] 收到文件 name={} size={}KB",
                file.getOriginalFilename(), file.getSize() / 1024);
        return Result.ok(importService.importAsync(file));
    }

    /**
     * 查任务状态。
     *
     * 【幂等性说明】同一个文件重复上传不会新建任务，会返回**已有的那个 taskId**。
     * 所以前端拿到的 taskId 可能不是"这次"创建的 —— 这是正确行为，不是 bug。
     */
    @GetMapping("/task/{taskId}")
    public Result<ImportResultVO> getTask(@PathVariable("taskId") Long taskId) {
        return Result.ok(importService.getTask(taskId));
    }

    /**
     * 手动重试一个失败的任务。
     *
     * 【自动重试都有了，为什么还要这个接口】
     * 自动重试只处理"临时故障"，且最多 3 次（累计等待 7 秒）。
     * 超过之后任务就永久 FAILED 了。但很多时候问题在于**环境**，
     * 而不是数据：数据库在维护、连接池被临时打满、磁盘满了被清理 ——
     * 等运维处理完（可能是半小时后），数据本身完全没问题，只是需要一个"再来一次"的按钮。
     *
     * 没有这个接口，用户只能重新上传同一个文件 ——
     * 但 MD5 没变，会被幂等逻辑拦下来，直接返回那个 FAILED 的旧任务。
     * **用户会以为系统坏了。** 这就是手动重试接口存在的价值。
     *
     * 【幂等性】
     * 同一个任务连续点两次：第一次成功改状态并投递，第二次会因为
     * 状态已经是 PENDING 而返回"任务已在队列中" —— 不会重复导入。
     *
     * @param taskId 任务 ID
     */
    @PostMapping("/task/{taskId}/retry")
    public Result<ImportResultVO> retryTask(@PathVariable("taskId") Long taskId) {
        log.info("[导入-手动重试] taskId={}", taskId);
        return Result.ok(importService.retryTask(taskId));
    }

    // ==========================================================================
    // 导入闭环 —— 模板下载 / 错误报告 / 错误统计
    // ==========================================================================

    /**
     * 下载导入模板。
     *
     * 【为什么模板要由后端生成，不给一个静态文件放前端】
     * 因为模板和后端的解析逻辑是**强耦合**的：表头文字、列顺序、周期类型取值范围，
     * 任何一边改了另一边必须同步。如果模板是一个写死的 .xlsx 放在静态资源目录，
     * 代码改了字段、模板忘了更新 —— 用户下载的模板就是错的，而且**谁都不会发现**，
     * 直到用户投诉"我按你的模板填的，你却说格式错误"。
     *
     * 由后端生成，模板和 @ExcelProperty 用的是同一份定义，天然同步。
     * 另外：Excel 的"文本格式"这类设置，也只有代码生成才能精确控制。
     */
    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) {
        setDownloadHeaders(response, "指标数据导入模板.xlsx");
        try {
            importReportService.writeTemplate(response.getOutputStream());
        } catch (IOException e) {
            // ★ 到这里响应头已经发出去了，**不可能再改成 JSON 错误**。
            //   这就是"流式下载接口"的固有代价：一旦开始写流，就没有回头路。
            //   所以凡是能提前校验的（任务是否存在、有没有错误行），
            //   都必须在 setDownloadHeaders 之前做完 —— 见下面 downloadErrors 的写法。
            log.error("[导入-模板] 写响应流失败（客户端可能已断开）", e);
        }
    }

    /**
     * 下载错误报告。
     *
     * 两种范围：
     *   scope=error（默认）—— 只有错误行。文件小、聚焦，适合"我就想看看错在哪"
     *   scope=all         —— 全部行 + 错误原因列。正确行的原因为空，
     *                        适合"我要在一个文件里改完整体重传"
     *
     * 【为什么默认是 error 而不是 all】
     * 因为 all 要重新解析一遍原始文件（1 万行约 1~2 秒），而大多数时候用户
     * 只想快速确认问题。默认走轻的那条路，需要全量的用户显式加参数。
     * **默认值应该选"最常用的那个"，不是"功能最强的那个"。**
     */
    @GetMapping("/task/{taskId}/errors")
    public void downloadErrors(@PathVariable("taskId") Long taskId,
                               @RequestParam(value = "scope", defaultValue = "error") String scope,
                               HttpServletResponse response) {
        // ---------- ① 所有可能失败的校验，全部放在写响应头之前 ----------
        ErrorSummaryVO summary = importReportService.getErrorSummary(taskId);
        if (summary.getRecordedRows() == null || summary.getRecordedRows() == 0) {
            // 没有错误行 —— 这时抛异常是安全的，响应还没开始写，全局异常处理器能返回 JSON
            throw new BizException("该任务没有错误明细，无需下载错误报告");
        }
        if (!"error".equalsIgnoreCase(scope) && !"all".equalsIgnoreCase(scope)) {
            throw new BizException("scope 只能是 error（仅错误行）或 all（全部数据）");
        }

        // ---------- ② 设头 + 写流 ----------
        String fileName = "导入错误报告_任务" + taskId + "_"
                + LocalDateTime.now().format(FILE_TS) + ".xlsx";
        setDownloadHeaders(response, fileName);
        try {
            importReportService.writeErrorReport(taskId, scope, response.getOutputStream());
        } catch (IOException e) {
            log.error("[导入-错误报告] 写响应流失败（客户端可能已断开）taskId={}", taskId, e);
        } catch (RuntimeException e) {
            // 例如 scope=all 时原文件已被清理。此时响应已 committed，
            // 只能记日志；用户下载到的是一个不完整的文件。
            // 真实项目里通常会在下载前把"文件是否还在"也检查一遍，
            // 或者干脆把原文件存到对象存储（不会被人为清理）。
            log.error("[导入-错误报告] 生成失败 taskId={} scope={}", taskId, scope, e);
        }
    }

    /**
     * 错误统计：哪类错误最多、占多少。
     *
     * 返回 JSON 而不是文件，因为这是"快速判断"用的：
     *   {"errorRows":3000,"byType":[{"errorTypeLabel":"周期类型非法","count":2680,"percent":89.33}]}
     * 一眼就能看出"主要是周期类型填错了，改这一列就行"。
     * 要看每一行，再去下载 Excel —— **摘要走接口，明细走文件。**
     */
    @GetMapping("/task/{taskId}/error-summary")
    public Result<ErrorSummaryVO> getErrorSummary(@PathVariable("taskId") Long taskId) {
        return Result.ok(importReportService.getErrorSummary(taskId));
    }

    /**
     * 设置下载响应头。
     *
     * 【中文文件名为什么必须编码】
     * HTTP 头只能是 ASCII。直接写 `filename="指标数据导入模板.xlsx"`，
     * Tomcat 会按 ISO-8859-1 编码，浏览器拿到就是乱码（一堆问号或奇怪的拉丁字符）。
     * 用户下载下来文件名变成 `?????.xlsx`，还得自己重命名。
     *
     * 两层写法一起给，兼容所有浏览器：
     *   filename="..."             —— 老浏览器（IE 时代）认这个
     *   filename*=UTF-8''...       —— RFC 5987 标准写法，现代浏览器优先用它
     *
     * URLEncoder 会把空格变成 "+"，但文件名里的 "+" 在 RFC 5987 里表示字面加号，
     * 空格必须是 %20。所以要把 "+" 换回 "%20" —— 这一步漏了，
     * 文件名里的空格会变成加号。
     *
     * 【Access-Control-Expose-Headers 是干什么的】
     * 前端如果要从响应头里读文件名（比如用 axios 取 Content-Disposition），
     * 跨域时浏览器默认**不暴露**这个头，前端读到的是 null，只能自己随便起个名字。
     * 加上这一行，前端才能读到真名。做前后端分离时这个坑很常见。
     */
    private void setDownloadHeaders(HttpServletResponse response, String fileName) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
    }
}

package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.controller.vo.ImportResultVO;
import com.liuxudong.areaecon.service.ImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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

    public ImportController(ImportService importService) {
        this.importService = importService;
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
}

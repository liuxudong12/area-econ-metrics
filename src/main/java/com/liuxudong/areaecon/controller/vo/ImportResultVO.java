package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Excel 导入的结果 / 进度。
 *
 * 同一个 VO 在两个场景用：
 *   ① 同步导入：接口直接返回它，里面是"这次导入的统计"
 *   ② 异步任务：查任务状态时，从 async_task 表读出同样的字段拼成它
 *
 * 【为什么异步场景还需要 totalRows / successRows 两个字段】
 * 因为用户最关心的不只是"成功还是失败"，而是"到底进去多少行"。
 * 想象一下：导入 1 万行，状态显示 FAILED —— 用户第一个问题必然是
 * "那我这 1 万行到底进了多少？" 如果只能看到 FAILED，他只能去问开发。
 */
@Data
public class ImportResultVO {

    /** 任务 ID（同步接口为 null，异步接口才有） */
    private Long taskId;

    /** 任务状态：PENDING / RUNNING / SUCCESS / FAILED（同步接口直接返回 SUCCESS 或 FAILED） */
    private String status;

    /** 解析出的总行数 */
    private Integer totalRows;

    /** 成功写入的行数 */
    private Integer successRows;

    /** 有问题的行数 */
    private Integer errorRows;

    /** 错误明细（最多几条，避免响应体过大） */
    private List<String> errors;

    /** 本次操作耗时（毫秒）。这个数字就是"为什么要异步"的证据 */
    private Long elapsedMs;

    /** 本次实际使用的批大小：1 = 逐行插入，500 = 批量插入。用来做性能对比实验 */
    private Integer batchSize;

    /**
     * 已重试次数。
     *
     * 为什么要把这个暴露给前端？
     * 因为"失败 1 次后自动重试"和"失败 3 次即将放弃"是**完全不同的用户预期**。
     * 前者用户会安心等，后者用户该考虑去检查数据库了。
     * 只给一个 status=RETRY_WAIT，用户根本不知道还要等多久、还有几次机会。
     */
    private Integer retryCount;

    /**
     * 下次重试的时间点（只有 RETRY_WAIT 状态有值）。
     *
     * 前端可以据此显示倒计时："3 秒后自动重试"。
     * 比笼统的"请稍候"体验好得多 —— **能给出确定的等待时长，用户就不会焦虑。**
     */
    private LocalDateTime nextRetryTime;

    /**
     * 错误报告是否可下载。
     *
     * 只有"有错误明细"的失败任务才是 true。
     * 为什么不让前端自己判断（errorRows > 0）？
     *   因为 errorRows 是"校验错误的行数"，而"能不能下载"还取决于
     *   明细是否真的落库成功了（落库失败时明细是空的）。
     *   这种"能不能做"的判断应该由后端给出确定答案，不要让前端去推测。
     */
    private Boolean errorReportAvailable;

    /**
     * 错误报告下载地址。
     *
     * 直接给完整路径，前端拿来实现"下载"按钮即可 —— 不用自己拼 taskId、
     * 也不用知道是 /api/import 还是 /import。**接口地址变了只改后端一处。**
     * （这种"由后端下发 URL"的做法在 HATEOAS 里叫 hypermedia，
     * 但这里没那么正式，就是单纯让前端少拼一次字符串。）
     */
    private String errorReportUrl;

    /** 给前端/用户看的一句话提示 */
    private String message;
}

package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

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

    /** 本次操作耗时（毫秒）。面试时这个数字就是"为什么要异步"的证据 */
    private Long elapsedMs;

    /** 本次实际使用的批大小：1 = 逐行插入，500 = 批量插入。用来做性能对比实验 */
    private Integer batchSize;

    /** 给前端/用户看的一句话提示 */
    private String message;
}

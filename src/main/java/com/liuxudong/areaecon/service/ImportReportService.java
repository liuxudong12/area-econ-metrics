package com.liuxudong.areaecon.service;

import com.liuxudong.areaecon.controller.vo.ErrorSummaryVO;

import java.io.OutputStream;

/**
 * 导入报告服务：模板下载 + 错误报告下载 + 错误统计。
 *
 * 【为什么单独一个 Service，不塞进 ImportService】
 * ImportService 已经 1000 多行了，管着"接单、解析、写库、重试、状态机"。
 * 这里管的是完全不同的另一件事：**把已经存在的数据整理成用户能拿走的形式**。
 *
 * 两者变化的原因不一样：
 *   · 导入逻辑会因为"业务规则变了"而改（比如新增必填字段）
 *   · 导出逻辑会因为"用户想看什么"而改（比如加一列、改列顺序）
 * 变化原因不同的代码放在一起，改一个就会碰到另一个。
 * 分开之后，改导出不会碰到导入，风险小得多。
 */
public interface ImportReportService {

    /**
     * 把导入模板写进输出流。
     *
     * @param out 一般是 HttpServletResponse.getOutputStream()
     */
    void writeTemplate(OutputStream out);

    /**
     * 把错误报告写进输出流。
     *
     * @param taskId 任务 ID
     * @param scope  error = 只导出错误行（文件小、聚焦）；all = 导出全部行 + 错误原因列
     *               （正确行的原因为空，用户可以在一个文件里改完整体重传）
     * @param out    输出流
     */
    void writeErrorReport(Long taskId, String scope, OutputStream out);

    /**
     * 错误统计摘要（给任务详情页展示"主要错在哪"）。
     */
    ErrorSummaryVO getErrorSummary(Long taskId);
}

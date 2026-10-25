package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

import java.util.List;

/**
 * 错误统计（前端在任务详情页展示）：
 *   本次导入 10000 行，3000 行有误
 *   ├─ 周期类型非法   2680 行  89.3%   ← 一眼看出主要问题在哪
 *   ├─ 指标值为负      320 行  10.7%
 *
 * 【为什么要有这个接口，直接让用户下载 Excel 看不就行了】
 * 因为"下载一个文件、用 Excel 打开、还要自己筛选排序"是有成本的。
 * 很多时候用户只想快速知道"这次错的严重不严重、主要是什么问题" ——
 * 这些用一行 JSON 就能回答，不该让他去开文件。
 *
 * 真实产品的做法是：任务详情页直接显示这段统计 + 一个"下载全部明细"的按钮。
 * **摘要用接口，明细用文件。** 两者的成本差了一个数量级。
 */
@Data
public class ErrorSummaryVO {

    private Long taskId;

    private String fileName;

    private String status;

    /** 文件总行数 */
    private Integer totalRows;

    /**
     * 校验失败的行数（真实总数）。
     *
     * 来自 async_task.error_rows，不是 count(import_error_row) ——
     * 因为明细有条数上限，截断后两者不相等。
     */
    private Long errorRows;

    /** 实际记录在库里的明细条数 */
    private Long recordedRows;

    /**
     * 明细是否被截断（errorRows &gt; recordedRows）。
     * 为 true 时前端要提示"仅记录前 N 条，完整错误行数见 errorRows"。
     */
    private Boolean truncated;

    /** 按错误类型分组，已按数量降序 */
    private List<ErrorTypeStat> byType;

    @Data
    public static class ErrorTypeStat {
        /** 枚举 code，给程序用 */
        private String errorType;
        /** 中文名，给用户看 */
        private String errorTypeLabel;
        /** 该类型的行数 */
        private Long count;
        /** 占全部错误行的百分比，保留 2 位 */
        private Double percent;
    }
}

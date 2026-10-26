package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出日志（export_log）。
 *
 * ==========================================================================
 * 【导出为什么值得单独建一张日志表】
 * ==========================================================================
 * 导出是典型的「便宜接口、昂贵资源」：
 *   用户点一下 → 数据库顺序吐出几十万行 → 应用拼出几十 MB 的 Excel → 网络传出几十 MB。
 *
 * 它和"查一条数据"完全不是一个量级的操作，但它们在代码里长得一样：
 * 都是一个 GET 请求、都返回 200。**危险就危险在这里** ——
 * 线上数据库突然被打满，你不会立刻想到是某个"只读接口"干的。
 *
 * 有这张表之后，排查路径变成一条 SQL：
 * <pre>
 *   SELECT export_type, param_json, total_rows, cost_ms, client_ip
 *   FROM export_log
 *   WHERE create_time &gt;= NOW() - INTERVAL 10 MINUTE
 *   ORDER BY cost_ms DESC LIMIT 20;
 * </pre>
 * 谁、在什么时候、导了多少行、花了多久 —— 一目了然。
 *
 * ==========================================================================
 * 【一个容易忽略的设计：日志写失败，不能影响导出】
 * ==========================================================================
 * 日志是"附加品"。如果因为日志表被锁住、或者字段超长导致 insert 报错，
 * 就把已经导出的文件搞失败，那是本末倒置。
 * 所以 Service 里写这条日志时是 **try-catch 吞掉异常 + 只打 warn 日志**。
 *
 * 这条原则在真实项目里很常见，叫「旁路逻辑不能影响主链路」：
 *   埋点、日志、审计、通知类操作，失败都应该只记录、不抛出去。
 * 但反过来，**记账类**的操作（比如导入任务的状态更新）就必须和主流程同成败 ——
 * 判断标准是：这个数据丢了，会不会导致业务状态不一致？
 */
@Data
@TableName("export_log")
public class ExportLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 导出类型，见 {@link ExportType} */
    private String exportType;

    /** 请求参数快照（拼成一行 JSON 字符串，只为人看，不参与查询） */
    private String paramJson;

    /** 返回给用户的文件名 */
    private String fileName;

    /** 实际导出的数据行数（不含表头） */
    private Integer totalRows;

    /** 生成了几个 sheet。超过 Excel 单表上限时会被切分 */
    private Integer sheetCount;

    /** 耗时（毫秒）：从开始取数到写完文件 */
    private Integer costMs;

    /** SUCCESS / FAILED / REJECTED */
    private String status;

    private String errorMsg;

    private String clientIp;

    private LocalDateTime createTime;
}

package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入错误行明细（表 import_error_row）。
 *
 * ==========================================================================
 * 【为什么错误明细必须落库，不能只放在内存 / 日志里？】
 * ==========================================================================
 * 三个理由，每个都是真实会出问题的：
 *
 * ① 任务跑完，线程就没了
 *    异步导入执行在后台线程里。执行完 → 线程回到池里 → 它持有的 List 被 GC。
 *    用户 10 分钟后再点"下载错误报告"，内存里早没这东西了。
 *
 * ② 日志不是给用户看的
 *    "去日志里搜吧"是研发思维，不是产品思维。用户不是开发，他打不开服务器。
 *    而且日志会滚动删除、会被清理，一周后就查不到了。
 *
 * ③ 服务重启 / 多实例部署
 *    错误明细在 A 机器的内存里，用户的下一次请求被负载均衡打到了 B 机器 ——
 *    下载按钮点了没反应。
 *    落库之后，**任何一台机器都能生成错误报告**，这才是能上生产的设计。
 *
 * 一句话：**用户可能需要再次访问的东西，就必须持久化。**
 * （和 Day 9"线程池只干活、任务表才记账"是同一条原则。）
 *
 * ==========================================================================
 * 【这一行和 FactImportRow 有什么不同？】
 * ==========================================================================
 *   FactImportRow  —— 数据"正常"时的形状：indicatorValue 是 BigDecimal
 *   ImportErrorRow —— 数据"不正常"时的形状：indicatorValue 是 String
 *
 * 这看起来只是类型不同，实际上是一条设计原则：
 * **接受正常数据的模型，不能用来描述错误数据。**
 * 因为错误数据的本质就是"不符合模型" —— 硬套只会让"记录错误"这件事本身失败。
 */
@Data
@TableName("import_error_row")
public class ImportErrorRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务。同一个任务重试多次时，每次都会先清空旧错误再写新的（见 ImportServiceImpl） */
    private Long taskId;

    /**
     * Excel 里的行号（含表头，从 1 开始，和用户打开 Excel 看到的行号完全一致）。
     *
     * 【为什么必须和用户在 Excel 里看到的行号一致】
     * 因为用户拿到错误报告的第一反应是"打开原文件，跳到第 1003 行看看"。
     * 如果这里存的是 0-based 或者"数据行序号"（去掉了表头），
     * 用户按这个数字去翻，会永远差 1 行 —— 然后他就会开始怀疑整个系统的可靠性。
     * **面向用户的数字，必须和用户看到的对齐。**
     */
    private Integer rowNum;

    // ---------- 下面 5 个字段是用户填的原值，全部按字符串存 ----------
    private String regionCode;
    private String indicatorCode;
    private String periodCode;
    private String periodType;
    private String indicatorValue;

    /** 错误类型，取值见 {@link ImportErrorType}。存的是枚举的 name()，不是中文 */
    private String errorType;

    /** 详细原因，给人看 */
    private String errorMsg;

    private LocalDateTime createTime;
}

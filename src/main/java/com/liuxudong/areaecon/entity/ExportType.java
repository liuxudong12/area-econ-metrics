package com.liuxudong.areaecon.entity;

/**
 * 导出类型。用常量而不是枚举，因为要直接存进数据库的 VARCHAR 字段，
 * 而且前端也会拿这个字符串做统计分组。
 *
 * 【为什么不用 Java 枚举】
 * 枚举 + MyBatis 默认存的是名字（name()），也完全可以。
 * 但这里的值同时是"数据库里能看到的一列"和"前端筛选条件"，
 * 用 String 常量更直白：查 `WHERE export_type = 'FACT'` 时不用在脑子里翻译。
 * 小项目里少一层类型转换，就少一处可能对不上的地方。
 */
public final class ExportType {

    private ExportType() {
    }

    /** 指标值明细 */
    public static final String FACT = "FACT";

    /** 区域汇总（category-summary） */
    public static final String STAT_CATEGORY = "STAT_CATEGORY";

    /** 周期趋势（trend） */
    public static final String STAT_TREND = "STAT_TREND";

    /** TOP N 排行 */
    public static final String STAT_TOP = "STAT_TOP";

    /** 同比 */
    public static final String STAT_YOY = "STAT_YOY";

    /** 区域维表 */
    public static final String DICT_REGION = "DICT_REGION";

    /** 指标维表 */
    public static final String DICT_INDICATOR = "DICT_INDICATOR";

    /** 周期维表 */
    public static final String DICT_PERIOD = "DICT_PERIOD";

    /** 状态：成功 */
    public static final String OK = "SUCCESS";

    /** 状态：导到一半失败 */
    public static final String FAILED = "FAILED";

    /** 状态：还没开始就被拒绝（超上限 / 并发超限） */
    public static final String REJECTED = "REJECTED";
}

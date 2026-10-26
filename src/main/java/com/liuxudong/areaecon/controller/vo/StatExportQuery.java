package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

import java.util.List;

/**
 * 统计结果导出的参数。
 *
 * 【为什么不把 kind / category / periodCodes 都作为方法参数一个个传】
 * 因为 Service 里要按 kind 分四个分支，每个分支用的参数不一样
 * （yoy 要 basePeriod/currentPeriod，top 要 topN）。
 * 全拆成参数就是 7 个入参的方法，调用处一长串 null 和逗号，
 * 而且"第 4 个参数到底是什么"每次都要去数。
 *
 * 包成一个对象后：
 *   · 调用处可读（setKind、setCategory 一眼知道在干什么）
 *   · 以后加一个筛选条件，不用改方法签名（进而不用改所有调用点）
 *
 * **参数超过 3~4 个，就该考虑包对象** —— 这是很实用的经验规则。
 */
@Data
public class StatExportQuery {

    /** 导出哪一种：category / trend / top / yoy */
    private String kind;

    /** 指标分类，如"工业" */
    private String category;

    /** 周期类型 Y/Q/M，默认 Y */
    private String periodType = "Y";

    /** 周期编码列表，如 ["2024","2025"]。Controller 会把逗号分隔的字符串拆成 List */
    private List<String> periodCodes;

    /** 同比：基期 */
    private String basePeriod;

    /** 同比：当期 */
    private String currentPeriod;

    /** TOP N 的 N，默认 10 */
    private Integer topN = 10;
}

package com.liuxudong.areaecon.service;

import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;

import java.util.List;

/**
 * 统计业务接口 —— 大屏 / 报表类查询都放这里。
 *
 * 【它和我们之前的 RegionService 有个区别】
 * RegionService 继承了 IService<Region>，白拿了 save/getById 那一套。
 * 但"统计"没有对应的实体表，它是对 fact_indicator_value 的各种聚合，
 * 所以这个接口不继承 IService，就是一组自定义方法。
 *
 * 第 9-10 天给它加 Redis 缓存时，改动会全部收敛在这个接口的实现里，
 * Controller 一行都不用动 —— 这就是分层的价值。
 */
public interface StatService {

    /**
     * 按指标分类，汇总各区域的指标值。
     *
     * @param category    指标分类，如"工业"
     * @param periodType  周期类型：Y / Q / M
     * @param periodCodes 周期编码列表，如 ["2024","2025","2026"]
     */
    List<RegionMetricStatVO> categorySummary(String category, String periodType, List<String> periodCodes);

    /**
     * 按周期看趋势 —— 大屏折线图用。
     *
     * ⚠️ 这个方法目前是**慢的**（实测 49 万行约 123ms，全表扫描）。
     * 不是写错了，是**故意留着给第 6-8 天做索引优化实验的**。
     * 现在你要做的是：记住它慢，并想清楚它为什么慢。
     */
    List<PeriodMetricStatVO> categoryTrend(String category, String periodType, List<String> periodCodes);
}

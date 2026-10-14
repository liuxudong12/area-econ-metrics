package com.liuxudong.areaecon.service;

import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionRankVO;
import com.liuxudong.areaecon.controller.vo.RegionYoyVO;

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
     * Day 6 改造：SQL 里不再 JOIN dim_region，区域名称由本方法的实现"批量补上"。
     * 理由和实测数据（44.3ms → 9.52ms）写在 FactIndicatorValueMapper.xml 的注释里。
     *
     * @param category    指标分类，如"工业"
     * @param periodType  周期类型：Y / Q / M
     * @param periodCodes 周期编码列表，如 ["2024","2025","2026"]
     */
    List<RegionMetricStatVO> categorySummary(String category, String periodType, List<String> periodCodes);

    /**
     * 按周期看趋势 —— 大屏折线图用。
     *
     * ✅ Day 5 已优化：加 idx_tpi 索引后 133.45ms → 9.66ms（扫描行数 48.9 万 → 5,418）。
     */
    List<PeriodMetricStatVO> categoryTrend(String category, String periodType, List<String> periodCodes);

    // ==================== Day 6 新增 ====================

    /**
     * TOP N 区域排行 —— 大屏"排行榜"用。
     *
     * 返回结果按总值**从大到小**排列，且已带好名次（rankNo）和占比（share）。
     *
     * 【topN 该不该设上限】
     * 该。这是防"参数被玩坏"的基本功：
     *   · 传 topN=1000000 虽然查不出那么多行，但会让 MySQL 白排一次序；
     *   · 传 topN=-1 在某些写法下会变成语法错误或全量返回。
     * 所以校验放在 Controller 层，把非法参数挡在业务逻辑之外。
     *
     * @param topN 取前几名，1 ~ 100
     */
    List<RegionRankVO> topRegions(String category, String periodType, List<String> periodCodes, Integer topN);

    /**
     * 各区域的同比 / 环比。
     *
     * 【一个接口覆盖两种口径】
     *   basePeriod=2024,    currentPeriod=2025     → 年同比
     *   basePeriod=2026-02, currentPeriod=2026-03  → 月环比
     * 代码一模一样，只是传参不同。**别为"同比""环比"写两个接口**，
     * 它们本质是同一个计算，参数化就够了。
     *
     * @param basePeriod    基期（被比较的那一期）
     * @param currentPeriod 当期（要算的这一期）
     */
    List<RegionYoyVO> regionYoy(String category, String periodType, String basePeriod, String currentPeriod);
}

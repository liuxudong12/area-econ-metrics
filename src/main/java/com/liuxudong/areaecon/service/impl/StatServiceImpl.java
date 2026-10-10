package com.liuxudong.areaecon.service.impl;

import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.mapper.FactIndicatorValueMapper;
import com.liuxudong.areaecon.service.StatService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 统计业务实现。
 *
 * 【注意这里没有继承 ServiceImpl】
 * ServiceImpl 是给"单表 CRUD"用的（它需要一个实体 + 对应 Mapper）。
 * 统计查询跨三张表、返回自定义 VO，不属于单表操作，所以直接注入 Mapper 用就好。
 *
 * 【为什么注入的是 Mapper 而不是别的 Service】
 * 统计只依赖 fact_indicator_value 这一张主表的数据访问，
 * 不需要 RegionService / IndicatorService —— 保持依赖最小化。
 *
 * 【@Transactional 要不要加？】
 * 这里全是只读查询，不需要事务。事务是为了保证"多步写的原子性"，
 * 只读场景加事务只是白白多一层代理开销。
 * 真要加也只读优化，可以写 @Transactional(readOnly = true)。
 *
 * 【第 6-8 天这里会发生什么】
 * 你会把这个方法的 SQL 拿去 EXPLAIN，发现它全表扫描了 10 万行。
 * 加完索引之后，同一条 SQL 的 type 从 ALL 变成 range/ref，耗时掉一个数量级。
 * **那组前后对比数字，就是你简历上"索引优化"那一条。**
 */
@Service
public class StatServiceImpl implements StatService {

    private final FactIndicatorValueMapper factIndicatorValueMapper;

    public StatServiceImpl(FactIndicatorValueMapper factIndicatorValueMapper) {
        this.factIndicatorValueMapper = factIndicatorValueMapper;
    }

    @Override
    public List<RegionMetricStatVO> categorySummary(String category, String periodType, List<String> periodCodes) {
        // 这一行就是全部逻辑 —— 业务简单，就让它简单。
        // 不要在 Service 里再套一层没有意义的 if/else，那是给自己找事。
        return factIndicatorValueMapper.selectCategorySummary(category, periodType, periodCodes);
    }

    @Override
    public List<PeriodMetricStatVO> categoryTrend(String category, String periodType, List<String> periodCodes) {
        // 就是这一行，全表扫描 48 万行，123ms。
        // 第 6-8 天加完索引后，这行代码一个字都不用改，性能却会掉一个数量级 ——
        // 这就是"优化 SQL 和索引，而不是改代码"的典型场景。
        return factIndicatorValueMapper.selectPeriodTrend(category, periodType, periodCodes);
    }
}

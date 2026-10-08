package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.FactIndicatorValue;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标值事实表 Mapper。
 *
 * 第 5 天做"大屏聚合统计"（跨 fact + dim_indicator 多表 join）时，
 * 会在这里加一个自定义方法 + 配套的 XML：
 *     List<RegionMetricVO> sumByCategory(统计条件);
 * 那时候才需要手写 SQL —— 单表能用 BaseMapper 解决就绝不要写 XML。
 */
@Mapper
public interface FactIndicatorValueMapper extends BaseMapper<FactIndicatorValue> {
}

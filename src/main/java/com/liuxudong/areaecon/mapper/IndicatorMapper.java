package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.Indicator;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标维表 Mapper。用法同 RegionMapper，全部由 BaseMapper 提供。
 */
@Mapper
public interface IndicatorMapper extends BaseMapper<Indicator> {
}

package com.liuxudong.areaecon.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liuxudong.areaecon.entity.Indicator;
import com.liuxudong.areaecon.mapper.IndicatorMapper;
import com.liuxudong.areaecon.service.IndicatorService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 指标业务实现。和 RegionServiceImpl 一个套路。
 */
@Service
public class IndicatorServiceImpl extends ServiceImpl<IndicatorMapper, Indicator> implements IndicatorService {

    @Override
    public List<Indicator> listByCategory(String category) {
        return lambdaQuery()
                // 老配方：第一个参数是"开关"，category 为空就不拼这个条件
                .eq(StringUtils.hasText(category), Indicator::getCategory, category)
                .orderByAsc(Indicator::getCategory)
                .orderByAsc(Indicator::getSort)
                .list();
    }

    @Override
    public List<String> listCategories() {
        // baseMapper 是 ServiceImpl 提供的、指向 IndicatorMapper 的字段。
        // 我们自定义的 SQL（selectCategories）必须通过它来调。
        return baseMapper.selectCategories();
    }
}

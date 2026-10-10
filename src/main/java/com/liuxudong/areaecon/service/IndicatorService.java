package com.liuxudong.areaecon.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.liuxudong.areaecon.entity.Indicator;

import java.util.List;

/**
 * 指标业务接口。模式和 RegionService 完全一样，不再重复解释。
 */
public interface IndicatorService extends IService<Indicator> {

    /**
     * 按分类查指标列表。category 传 null 表示查全部。
     */
    List<Indicator> listByCategory(String category);

    /**
     * 查出所有不重复的分类（给前端做下拉框）。
     */
    List<String> listCategories();
}

package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.entity.Indicator;
import com.liuxudong.areaecon.service.IndicatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标查询接口。写法和 RegionController 完全一致，这里不重复解释。
 *
 * 它存在的意义：前端的"分类下拉框"和"指标列表"都靠它，
 * 而且 /api/stat/category-summary 的 category 参数值，就来自 /categories。
 */
@RestController
@RequestMapping("/api/indicator")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    /**
     * GET /api/indicator/list                → 全部 18 个指标
     * GET /api/indicator/list?category=工业   → 只看工业分类
     */
    @GetMapping("/list")
    public Result<List<Indicator>> list(@RequestParam(required = false) String category) {
        return Result.ok(indicatorService.listByCategory(category));
    }

    /**
     * GET /api/indicator/categories
     * → ["投资","民生","综合经济","工业","财政"]（5 个分类）
     *
     * 前端拿它渲染下拉框，这样以后数据库里加了新分类，前端不用改代码。
     */
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        return Result.ok(indicatorService.listCategories());
    }
}

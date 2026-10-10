package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.service.StatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 统计接口 —— 本项目**技术含量最高**的一段，也是简历上要重点讲的部分。
 *
 * 前面的 Region 增删改查是"基本功"，说实话面试官不太感兴趣。
 * 从这里开始是"数据加工"：把 10 万行明细汇总成一张大屏能直接画的表。
 */
@RestController
@RequestMapping("/api/stat")
public class StatController {

    private final StatService statService;

    public StatController(StatService statService) {
        this.statService = statService;
    }

    /**
     * 按指标分类汇总各区域的值。
     *
     * 例子：
     *   GET /api/stat/category-summary?category=工业&periodType=Y&periodCodes=2024,2025,2026
     *
     * 含义：把"工业"这个分类下、2024~2026 三年的指标值，按区域加总。
     * 大屏上那种"各地区工业增加值排行"就是这么来的。
     *
     * 【参数说明】
     *   category     必填，指标分类。可选值可以调 /api/indicator/categories 拿
     *   periodType   选填，默认 Y（年）。Y 年 / Q 季 / M 月
     *   periodCodes  必填，逗号分隔的周期编码，如 "2024,2025,2026"
     *
     * ⚠️ 注意 periodCodes 是"周期编码"，不是年份——
     *    年周期编码就是 "2024"，季是 "2024Q1"，月是 "2024-01"。
     *    传错格式不会报错，只是查不到数据（返回空数组）。这是接口设计上常见的坑，
     *    真实项目里应该做成枚举或字典，并在文档里写清格式。
     */
    @GetMapping("/category-summary")
    public Result<List<RegionMetricStatVO>> categorySummary(
            @RequestParam String category,
            @RequestParam(defaultValue = "Y") String periodType,
            @RequestParam String periodCodes) {

        // 参数校验：@RequestParam 上的 @NotBlank 需要类上加 @Validated 才生效。
        // 我们这个项目没开，所以手动校验一下。
        // 空字符串进来会导致 SQL 里 category = '' 查不到东西，
        // 与其静默返回空数组让前端困惑，不如直接告诉他参数不对。
        List<String> codeList = parsePeriodCodes(category, periodCodes);
        return Result.ok(statService.categorySummary(category, periodType, codeList));
    }

    /**
     * 按周期看趋势 —— 大屏折线图。
     *
     * 例子：
     *   GET /api/stat/trend?category=工业&periodType=Y&periodCodes=2024,2025,2026
     *   → [{"periodCode":"2024","totalValue":...}, {"periodCode":"2025",...}, ...]
     *
     * ⚠️ 这个接口现在是**慢的**（49 万行实测约 123ms）。不是写错了，
     *    是故意留给第 6-8 天做索引优化实验的靶子。
     *    你现在要记住它的两个特征：
     *      ① 查询里没有 region_code
     *      ② EXPLAIN 显示 type=ALL，扫描 48 万行
     *    第 6-8 天我们加一个索引把它治好，前后对比数字就是你简历上的亮点。
     */
    @GetMapping("/trend")
    public Result<List<PeriodMetricStatVO>> trend(
            @RequestParam String category,
            @RequestParam(defaultValue = "Y") String periodType,
            @RequestParam String periodCodes) {

        List<String> codeList = parsePeriodCodes(category, periodCodes);
        return Result.ok(statService.categoryTrend(category, periodType, codeList));
    }

    /**
     * 两个接口共用的参数校验 + 解析。
     *
     * 【为什么不把这段复制两遍？】
     * 复制两遍的话，以后要改规则（比如限制最多查 12 个周期）就得改两处，
     * 漏掉一处就是 bug。**同一个规则只写一次**，这是最基本的代码卫生。
     */
    private List<String> parsePeriodCodes(String category, String periodCodes) {
        // 参数校验：@RequestParam 上的 @NotBlank 需要类上加 @Validated 才生效。
        // 我们这个项目没开，所以手动校验一下。
        // 空字符串进来会导致 SQL 里 category = '' 查不到东西，
        // 与其静默返回空数组让前端困惑，不如直接告诉他参数不对。
        if (category == null || category.isBlank()) {
            throw new BizException("category（指标分类）不能为空");
        }
        if (periodCodes == null || periodCodes.isBlank()) {
            throw new BizException("periodCodes（周期编码）不能为空，如 2024,2025,2026");
        }

        // "2024,2025,2026" → ["2024","2025","2026"]
        // trim + 过滤空串，是为了容忍用户写成 "2024, 2025," 这种带空格和多余逗号的情况。
        return Arrays.stream(periodCodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}

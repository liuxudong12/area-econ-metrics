package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionRankVO;
import com.liuxudong.areaecon.controller.vo.RegionYoyVO;
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
 * 从这里开始是"数据加工"：把 49 万行明细汇总成一张大屏能直接画的表。
 *
 * ============================================================================
 * Day 6 结束时的接口全景（四个接口，一个演进过程）
 * ============================================================================
 *
 *   /api/stat/category-summary   按区域汇总    44.3ms → 9.52ms   ← Day 6 改造（去 JOIN）
 *   /api/stat/trend              按周期汇总   133.45ms → 9.66ms  ← Day 5 优化（加索引）
 *   /api/stat/top-regions        区域排行 TOP N                  ← Day 6 新增（窗口函数）
 *   /api/stat/yoy                同比 / 环比                     ← Day 6 新增（条件聚合）
 *
 * 【这四个接口放在一起看，是一条完整的"数据加工"能力链】
 *   趋势图（按周期看走势）→ 排行榜（谁是第一）→ 同比（比去年好还是差）
 *   —— 真实大屏上翻来覆去就是这三种图。你能把这三个都自己写出来，
 *   面试聊到"你做过什么数据加工"就不会只会说"我写了个 CRUD"。
 */
@RestController
@RequestMapping("/api/stat")
public class StatController {

    /** 周期类型白名单。放在类级别当常量，避免每次调用都重新建集合 */
    private static final List<String> PERIOD_TYPES = List.of("Y", "Q", "M");

    /** topN 的上限。防止有人传个 1000000 进来让 MySQL 白排序 */
    private static final int MAX_TOP_N = 100;

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
     * 【Day 6 的变化：从外面看，什么都没变】
     * 参数没变、返回值结构没变、业务含义没变，只有耗时从 44.3ms 变成 9.52ms。
     * 这就是"优化"该有的样子 —— **改内部实现，不动对外契约**。
     * 如果一次优化要逼着前端改代码，那大概率是设计出了问题。
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
        validatePeriodType(periodType);
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
     * ✅ Day 5 已把它从 133.45ms 优化到 9.66ms（加 idx_tpi 索引，扫描行数 48.9 万 → 5,418）。
     *    这段经历现在是你简历上最硬的一个数字，具体怎么讲的模板见 docs/00-第5天操作手册.md。
     */
    @GetMapping("/trend")
    public Result<List<PeriodMetricStatVO>> trend(
            @RequestParam String category,
            @RequestParam(defaultValue = "Y") String periodType,
            @RequestParam String periodCodes) {

        validatePeriodType(periodType);
        List<String> codeList = parsePeriodCodes(category, periodCodes);
        return Result.ok(statService.categoryTrend(category, periodType, codeList));
    }

    /**
     * 【Day 6 新增】区域排行 TOP N —— 大屏"排行榜"。
     *
     * 例子：
     *   GET /api/stat/top-regions?category=工业&periodType=Y&periodCodes=2024,2025,2026&topN=10
     *
     * 返回：
     *   [{"regionCode":"430806","regionName":"张家界第6区","totalValue":67757.3316,
     *     "rankNo":1,"share":0.71}, ...]
     *
     * 【和 category-summary 有什么关系？】
     * 数据来源完全一样，区别在"结果怎么加工"：
     *   category-summary → 全量 150 个区域，只有 code / 名称 / 总值
     *   top-regions      → 只要前 N 名，而且多给"名次"和"占比"
     *
     * 【为什么 topN 要设上限、要在这里校验？】
     * 这是接口的"入口守门"职责。校验应该尽量靠近入口，理由：
     *   ① 越早拦下，浪费的计算越少（一个非法参数不该让它跑到 SQL 里去）；
     *   ② 报错信息能带上下文（"topN 最大 100，本次传入 5000"），比数据库抛的语法错友好得多；
     *   ③ Controller 是唯一知道"HTTP 参数长什么样"的地方，业务层不该关心这个。
     *
     * ⚠️ 一个设计选择：topN 超限时，是"报错"还是"自动截断成 100"？
     *    我选了报错。因为**静默改用户的参数是更坏的行为**——
     *    前端传了 500 以为拿到 500 条，实际只有 100 条，这种 bug 排查起来最费劲。
     *    宁可让他立刻看到错误。这是"fail fast"原则。
     */
    @GetMapping("/top-regions")
    public Result<List<RegionRankVO>> topRegions(
            @RequestParam String category,
            @RequestParam(defaultValue = "Y") String periodType,
            @RequestParam String periodCodes,
            @RequestParam(defaultValue = "10") Integer topN) {

        validatePeriodType(periodType);
        List<String> codeList = parsePeriodCodes(category, periodCodes);

        if (topN == null || topN < 1) {
            throw new BizException("topN 必须是大于 0 的整数");
        }
        if (topN > MAX_TOP_N) {
            throw new BizException("topN 最大 " + MAX_TOP_N + "，本次传入 " + topN);
        }

        return Result.ok(statService.topRegions(category, periodType, codeList, topN));
    }

    /**
     * 【Day 6 新增】各区域的同比（也能当月环比用）。
     *
     * 例子：
     *   # 年同比：2025 年 vs 2024 年
     *   GET /api/stat/yoy?category=工业&periodType=Y&basePeriod=2024&currentPeriod=2025
     *
     *   # 月环比：2026-03 vs 2026-02
     *   GET /api/stat/yoy?category=工业&periodType=M&basePeriod=2026-02&currentPeriod=2026-03
     *
     * 返回：
     *   [{"regionCode":"430102","regionName":"长沙第2区",
     *     "currentValue":..., "baseValue":..., "growth":..., "growthRate":12.34}, ...]
     *   其中 growthRate 可能为 null —— 表示"基期没有数据，增长率无从计算"，前端显示 "--"。
     *
     * 【一个业务校验：两个周期不能是同一个】
     * 如果 basePeriod = currentPeriod，算出来增长率永远是 0%，
     * 看起来"正常"但毫无意义 —— 这种"不会报错但结果没意义"的参数最危险，
     * 一定要在入口挡掉。**宁可报错，也不要返回一个看起来对但其实错的结果。**
     */
    @GetMapping("/yoy")
    public Result<List<RegionYoyVO>> yoy(
            @RequestParam String category,
            @RequestParam(defaultValue = "Y") String periodType,
            @RequestParam String basePeriod,
            @RequestParam String currentPeriod) {

        validatePeriodType(periodType);
        requireText(category, "category（指标分类）");
        requireText(basePeriod, "basePeriod（基期，被比较的那一期）");
        requireText(currentPeriod, "currentPeriod（当期，要算的这一期）");

        if (basePeriod.trim().equals(currentPeriod.trim())) {
            throw new BizException("基期和当期不能是同一个周期，否则增长率恒为 0，没有意义");
        }

        return Result.ok(statService.regionYoy(category, periodType, basePeriod.trim(), currentPeriod.trim()));
    }

    // ==========================================================================
    // 下面是三个接口共用的参数校验与解析
    // ==========================================================================

    /**
     * 校验并解析 periodCodes。
     *
     * 【为什么不把这段复制到每个接口里？】
     * 复制的话，以后要改规则（比如限制最多查 12 个周期）就得改好几处，
     * 漏掉一处就是 bug。**同一个规则只写一次**，这是最基本的代码卫生。
     *
     * "2024,2025,2026" → ["2024","2025","2026"]
     * trim + 过滤空串，是为了容忍用户写成 "2024, 2025," 这种带空格和多余逗号的情况。
     */
    private List<String> parsePeriodCodes(String category, String periodCodes) {
        requireText(category, "category（指标分类）");
        requireText(periodCodes, "periodCodes（周期编码），如 2024,2025,2026");

        List<String> codes = Arrays.stream(periodCodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // 解析完还要再判一次空：如果用户传的是 ",,," 这种，split+filter 之后就是空列表，
        // 拿去拼 SQL 会变成 `IN ()`，属于非法语法。**校验要校验"处理之后"的结果，不是原始输入。**
        if (codes.isEmpty()) {
            throw new BizException("periodCodes 解析后为空，请检查格式，如 2024,2025,2026");
        }
        return codes;
    }

    /**
     * 【Day 6 顺手加的加固】周期类型白名单校验。
     *
     * 【为什么要做白名单】
     * periodType 只有一个字符，写错太容易了：把 "Y" 写成 "y"、"年"、"years"，
     * SQL 里就是 `period_type = 'y'` —— **不报错，返回空数组**。
     * 调用方看到空数据，第一反应是"没数据啊"，而不是"我参数写错了"，能debug半天。
     *
     * 【继续传 0 条数据下去，还是立刻报错？】
     * 立刻报错。理由只有一个：**空结果和二义参数，哪个更好排查？**
     * 答案是后者 —— 报错会明确指出"你传的值不在允许范围里"，
     * 而静默返回空数组把定位问题的成本全推给了调用方。
     *
     * 这就是所谓的"**fail fast**"：让错误在最靠近源头的地方暴露出来。
     * 真实项目里这类校验通常用枚举 + 全局校验器做，这里手写一遍是为了让你看清原理。
     */
    private void validatePeriodType(String periodType) {
        if (periodType == null || !PERIOD_TYPES.contains(periodType.trim())) {
            throw new BizException("periodType 只能是 Y（年）/ Q（季）/ M（月），本次传入：" + periodType);
        }
    }

    /**
     * 通用的"必填文本"校验。抽出来是因为它有 4 个调用点，
     * 且以后可能再加规则（比如长度限制、字符集限制）—— 改一处就够。
     */
    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BizException(fieldName + " 不能为空");
        }
    }
}

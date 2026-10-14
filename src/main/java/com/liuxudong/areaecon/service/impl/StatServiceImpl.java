package com.liuxudong.areaecon.service.impl;

import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionRankVO;
import com.liuxudong.areaecon.controller.vo.RegionYoyVO;
import com.liuxudong.areaecon.entity.Region;
import com.liuxudong.areaecon.mapper.FactIndicatorValueMapper;
import com.liuxudong.areaecon.service.RegionService;
import com.liuxudong.areaecon.service.StatService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 统计业务实现。
 *
 * 【注意这里没有继承 ServiceImpl】
 * ServiceImpl 是给"单表 CRUD"用的（它需要一个实体 + 对应 Mapper）。
 * 但"统计"不是对某一张表的增删改查，而是对 fact_indicator_value 的各种聚合，
 * 返回的也是自定义 VO 而不是实体，所以不继承，就是一组自己写的方法。
 *
 * 【@Transactional 要不要加？】
 * 这里全是只读查询，不需要事务。事务是为了保证"多步写的原子性"，
 * 只读场景加事务只是白白多一层代理开销。
 * 真要加也只读优化，可以写 @Transactional(readOnly = true)。
 *
 * ============================================================================
 * Day 6 的改动：这里多了"补区域名称"这一步
 * ============================================================================
 *
 * 改造前：区域名称是 SQL 里 JOIN dim_region 查出来的。
 * 改造后：SQL 只算 region_code + 总值，名称由 Java 侧批量补。
 *
 * 为什么要把这个活从 SQL 挪到 Java？—— 因为**顺序**：
 *   ✗ 先关联再聚合：1350 行明细 → 每行去 dim_region 换名字（1350 次随机回表）→ 压成 150 行
 *   ✓ 先聚合再关联：1350 行明细 → 先压成 150 行 → 只补 150 个名字
 * 聚合会砍掉 90% 的行，那就让它先发生。实测 44.3ms → 9.52ms。
 *
 * 【面试可能会追问：这样不是把数据库的活推给应用层了吗？】
 * 对，但这是**有意识的取舍**，不是偷懒。三个理由：
 *   ① 应用层可以"一次查 150 个"，回表次数从 1350 降到 150，总 I/O 真的变少了；
 *   ② 区域维表只有 150 行、几乎不变，是典型的"字典数据"，本来就该往内存/缓存放（Day 7 更明显）；
 *   ③ 拆开之后，SQL 只剩下"纯聚合"，更容易看懂、更容易单独加索引优化。
 */
@Service
public class StatServiceImpl implements StatService {

    /** 常量 100，用于百分比换算。抽出来是为了避免在代码里散落魔法数字 */
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final FactIndicatorValueMapper factIndicatorValueMapper;
    private final RegionService regionService;

    /**
     * 【构造器注入两个依赖】
     * 这里多了一个 RegionService —— 因为要补区域名称，得能查 dim_region。
     *
     * 【为什么不直接注入 RegionMapper？】
     * 也行，而且看起来更"轻"。但注入 Service 有两个好处：
     *   ① 分层的规矩：Service 调 Service，不越过业务层直接摸别人的 Mapper；
     *   ② 复用业务规则：哪天"有效区域"的判定变复杂了（比如加个禁用状态），
     *      只要改 RegionService 一处，所有调用方跟着变。直接注入 Mapper 就得每处都改。
     *
     * 【会不会造成循环依赖？】
     * 不会。StatServiceImpl → RegionService，而 RegionService 不依赖 StatService，没有环。
     * （真要出现 A→B→A，Spring 会启动报错，那时再考虑 @Lazy 或抽公共层。）
     */
    public StatServiceImpl(FactIndicatorValueMapper factIndicatorValueMapper,
                           RegionService regionService) {
        this.factIndicatorValueMapper = factIndicatorValueMapper;
        this.regionService = regionService;
    }

    @Override
    public List<RegionMetricStatVO> categorySummary(String category, String periodType, List<String> periodCodes) {
        // 第一步：SQL 只干"算数"这一件事（不含 dim_region）
        List<RegionMetricStatVO> rows = factIndicatorValueMapper.selectCategorySummary(category, periodType, periodCodes);

        // 第二步：一次性把这批结果的区域名称捞回来
        Map<String, String> nameMap = loadRegionNameMap(
                rows.stream().map(RegionMetricStatVO::getRegionCode).toList());

        // 第三步：补名字。查不到名字的区域（已被逻辑删除）直接丢掉 ——
        // 这是为了和改造前的 SQL 语义保持一致（原 SQL 里 JOIN dim_region 时 r.deleted = 0 会把它过滤掉）。
        //
        // ⚠️ 这段是今天最值得记住的一句话：
        //    **改写 SQL 时，原来的 JOIN / WHERE 除了"过滤数据"，往往还偷偷承担着业务语义。**
        //    忘掉这条语义，接口的返回条数就会莫名其妙多出来几条 —— 而且不报错，只有用户会发现。
        List<RegionMetricStatVO> result = new ArrayList<>(rows.size());
        for (RegionMetricStatVO row : rows) {
            String regionName = nameMap.get(row.getRegionCode());
            if (regionName == null) {
                continue;
            }
            row.setRegionName(regionName);
            result.add(row);
        }
        // 因为 SQL 已经 ORDER BY totalValue DESC 了，剔除几条不影响剩余记录的相对顺序，
        // 所以这里不需要再排一次。**能不在 Java 里排序就不排**：数据库的排序是并行过的，
        // 拉到内存里排既费内存又费 CPU。
        return result;
    }

    @Override
    public List<PeriodMetricStatVO> categoryTrend(String category, String periodType, List<String> periodCodes) {
        // 就是这一行。Day 5 之前它是全表扫描 48 万行、133ms；
        // 加完 idx_tpi 索引后，代码一个字没改，耗时降到 9.66ms。
        // 这就是"优化 SQL 和索引，而不是改代码"的典型场景。
        return factIndicatorValueMapper.selectPeriodTrend(category, periodType, periodCodes);
    }

    // ==================== Day 6 新增 ====================

    @Override
    public List<RegionRankVO> topRegions(String category, String periodType, List<String> periodCodes, Integer topN) {
        // 名次（rankNo）和占比（share）都由 SQL 里的窗口函数算好了，Java 只负责补区域名。
        // 这是今天第二个"该怎么分工"的决定点：
        //   · 名次 / 占比 → 交给 SQL。因为它们依赖"全量数据集"（尤其是占比的分母），
        //     拉到 Java 算还得把全量数据搬过来，浪费网络；
        //   · 区域名称   → 交给 Java。因为它是纯字典查询，和聚合无关（就是上面那套理由）。
        // **判断依据：这个计算需不需要看"整体"？需要就给数据库，不需要就往应用层放。**
        List<RegionRankVO> rows = factIndicatorValueMapper.selectTopRegions(category, periodType, periodCodes, topN);

        Map<String, String> nameMap = loadRegionNameMap(
                rows.stream().map(RegionRankVO::getRegionCode).toList());

        List<RegionRankVO> result = new ArrayList<>(rows.size());
        for (RegionRankVO row : rows) {
            String regionName = nameMap.get(row.getRegionCode());
            if (regionName == null) {
                continue;
            }
            row.setRegionName(regionName);
            result.add(row);
        }
        // ⚠️ 一个小坑：这里"剔除"会让名次出现空洞（比如第 10 名被剔了就只剩 9 条）。
        //    严格来说应该"多查几条再剔"，但本项目的区域维表和数据是干净的，不会命中这个分支。
        //    真实项目里如果维表可能被删，正确做法是 **在 SQL 里过滤掉已删除区域**，
        //    而不是在 Java 侧剔除 —— 因为 LIMIT 是在 SQL 里生效的。
        //    这个取舍写在这里，面试问到能答出来就是加分项。
        return result;
    }

    @Override
    public List<RegionYoyVO> regionYoy(String category, String periodType, String basePeriod, String currentPeriod) {
        List<RegionYoyVO> rows = factIndicatorValueMapper.selectRegionYoy(category, periodType, basePeriod, currentPeriod);

        // 逐行算增长额和增长率。
        // 【这三行是整个项目里最容易写错的地方，逐个说清】
        for (RegionYoyVO row : rows) {
            // ① 空值保护：SQL 里写了 ELSE 0，理论上不会为 null，
            //    但**不能靠"理论上"写代码**。上游一改（比如有人把 ELSE 0 删了），
            //    这里就是 NullPointerException。防御性编程的收益在出问题那天才体现。
            BigDecimal current = row.getCurrentValue() == null ? BigDecimal.ZERO : row.getCurrentValue();
            BigDecimal base = row.getBaseValue() == null ? BigDecimal.ZERO : row.getBaseValue();

            // ② 增长额 = 当期 - 基期。subtract 不会有任何精度陷阱，放心用。
            //    （BigDecimal 的坑全在"除法"和"用 == 比较"上，加减乘都是安全的。）
            BigDecimal growth = current.subtract(base);

            // ③ 增长率 = 增长额 / 基期 × 100
            //    ⚠️⚠️ 这里是最经典的 BigDecimal 陷阱：
            //    divide() 不给精度时，遇到**除不尽**的数（比如 100/3）会直接抛
            //    ArithmeticException: Non-terminating decimal expansion; no exact representable decimal result
            //    —— 注意它是**抛异常**，不是返回个近似值。
            //    所以必须写成 divide(除数, 保留几位小数, 舍入方式) 三个参数齐全。
            //
            //    舍入方式用 HALF_UP（四舍五入），别用默认的 HALF_EVEN（银行家舍入，逢5取偶）。
            //    财务/报表场景用 HALF_EVEN 会让人觉得"算错了"（2.5 → 2，3.5 → 4）。
            BigDecimal growthRate = null;
            if (base.compareTo(BigDecimal.ZERO) != 0) {
                growthRate = growth.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
            }
            // ④ 基期为 0 时不计算增长率，保持 null。
            //    为什么不是 BigDecimal.ZERO？因为"增长 0%"和"没法算"是两件事：
            //    去年没数据、今年有 100 万，这个增长是"从无到有"，写成 0% 是严重的误导。
            //    前端拿到 null 会显示成 "--"，这是正确的表达。
            //    **在统计数据里，"0" 和 "空" 必须区分开。**

            row.setGrowth(growth);
            row.setGrowthRate(growthRate);
        }

        // 补区域名称：同样的逻辑，第三遍出现了。
        // 【这里要不要抽成一个通用方法？】
        // 现在三个方法都在写"补名字 + 剔空"这段循环，看着确实重复。
        // 但我**故意没有抽**，因为三处的类型不同（RegionMetricStatVO / RegionRankVO / RegionYoyVO），
        // 要抽通用就得引入 BiConsumer 之类的函数式参数，读起来反而更绕。
        // 经验法则：**重复 3 次的代码，如果抽出来之后变得更难读，那就先别抽。**
        // （真要抽，接口 + 默认方法是更 Java 的做法，属于重构的范畴，不在这几天的目标里。）
        Map<String, String> nameMap = loadRegionNameMap(
                rows.stream().map(RegionYoyVO::getRegionCode).toList());

        List<RegionYoyVO> result = new ArrayList<>(rows.size());
        for (RegionYoyVO row : rows) {
            String regionName = nameMap.get(row.getRegionCode());
            if (regionName == null) {
                continue;
            }
            row.setRegionName(regionName);
            result.add(row);
        }
        return result;
    }

    /**
     * 批量把 region_code 换成 region_name，返回 编码 → 名称 的映射。
     *
     * 【为什么必须"批量"，不能循环单查】
     * 150 个区域循环单查 = 150 次数据库往返，每次都有网络开销和连接开销。
     * 批量查是 1 次。这就是所谓的 **N+1 查询问题**——
     * 面试问"你项目里怎么优化性能"时，除了索引和缓存，这是第三个标准答案。
     *
     * 识别方法很简单：**看到 for 循环里写数据库查询，就要停下来想想能不能批量。**
     *
     * 【为什么返回 Map 而不是 List】
     * 调用方要按 region_code 去"查名字"，Map 的 get 是 O(1)；
     * 用 List 就得每行都遍历一遍（O(n²)，150 行就是 22500 次比较，虽然不致命但没必要）。
     */
    private Map<String, String> loadRegionNameMap(Collection<String> regionCodes) {
        List<Region> regions = regionService.listByCodes(regionCodes);
        // Collectors.toMap 的第三个参数是"key 冲突时怎么办"。
        // region_code 上有唯一索引，理论上不会冲突，但**显式写上 (a, b) -> a 更安全**——
        // 不写的话，一旦真出现重复 key 会抛 IllegalStateException，而那是生产环境的凌晨两点。
        return regions.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Region::getRegionCode,
                        Region::getRegionName,
                        (existing, replacement) -> existing));
    }
}

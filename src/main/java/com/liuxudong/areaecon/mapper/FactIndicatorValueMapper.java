package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liuxudong.areaecon.controller.vo.FactExportQuery;
import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionRankVO;
import com.liuxudong.areaecon.controller.vo.RegionYoyVO;
import com.liuxudong.areaecon.entity.FactIndicatorValue;
import com.liuxudong.areaecon.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 指标值事实表 Mapper。
 *
 * 【什么时候需要手写 SQL？】
 * 单表增删改查 → 用 BaseMapper 就够了，一行 SQL 都不用写。
 * 但下面这种"多表 JOIN + GROUP BY 聚合"，BaseMapper 表达不了，必须自己写。
 *
 * 手写 SQL 有两种写法：
 *   ① 注解：@Select("SELECT ...") 直接写在方法上 —— 适合很短的一两句 SQL
 *   ② XML：写到 resources/mapper/XxxMapper.xml —— 适合长 SQL、动态条件（if / foreach）
 *
 * 我们这个聚合查询有条件判断和 IN 列表，用 XML 更清楚，所以走 ②。
 * 但注意：方法定义必须写在这里（Mapper 接口），XML 只是它的"实现"。
 *
 * 【XML 是怎么被找到的？】
 * 靠两点，缺一不可：
 *   ① application.yml 里的 mapper-locations 已指向 resources/mapper 下的所有 xml
 *   ② XML 里的 mapper namespace 必须**一字不差**等于这个接口的全限定名
 * 少了任何一条，启动就会报 "Invalid bound statement (not found)"。
 */
@Mapper
public interface FactIndicatorValueMapper extends BaseMapper<FactIndicatorValue> {

    /**
     * 按"指标分类"汇总各区域的指标值 —— 大屏最典型的一个查询。
     *
     * 举例：查"工业"这个分类下，2024/2025/2026 三年，每个区域的总值。
     *
     * 【Day 6 改造：这里少 JOIN 了一张表】
     * 改造前它是 JOIN 三张表（fact + dim_indicator + dim_region），实测 44.3ms。
     * 改造后去掉了 dim_region，SQL 只负责"算数"，区域名称由 Service 侧批量补上。
     *
     * 为什么这样能快 4.6 倍？——把关联顺序从"先关联后聚合"改成"先聚合后关联"：
     *
     *   ✗ 改造前：1350 行明细 → 每行都去 dim_region 换名字（1350 次随机回表）→ 再压成 150 行
     *   ✓ 改造后：1350 行明细 → 先压成 150 行 → 只补 150 个名字（150 次）
     *
     * 聚合本来就砍掉 90% 的行，那就该让它**先发生**。
     *
     * 【@Param 的作用】
     * XML 里用 #{category} 取值时，MyBatis 需要一个"名字 → 值"的映射。
     * 方法有多个参数时，必须加 @Param("名字") 明确指定名字；
     * 不加的话 XML 里只能写 #{param1}、#{arg0} 这种，可读性极差、还容易错。
     * 只有一个参数时可以省略，但**建议一律加上**。
     *
     * @param category    指标分类，如"工业"
     * @param periodType  周期类型：Y 年 / Q 季 / M 月（对应表里的 period_type）
     * @param periodCodes 周期编码列表，如 ["2024","2025","2026"]（对应 IN (...) 查询）
     */
    @DataScope(alias = "f")
    List<RegionMetricStatVO> selectCategorySummary(@Param("category") String category,
                                                  @Param("periodType") String periodType,
                                                  @Param("periodCodes") List<String> periodCodes);

    /**
     * 按"周期"汇总某分类的指标值 —— 大屏"趋势折线图"用。
     *
     * 举例：查"工业"这个分类，每年（2024/2025/2026）全省的总值，看走势。
     *
     * ✅ Day 5 已优化：给 fact_indicator_value 补了 idx_tpi(period_type, period_code, indicator_code)，
     *    扫描行数 489,618 → 5,418，DB 耗时 133.45ms → 9.66ms。
     *    但下面这段"它当年为什么慢"的分析照样要会讲 —— 面试考的就是这个思路。
     *
     * 【为什么它比 selectCategorySummary 慢得多？—— 这句话面试能用】
     * 两个查询的表结构是一样的，区别只在于：**select 和 group by 用不用 region_code**。
     *
     *   selectCategorySummary 要按区域分组 → 执行时必然涉及 region_code
     *     → 而 uk_fact 索引的最左列正好是 region_code
     *     → 优化器能把索引"用起来" → 扫描 1,668 行
     *
     *   而这个方法只按周期分组，整个查询里**不出现 region_code**
     *     → uk_fact 索引用不上（最左列缺失，B+ 树没法定位入口）
     *     → 只能全表扫描 → 扫描 489,600 行
     *
     * 实测（49 万行）：前者 38ms，后者 123ms，差 3 倍多。
     *
     * 【这就是"索引不是有没有，而是能不能用上"的活教材】
     * 表上明明有索引，但你的 WHERE / GROUP BY 里没有索引的最左列，
     * 索引就是一张废纸。第 6-8 天我们加一个 period_type 打头的索引来治好它。
     */
    @DataScope(alias = "f")
    List<PeriodMetricStatVO> selectPeriodTrend(@Param("category") String category,
                                               @Param("periodType") String periodType,
                                               @Param("periodCodes") List<String> periodCodes);

    // ==================== Day 6 新增：两个进阶统计接口 ====================

    /**
     * TOP N 区域排行（大屏"排行榜"用）。
     *
     * 举例：查"工业"分类 2024 年，各区域按总值排名，取前 10。
     *
     * 【和 selectCategorySummary 的关系】
     * 它俩的数据来源完全一样（同一张表、同一套过滤条件、同样按区域分组），
     * 区别只在**结果怎么加工**：
     *     selectCategorySummary  只返回 code + 总值
     *     selectTopRegions       额外算出【名次】和【占比】，并且只要前 N 条
     *
     * 【Day 6 的新武器：窗口函数】
     *     RANK() OVER (ORDER BY totalValue DESC)           → 名次
     *     SUM(totalValue) OVER ()                          → 所有区域的总值（没 GROUP BY 的聚合）
     *     ROUND(totalValue / SUM(totalValue) OVER () * 100, 2)  → 占比
     *
     * 「OVER ()」里什么都不写 = 整个结果集当做一个窗口，这跟 GROUP BY 是**两码事**：
     *     GROUP BY 会把多行压成一行（行数变少）
     *     窗口函数**不改行数**，只是给每行多挂一列计算结果
     * 所以你要"分组汇总后又想看到明细"，就用窗口函数 —— 这正是排行的场景。
     *
     * ⚠️ 为什么有个子查询 t？
     *     MySQL 不允许在 WHERE / 同一层 SELECT 里直接用 SUM() 的结果做算术后再排序取前 N，
     *     所以先在子查询里把"每个区域的总值"算出来，外层再用窗口函数加工。
     *     这是 SQL 的"执行顺序"决定的（FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY），
     *     SELECT 里的别名，同一层的 ORDER BY 能用，但窗口函数里就不能直接引用聚合别名。
     *
     * @param topN 取前几名，如 10
     */
    @DataScope(alias = "f")
    List<RegionRankVO> selectTopRegions(@Param("category") String category,
                                        @Param("periodType") String periodType,
                                        @Param("periodCodes") List<String> periodCodes,
                                        @Param("topN") Integer topN);

    /**
     * 各区域的同比增长（同比 / 环比通用）。
     *
     * 举例：查"工业"分类，2025 年 vs 2024 年，每个区域的增长情况。
     *
     * 【核心技术点：条件聚合（Conditional Aggregation）】
     * 一条 SQL 同时算出"当期值"和"基期值"两个指标：
     *
     *     SUM(CASE WHEN period_code = '2025' THEN indicator_value ELSE 0 END) AS currentValue
     *     SUM(CASE WHEN period_code = '2024' THEN indicator_value ELSE 0 END) AS baseValue
     *
     * 加点缩进看清楚它在干什么：
     *     SUM(  CASE WHEN 周期 = 当期 THEN 值 ELSE 0 END  )
     *     └─ 外层还是 SUM  └─ 但只把"符合条件的那些行"的值累加起来
     *
     * 【为什么不用查两次库？】
     * 两个值来自**同一批数据、同一个 WHERE**，只是口径不同。
     * 分两次查 = 两倍 I/O + 两倍网络 + 两次结果要自己在 Java 里按 region_code 拼起来对不齐的风险。
     * 一次扫表同时算出多个口径，是数据统计里非常常用的手法。
     *
     * ⚠️ ELSE 0 不能省：如果某个区域在基期没有数据，CASE 不写 ELSE 会返回 NULL，
     *    整个 SUM 遇到 NULL 的结果取决于其他行，容易埋坑。写 ELSE 0 让语义明确。
     *
     * 【注意增长额和增长率不在这里算】
     * 它们在 Service 层用 BigDecimal 算（有除零、精度、舍入三个坑要处理）。
     * SQL 只管"取数"，业务计算放 Java —— 这样换数据库也不用改逻辑。
     */
    @DataScope(alias = "f")
    List<RegionYoyVO> selectRegionYoy(@Param("category") String category,
                                      @Param("periodType") String periodType,
                                      @Param("basePeriod") String basePeriod,
                                      @Param("currentPeriod") String currentPeriod);

    // ==================== Day 9 新增：Excel 导入的批量写入 ====================
    // ==================== Day 10 升级：INSERT → upsert（可重入） ====================

    /**
     * 批量写入指标值（upsert：有则更新、无则插入）。
     *
     * 【这条 SQL 是 Day 9 性能对比 + Day 10 幂等重试的共同基础】
     *
     * 一、性能：一条 SQL 插 N 行，而不是 N 条 SQL 各插一行
     *   同步导入 1 万行如果用逐行插入（for 循环调 BaseMapper.insert），
     *   就是 1 万次：
     *     应用 → 网络 → MySQL 解析 SQL → 执行 → 返回 → 应用
     *   每次往返哪怕只有 0.5ms，1 万次也是 5 秒起步，实际加上索引维护会更久。
     *   而这条 SQL 一次能插 500 行 —— 1 万行只需要 20 次往返。
     *
     *   ⚠️ 为什么不是"一次插 1 万行"？三方面限制：
     *     ① MySQL 的 max_allowed_packet 默认 64MB，一条 SQL 太大会被拒；
     *     ② 单条 SQL 太长，解析和事务日志（undo/redo）都会变大，回滚成本高；
     *     ③ 一整批失败时，500 行的重试代价远小于 1 万行。
     *     500 是工程上的经验值，不是理论最优。
     *
     * 二、幂等：Day 10 新增 ON DUPLICATE KEY UPDATE
     *   Day 9 的版本是纯 INSERT，导致"分批提交后失败"的任务**永远无法重试成功**：
     *   前 10 批已经落库，重试时第 1 批就撞 uk_fact → 整批回滚 → 无限失败。
     *
     *   改成 upsert 之后，重复写入变成"重置为最新值"，跑 1 遍和跑 100 遍结果一致。
     *   这才是"指数退避重试"能成立的前提：
     *       **"可重试"的前提是"可重入"。**
     *
     * 三、⚠️ 返回值的含义变了，不能当行数用
     *   纯 INSERT：返回 500 = 插了 500 行。
     *   upsert 的受影响行数是 MySQL 的混合计数：
     *      插入 1 行 → 1
     *      更新 1 行 → 2（逻辑上删掉旧行 + 插新行，所以算两次）
     *      命中但值没变 → 0
     *   批量时这些数全混在一起累加，**无法反推真实行数**。
     *   所以调用方的 `written` 是自己按"提交成功的批次数 × 批大小"手数的，
     *   绝对不要用这个返回值记账 —— 会算出一个莫名其妙的数字。
     *
     * 四、⚠️ 唯一索引不含 deleted，所以 SET 里必须显式写 deleted = 0
     *   否则一条被逻辑删除的记录再导入会永远保持 deleted=1：
     *   SQL 不报错、接口返回成功、用户却查不到数据。属于"静默错误"。
     *
     * 五、原子性边界（这条没变）
     *   这 500 行要么全成功、要么全失败（同一条 SQL 就是一个隐式事务）。
     *   upsert 只是让"重复"不再导致失败，并不改变这一点。
     *
     * @param list 待写入的记录，调用方负责保证它不为空（空列表会拼出非法 SQL）
     * @return 受影响行数（插入计 1、更新计 2、无变化计 0，**已混算，不可当行数用**）
     */
    int insertBatch(@Param("list") List<FactIndicatorValue> list);

    // ==================== Day 12 新增：导出（游标分页） ====================

    /**
     * 按筛选条件数一共多少行 —— 导出前的"可行性检查"。
     *
     * 【为什么导出前必须先数一遍】
     * HTTP 响应一旦开始写就不能回头。如果闷头开始导一个 300 万行的结果，
     * 中途撞上 Excel 的 1,048,576 行上限或者 OOM，用户只会拿到一个**损坏的文件**，
     * 而且拿不到任何错误提示 —— 他会以为是网络问题，反复重试，把服务器再拖一遍。
     *
     * 所以顺序必须是：先 COUNT → 超上限就直接返回 JSON 错误 → 通过才写文件。
     * 多花几十毫秒 COUNT，换来"永远不会有半截文件"。
     *
     * @param query 筛选条件（和取数用**同一个对象**，保证条件一致）
     * @return 符合条件的总行数
     */
    @DataScope(alias = "f")
    long countForExport(@Param("query") FactExportQuery query);

    /**
     * 游标分页取一页数据（keyset pagination）。
     *
     * 【和普通分页的区别 —— 这是今天最核心的一句话】
     *   普通分页 LIMIT 400000, 2000：先数出 40 万行丢掉，再取 2000 行 → 越翻越慢，O(n²)
     *   游标分页 WHERE id &gt; 400000 LIMIT 2000：直接定位，往后再取 2000 行 → 每页恒定，O(n)
     *
     * 【调用方式（Service 里就是这么写的）】
     * <pre>
     *   long lastId = 0;
     *   while (true) {
     *       List&lt;X&gt; page = mapper.selectForExportByCursor(query, lastId, 2000);
     *       if (page.isEmpty()) break;
     *       writeToExcel(page);                    // 写完立刻丢掉，不进内存
     *       lastId = page.get(page.size() - 1).getId();
     *       if (page.size() &lt; 2000) break;         // 最后一页
     *   }
     * </pre>
     * 内存里永远只有一页（2000 个对象），跟总行数无关。
     *
     * @param query  筛选条件
     * @param lastId 上一页最后一条的 id；第一页传 0
     * @param limit  每页多少条（建议 1000~5000，太小往返多、太大吃内存）
     */
    @DataScope(alias = "f")
    List<FactIndicatorValue> selectForExportByCursor(@Param("query") FactExportQuery query,
                                                     @Param("lastId") Long lastId,
                                                     @Param("limit") int limit);

    // ==================== Day 13 新增：分页（用来验证插件顺序） ====================

    /**
     * 分页查询指标值明细 —— 这个方法是专门为"验证插件顺序"加的。
     *
     * 【为什么非要加这个接口】
     * 因为前 12 天所有统计都是手写 SQL、没有用过分页插件。
     * 而"数据权限拦截器"和"分页拦截器"的**先后顺序**会直接影响 COUNT 的正确性
     * （原因写在 MybatisPlusConfig 的注释里）。没有分页接口，这个差异根本测不出来。
     *
     * 【@DataScope 这里没写 alias，为什么】
     * 因为这条 SQL 只有一张表、没有 JOIN，列名 region_code 不会歧义。
     * 此时生成的条件是 {@code region_code LIKE '4301%'}（不带表前缀）。
     * 如果以后给它加了 JOIN，就必须补上 alias，否则会报
     * "Column 'region_code' in where clause is ambiguous"。
     * **别名要不要写，取决于 SQL 里有没有第二张表 —— 不是取决于习惯。**
     *
     * 【⚠️ @Select 手写 SQL 必须自己写 deleted = 0】
     * 这条规律从第 5 天讲到第 13 天，凡是手写 SQL 都躲不掉 @TableLogic 不生效这件事。
     *
     * 【分页参数怎么传】
     * MP 规定：方法第一个参数是 {@code IPage}，且返回值是 {@code IPage} 或 {@code List}。
     * 它会自动：① 复制一条 COUNT(*) 查总数 ② 给原 SQL 加 LIMIT。
     * 这两步发生在 PaginationInnerInterceptor 里，和数据权限插件的顺序关系见配置类注释。
     */
    @DataScope
    @Select("SELECT id, region_code, indicator_code, period_code, period_type, "
            + "indicator_value, data_source, deleted, create_time, update_time "
            + "FROM fact_indicator_value WHERE deleted = 0 ORDER BY id")
    Page<FactIndicatorValue> selectFactPage(Page<FactIndicatorValue> page);
}

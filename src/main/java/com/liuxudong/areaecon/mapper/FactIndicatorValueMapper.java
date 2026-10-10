package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.controller.vo.PeriodMetricStatVO;
import com.liuxudong.areaecon.controller.vo.RegionMetricStatVO;
import com.liuxudong.areaecon.entity.FactIndicatorValue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
     * 【它涉及三张表】
     *   fact_indicator_value  取指标值（大表，10 万行）
     *   dim_indicator         按 category 筛选指标
     *   dim_region            取区域名称给前端显示
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
    List<RegionMetricStatVO> selectCategorySummary(@Param("category") String category,
                                                  @Param("periodType") String periodType,
                                                  @Param("periodCodes") List<String> periodCodes);

    /**
     * 按"周期"汇总某分类的指标值 —— 大屏"趋势折线图"用。
     *
     * 举例：查"工业"这个分类，每年（2024/2025/2026）全省的总值，看走势。
     *
     * ⚠️ 这个查询是**故意留着的"慢查询"**，第 6-8 天要优化它。
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
    List<PeriodMetricStatVO> selectPeriodTrend(@Param("category") String category,
                                               @Param("periodType") String periodType,
                                               @Param("periodCodes") List<String> periodCodes);
}

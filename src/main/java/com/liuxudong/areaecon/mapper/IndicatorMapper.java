package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.Indicator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 指标维表 Mapper。
 *
 * 【这里演示另一种手写 SQL 的方式：注解】
 * 上面那个 FactIndicatorValueMapper 用的是 XML（因为 SQL 长、有 foreach）。
 * 而下面这个 SQL 只有一行，写成 XML 反而要额外开一个文件，不划算 ——
 * 这种就适合直接用 @Select 写在方法上。
 *
 * 判断标准很简单：
 *   短、没有动态条件（if/foreach） → 注解
 *   长、有动态条件 → XML
 * 两种混用在同一个项目里是正常的，不是"不统一"。
 */
@Mapper
public interface IndicatorMapper extends BaseMapper<Indicator> {

    /**
     * 查出所有不重复的指标分类（综合经济 / 工业 / 财政 / 民生 / 投资）。
     *
     * 【为什么需要这个接口？】
     * 前端要做一个"分类下拉框"，但它事先不知道有哪些分类。
     * 与其在代码里硬编码一个数组（数据一变就得改代码、重新发版），
     * 不如让数据库自己告诉我们 —— 这就叫"数据驱动"。
     *
     * 【DISTINCT 的作用】
     * 18 个指标里，分类只有 5 种，很多指标共用同一个分类。
     * DISTINCT 去重后只会返回 5 行。
     */
    @Select("SELECT DISTINCT category FROM dim_indicator WHERE deleted = 0 ORDER BY category")
    List<String> selectCategories();
}

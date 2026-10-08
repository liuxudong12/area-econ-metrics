package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.Region;
import org.apache.ibatis.annotations.Mapper;

/**
 * 区域维表的 Mapper —— 数据访问层（DAO），只负责跟数据库打交道。
 *
 * 【你可能会觉得奇怪：为什么一个方法都没写？】
 * 因为 BaseMapper<Region> 里已经内置好了常用方法，你白拿：
 *     insert(entity)                  新增
 *     deleteById(id)                  按主键删除（逻辑删除会转成 UPDATE）
 *     updateById(entity)              按主键更新
 *     selectById(id)                  按主键查
 *     selectList(wrapper)             条件查询
 *     selectPage(page, wrapper)       分页查询
 *     selectCount(wrapper)            统计条数
 *
 * 【@Mapper 注解的作用】
 * 告诉 MyBatis "这是一个 Mapper 接口，启动时给它生成实现类"。
 * 配合 config/MybatisPlusConfig 里的 @MapperScan，启动时会被自动扫描注册。
 *
 * 【命名约定】
 * 接口名 = 实体名 + Mapper，MyBatis 默认按这个名字去找同名的 XML（RegionMapper → RegionMapper.xml）。
 * 我们今天不需要写 XML，因为全是单表查询。第 5 天做跨表聚合统计时才需要手写 XML。
 *
 * 面试常问：#{} 和 ${} 的区别？
 * 答：#{} 是预编译占位符，会生成 ? 并由 JDBC 做参数绑定，能防 SQL 注入；
 *     ${} 是字符串直接拼接，有注入风险，只在需要拼表名/字段名之类的场景才用。
 */
@Mapper
public interface RegionMapper extends BaseMapper<Region> {
}

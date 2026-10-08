package com.liuxudong.areaecon.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liuxudong.areaecon.entity.Region;
import com.liuxudong.areaecon.mapper.RegionMapper;
import com.liuxudong.areaecon.service.RegionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 区域业务实现。
 *
 * 【两个泛型是什么】
 * ServiceImpl<RegionMapper, Region>
 *               ↑ Mapper 类型   ↑ 实体类型
 * 父类靠这两个泛型知道"该用哪个 Mapper、操作哪个实体"，从而把 save/getById/list 等方法实现出来。
 *
 * 【@Service 的作用】
 * 把这个类注册成 Spring 容器里的 Bean，这样 Controller 才能通过构造器注入拿到它。
 *
 * 【lambdaQuery() 是什么 —— 今天最值得学的一个东西】
 * 这是 MyBatis-Plus 的条件构造器（QueryWrapper 的 lambda 写法）。
 * 对比一下传统写法，感受差别：
 *
 *   传统（字符串，字段名写错编译不报错，运行时才炸）：
 *       QueryWrapper<Region> qw = new QueryWrapper<>();
 *       qw.eq("region_level", level).orderByAsc("sort");
 *       mapper.selectList(qw);
 *
 *   lambda（字段名是方法引用，写错直接编译不过）：
 *       lambdaQuery().eq(Region::getRegionLevel, level).orderByAsc(Region::getSort).list();
 *
 * lambda 写法的好处：
 *   ① 编译期就能发现字段名写错 —— 这是最大的价值，重构改字段名时尤其明显；
 *   ② 不会拼错列名导致线上事故。
 *
 * 【条件里的第一个参数是"开关"，这个技巧很常用】
 *       .eq(level != null, Region::getRegionLevel, level)
 * 意思是：level != null 才拼这个条件，为 null 就跳过。
 * 这样"查全部"和"按层级查"就用一个方法搞定了，不用写 if-else 两套。
 */
@Service
public class RegionServiceImpl extends ServiceImpl<RegionMapper, Region> implements RegionService {

    @Override
    public List<Region> listByLevel(Integer level) {
        return lambdaQuery()
                .eq(level != null, Region::getRegionLevel, level)   // level 为空时不加这个条件
                .orderByAsc(Region::getRegionLevel)                 // 先按层级：省 → 市 → 区县
                .orderByAsc(Region::getSort)                        // 同层级内按 sort 排
                .list();
    }

    @Override
    public IPage<Region> pageQuery(long pageNum, long pageSize, String keyword) {
        return lambdaQuery()
                // hasText：非 null 且去掉空格后还有内容，才认为关键字有效。
                // 用 like 做模糊匹配 —— 注意不要自己拼 '%' + keyword + '%'，
                // MyBatis-Plus 会自动帮你加两侧通配符并做参数绑定（防注入）。
                .like(StringUtils.hasText(keyword), Region::getRegionName, keyword)
                .orderByAsc(Region::getRegionLevel)
                .orderByAsc(Region::getSort)
                .page(new Page<>(pageNum, pageSize));   // 这一步会触发分页插件
    }

    @Override
    public Region getByCode(String regionCode) {
        // .one() 表示只取一条；如果查出多条会抛异常（MyBatis-Plus 的行为）。
        // 这里 region_code 上有唯一索引，所以最多一条，安全。
        return lambdaQuery()
                .eq(Region::getRegionCode, regionCode)
                .one();
    }
}

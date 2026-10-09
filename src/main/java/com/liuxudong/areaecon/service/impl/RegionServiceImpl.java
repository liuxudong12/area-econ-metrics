package com.liuxudong.areaecon.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.controller.vo.RegionSaveReqVO;
import com.liuxudong.areaecon.entity.Region;
import com.liuxudong.areaecon.mapper.RegionMapper;
import com.liuxudong.areaecon.service.RegionService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    // ==================== Day 3：写操作 ====================
    // 下面三个方法都加了 @Transactional(rollbackFor = Exception.class)。
    //
    // 【为什么必须写在 Service 上】
    // Spring 的事务靠动态代理实现，代理只包 Service 层的 Bean。
    // 写在 Controller 方法上：不报错、不生效（这是线上最常见的坑之一）。
    //
    // 【为什么写 rollbackFor = Exception.class】
    // 默认只对 RuntimeException / Error 回滚。写了它以后，受检异常也会回滚，更保险。
    // 不写也不影响本例（BizException 继承 RuntimeException），但写成习惯更好。
    //
    // 【@Transactional 的一个隐藏规则 —— 同类内部自调用会失效】
    // 你在本类里写 this.createRegion(...)，它走的是**原始对象**而不是代理对象，
    // 事务不会开启。要自调用还得有事务，得注入自己或用 AopContext.currentProxy()。

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRegion(RegionSaveReqVO reqVO) {
        // 第一步：业务规则校验 —— 编码不能重复。
        // 注意这里查一次数据库，表上其实已有 uk_region_code 唯一索引兜底。
        // 为什么还要查？因为唯一索引抛的是 DuplicateKeyException（系统异常），
        // 提示是英文的 SQL 报错，用户看不懂。提前查能给出人话提示。
        // 这是"业务校验给友好提示 + 数据库约束做最后防线"的双保险写法。
        long exists = lambdaQuery()
                .eq(Region::getRegionCode, reqVO.getRegionCode())
                .count();
        if (exists > 0) {
            throw new BizException("区域编码已存在：" + reqVO.getRegionCode());
        }

        // 第二步：VO → 实体。
        // BeanUtils.copyProperties(源, 目标) 会按"属性名相同"逐个复制。
        // 注意：它只复制同名的，VO 里有而实体没有的属性会被忽略。
        Region region = new Region();
        BeanUtils.copyProperties(reqVO, region);

        // 新增时 id 必须为空 —— 留着值会让 MyBatis-Plus 以为你要指定主键，破坏自增。
        region.setId(null);
        if (region.getSort() == null) {
            region.setSort(0);   // 表里 sort 是 NOT NULL，不传就给 0
        }

        // 第三步：入库。save() 由 IService 提供，底层就是 mapper.insert()。
        // 执行完后，数据库生成的自增 id 会被**回填**到 region 对象里（靠 IdType.AUTO）。
        save(region);
        return region.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRegion(RegionSaveReqVO reqVO) {
        if (reqVO.getId() == null) {
            throw new BizException("修改区域时 id 不能为空");
        }

        // 先查旧数据。除了判断"是否存在"，还为了拿到旧编码做对比。
        Region old = getById(reqVO.getId());
        if (old == null) {
            throw new BizException("区域不存在：" + reqVO.getId());
        }

        // 只有"编码真的改了"才需要查重。ne(id) 是为了排除自己 ——
        // 否则把自己排除掉，一改就报"编码已被占用"。
        if (!old.getRegionCode().equals(reqVO.getRegionCode())) {
            long exists = lambdaQuery()
                    .eq(Region::getRegionCode, reqVO.getRegionCode())
                    .ne(Region::getId, reqVO.getId())
                    .count();
            if (exists > 0) {
                throw new BizException("区域编码已被占用：" + reqVO.getRegionCode());
            }
        }

        Region update = new Region();
        BeanUtils.copyProperties(reqVO, update);
        // updateById 只会更新**非 null** 的字段（默认 NOT_NULL 策略），
        // 且会自动带上逻辑删除条件：UPDATE dim_region SET ... WHERE id = ? AND deleted = 0
        updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRegion(Long id) {
        Region old = getById(id);
        if (old == null) {
            // 不存在还报错，而不是"静默成功"。因为调用方传了错的 id，应该让他知道。
            throw new BizException("区域不存在：" + id);
        }
        // removeById 因为实体上有 @TableLogic，实际执行的是：
        //   UPDATE dim_region SET deleted = 1 WHERE id = ? AND deleted = 0
        // 数据**没有真的消失**，只是被标记了。这就是逻辑删除。
        removeById(id);
    }
}

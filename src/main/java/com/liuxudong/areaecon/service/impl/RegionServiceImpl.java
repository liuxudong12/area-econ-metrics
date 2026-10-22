package com.liuxudong.areaecon.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liuxudong.areaecon.cache.StatCacheHelper;
import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.controller.vo.RegionSaveReqVO;
import com.liuxudong.areaecon.entity.Region;
import com.liuxudong.areaecon.mapper.RegionMapper;
import com.liuxudong.areaecon.service.RegionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    private static final Logger log = LoggerFactory.getLogger(RegionServiceImpl.class);

    /**
     * Day 8 新增：区域写完要清统计缓存。
     *
     * 【为什么区域服务要管统计缓存？这不是越界了吗？】
     * 因为**数据的所有者最清楚自己什么时候被改了**。
     * 如果反过来让统计侧去"猜"区域什么时候变了（比如定时轮询），既慢又不可靠。
     * 现实中的做法也一样：谁改数据谁负责让缓存失效（Cache Aside 模式的基本约定）。
     *
     * 更彻底的解耦方案是**订阅数据库变更日志（Canal / Debezium）**，
     * 应用完全不感知缓存，由独立组件监听 binlog 去删。见手册里的对比。
     *
     * 注意依赖方向：RegionServiceImpl → StatCacheHelper，而 StatCacheHelper 不依赖任何业务类，
     * 所以不会出现循环依赖。（如果哪天想让 StatCacheHelper 反过来依赖 RegionService，那就是设计出问题了。）
     */
    private final StatCacheHelper cache;

    public RegionServiceImpl(StatCacheHelper cache) {
        this.cache = cache;
    }

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

    @Override
    public List<Region> listByCodes(Collection<String> regionCodes) {
        // 空集合要提前挡住：MyBatis-Plus 的 in() 遇到空集合会拼出一个不合法/恒假的 IN 子句，
        // 不同版本行为还不一致（有的直接拼 `IN ()` 报语法错）。
        // **凡是要拼 IN 的地方，先判空**——这是一条能省掉很多故障的经验。
        if (regionCodes == null || regionCodes.isEmpty()) {
            return Collections.emptyList();
        }
        // 同样是 lambdaQuery()，框架会自动带上 deleted = 0（因为实体上有 @TableLogic）。
        // 这一点很重要：**它等价于原来 SQL 里的 `AND r.deleted = 0`**，
        // 所以改造之后"被逻辑删除的区域不出现在统计结果里"这个语义没有变。
        return lambdaQuery()
                .in(Region::getRegionCode, regionCodes)
                .list();
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

        // 【Day 8】新增区域后清统计缓存。
        // 严格说这一步"多数时候可以省"：新区域在 fact 表里还没有数据，
        // 所以已有的统计结果不会变。但有个真实的边界情况：
        //   以前把某个区域逻辑删除了（统计数据里它消失了），现在又用**同一个 region_code**
        //   建回来 —— 这时 fact 表里的历史数据会立刻重新出现在统计结果里，缓存必须清。
        // 清一次的成本是"几个 key 的 SCAN + 下次请求慢几十毫秒"，
        // 漏清的代价是"用户看到错误的数字且没人知道"。**这个账很容易算。**
        evictStatCacheAfterCommit("新增区域 " + region.getRegionCode());
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

        // 【Day 8】**精准判断要不要清缓存** —— 这是今天最值得学的一个判断习惯。
        //
        // 统计接口用到了区域的哪几个字段？只有 region_code（做关联）和 region_name（给前端显示）。
        // 所以：
        //   · 只改了 sort / parentCode / regionLevel → 统计结果**一模一样**，缓存不用动；
        //   · 改了名称或编码 → 所有含这个区域的统计结果全错，必须清。
        //
        // 为什么值得较真？因为"清缓存"不是免费的：
        //   清完之后下一次请求必然未命中，要么多查一次库，还要抢锁。清得太随意，
        //   缓存就变成了"永远在失效"，等于没做。
        // 一句话：**失效范围要按"哪些查询结果真的变了"来定，不能一律清空。**
        boolean nameChanged = !Objects.equals(old.getRegionName(), reqVO.getRegionName());
        boolean codeChanged = !Objects.equals(old.getRegionCode(), reqVO.getRegionCode());
        if (nameChanged || codeChanged) {
            evictStatCacheAfterCommit("修改区域 id=" + reqVO.getId()
                    + "（名称变化=" + nameChanged + " 编码变化=" + codeChanged + "）");
        } else {
            log.info("[一致性] 区域 id={} 的修改不影响统计字段，跳过清缓存", reqVO.getId());
        }
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

        // 【Day 8】删除区域必须清缓存 —— 而且这里没有"要不要清"的余地：
        // 统计结果里"补不到区域名就丢弃"（Day 6 的语义），所以这个区域会从结果里整条消失，
        // 结果集本身（150 条变 149 条）都变了。不清缓存，前端会继续看到那个已删除的区域。
        evictStatCacheAfterCommit("删除区域 id=" + id + " code=" + old.getRegionCode());
    }

    /**
     * 【Day 8 最核心的一段】让缓存失效，而且是**在事务提交之后**才删。
     *
     * ========================================================================
     * 一、为什么删缓存必须等事务提交？这就要讲"缓存一致性"的经典事故了
     * ========================================================================
     * 假设在事务**内部**就删了缓存，会发生这个时序：
     *
     *   ① 更新线程：UPDATE dim_region（事务还没提交，别的连接看不见新值）
     *   ② 更新线程：DEL 缓存（缓存空了）
     *   ③ 读线程：  缓存未命中 → 查库 → **读到的是旧值**（事务未提交）→ 把旧值写回缓存
     *   ④ 更新线程：事务提交（数据库里已经是新值了）
     *   ⑤ 结果：缓存里躺着**旧值**，而且 TTL 600 秒 —— 用户要看 10 分钟的错数据。
     *
     * 问题的本质是：**"删缓存"和"事务提交"这两个动作之间有个时间窗**，
     * 读线程完全可能挤进这个窗口，把旧值重新灌回缓存（这叫"缓存回填脏数据"）。
     * 所以删缓存的正确时机是 **事务提交之后（afterCommit）**，不是方法体里。
     *
     * ========================================================================
     * 二、那"先删缓存再更新库" vs "先更新库再删缓存"，到底选哪个？
     * ========================================================================
     * 这是面试最爱问的一题，标准答案不是背结论，而是**说清两者的失败场景**：
     *
     *   · 先删缓存、再更新库（Cache Aside 的淘汰式）：
     *     删完、库还没更新的空档里，读线程会把旧值查出来回填 → 脏数据躺到 TTL 到期。
     *     这个窗口比"更新库再删缓存"更危险，因为删缓存是个瞬间动作、而更新库在事务里可能要几十毫秒。
     *
     *   · 先更新库、再删缓存（**本项目采用**）：
     *     只有在"读线程恰好在本线程提交之后、删缓存之前读了旧缓存"时才会短暂不一致，
     *     而且下一次读之前缓存就被删了，实际影响窗口极小。
     *     Facebook 那篇著名的论文（Scaling Memcache at Facebook）推荐的就是这个顺序。
     *
     *   · 那为什么还有"延迟双删"？—— 为了兜住上面那个极小窗口和主从延迟：
     *     更新库 → 删缓存 → **睡 500ms 左右** → 再删一次。
     *     第二次删是为了把"在这期间别人回填的旧值"再清一次。
     *     代价是"睡"这个动作很脏（阻塞线程、时间靠猜），所以它更像补丁而不是方案。
     *
     *   · 真要强一致怎么办？—— 别用"应用层删缓存"这套了：
     *     订阅 binlog（Canal / Debezium）异步失效、或者干脆让缓存只存"不会变"的字典数据。
     *     **现实工程里，缓存一致性追求的是"最终一致 + 窗口尽量小"，不是强一致。**
     *     谁跟你说"我用 Redis 做到了强一致"，基本可以判定他没做过。
     *
     * ========================================================================
     * 三、这段代码里的两个细节
     * ========================================================================
     *   ① {@code isSynchronizationActive()}：判断"当前有没有活动事务"。
     *      正常走 Controller 时一定有；但如果哪天这个方法被别的非事务方法直接调用，
     *      就没有事务了 —— 这时立刻删是对的（没有事务提交这回事）。
     *   ② {@code afterCommit}：Spring 事务同步的回调点，事务真正提交后才执行。
     *      **注意此时已经出了事务，如果这里抛异常，不会让业务回滚** ——
     *      这正是我们要的：删缓存失败只能记日志，绝不能让"改数据成功"变成"改数据失败"。
     *      （另一种写法是 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}，
     *        效果一样，但要把事件发布出去，代码更多。这里用同步回调更直观。）
     */
    private void evictStatCacheAfterCommit(String reason) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evictAllStat();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("[一致性] 事务已提交，开始清理统计缓存。原因：{}", reason);
                cache.evictAllStat();
            }
        });
    }
}

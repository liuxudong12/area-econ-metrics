package com.liuxudong.areaecon.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.liuxudong.areaecon.controller.vo.RegionSaveReqVO;
import com.liuxudong.areaecon.entity.Region;

import java.util.Collection;
import java.util.List;

/**
 * 区域业务接口。
 *
 * 【为什么要有 Service 层？Controller 直接调 Mapper 不行吗？】
 * 技术上可以，但企业项目里几乎不会这么做，原因有三：
 *   ① 职责分离：Controller 只管"接收请求、返回响应"，不该关心 SQL 怎么写；
 *   ② 复用：同一个业务逻辑可能被多个 Controller、定时任务、消息消费者调用；
 *   ③ 事务边界：@Transactional 是加在 Service 方法上的（Controller 上加不生效，这是常见坑）。
 * 面试被问"你为什么分层"，答这三条就够了。
 *
 * 【extends IService<Region> 是什么】
 * MyBatis-Plus 提供的通用 Service 接口，和 BaseMapper 一个思路：
 * 把最常见的增删改查都准备好了，你白拿 save / removeById / getById / list / page 等方法。
 * 我们的实现类继承 ServiceImpl 后，这些方法自动就有实现，不用自己写。
 *
 * 下面三个方法是我们**额外**定义、框架提供不了的业务查询。
 */
public interface RegionService extends IService<Region> {

    /**
     * 查询区域列表。
     * @param level 层级（1 省 / 2 市 / 3 区县），传 null 表示查全部
     */
    List<Region> listByLevel(Integer level);

    /**
     * 分页查询区域。
     * @param pageNum  第几页，从 1 开始
     * @param pageSize 每页几条
     * @param keyword  区域名称关键字（模糊匹配），可为空
     */
    IPage<Region> pageQuery(long pageNum, long pageSize, String keyword);

    /**
     * 按区域编码查一条。查不到返回 null。
     */
    Region getByCode(String regionCode);

    /**
     * 【Day 6 新增】按区域编码**批量**查询。
     *
     * 用途：统计接口查出"每个区域的汇总值"之后，拿这批编码一次性把区域名称捞回来，
     * 在 Java 里补到结果上 —— 这样统计 SQL 里就不用 JOIN dim_region 了。
     *
     * 【为什么不循环调 getByCode？N+1 问题】
     * 150 个区域就是 150 次数据库往返。批量查是 1 次。
     * 这个坑叫 N+1 查询，是面试常问的"你项目里怎么优化性能"的经典答案之一。
     * 判断标准很简单：**看到 for 循环里写数据库查询，就要警觉。**
     *
     * @param regionCodes 区域编码集合，为空时返回空列表（不要拼出 IN () 这种非法 SQL）
     */
    List<Region> listByCodes(Collection<String> regionCodes);

    // ==================== Day 3：写操作 ====================

    /**
     * 新增区域，返回新记录的 id（自增主键）。
     *
     * 业务规则：区域编码不能重复。
     * 编码重复时抛 BizException，由 GlobalExceptionHandler 转成 {"code":500,"message":"区域编码已存在：xxx"}。
     *
     * @param reqVO 新增请求，id 字段会被忽略
     */
    Long createRegion(RegionSaveReqVO reqVO);

    /**
     * 修改区域。id 必填，记录不存在或编码与他人冲突都会抛 BizException。
     */
    void updateRegion(RegionSaveReqVO reqVO);

    /**
     * 删除区域。注意是**逻辑删除**（把 deleted 置为 1），数据仍在表里。
     */
    void deleteRegion(Long id);
}

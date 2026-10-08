package com.liuxudong.areaecon.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.liuxudong.areaecon.entity.Region;

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
}

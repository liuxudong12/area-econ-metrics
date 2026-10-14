package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * TOP N 区域排行的结果对象。
 *
 * 和 RegionMetricStatVO 的区别：多了两个"算出来的字段"
 *
 *     rankNo  名次。不是数据库里存的，是 RANK() 窗口函数现场算的
 *     share   占比（%）。= 本区域值 / 所有区域总值 × 100
 *
 * 【为什么 rankNo 和 share 要放进 VO，而不是让前端自己算？】
 *   ① 名次牵扯并列规则（两个人并列第 1，下一个是第 2 还是第 3？）
 *      —— ROW_NUMBER / RANK / DENSE_RANK 三个函数三种答案，这是业务规则，不该甩给前端；
 *   ② 占比要用"所有区域的总和"做分母，前端只拿到了前 10 名，它手里没有全量数据，算不出来。
 *
 * 【为什么 rankNo 用 Integer 而不是 int】
 *   SQL 没查到值时映射出来是 null。用基本类型 int 接会直接抛空指针，
 *   用包装类型 Integer 至少是 null，能看出来"这里没值"。**VO 字段一律用包装类型**。
 */
@Data
public class RegionRankVO {

    /** 区域编码 */
    private String regionCode;

    /**
     * 区域名称。
     * ⚠️ 注意：这个字段**不是这条 SQL 查出来的**，
     *    是 Service 拿到结果后，再去 dim_region 批量查一次补上的。
     *    （原因见 StatServiceImpl.categorySummary 里的注释）
     */
    private String regionName;

    /** 汇总值 */
    private BigDecimal totalValue;

    /** 名次，从 1 开始。相同值并列（RANK 会跳号：1,1,3） */
    private Integer rankNo;

    /** 占全部区域的比重，单位 %，保留 2 位小数 */
    private BigDecimal share;
}

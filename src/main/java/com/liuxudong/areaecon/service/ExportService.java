package com.liuxudong.areaecon.service;

import com.liuxudong.areaecon.controller.vo.FactExportQuery;
import com.liuxudong.areaecon.controller.vo.StatExportQuery;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 导出服务。
 *
 * ==========================================================================
 * 【为什么这三个方法都直接吃 HttpServletResponse】
 * ==========================================================================
 * 因为导出必须**边查边往响应流里写**，不能先返回一个大对象再让 Controller 去写。
 * 一旦把 48 万行对应的对象全部组装好再返回，内存就白省了 ——
 * 流式导出的价值全在"数据不落地"。
 *
 * 代价是 Service 层依赖了 Servlet API（"响应"这种东西本来是 Web 层概念）。
 * 真实项目里有两种处理方式：
 *   ① 像这里一样直接传 response —— 简单直接，Spring 项目里非常常见；
 *   ② 定义 ExportCallback 接口（onStart(fileName) / onRow / onFinish），
 *      由 Controller 实现，Service 只认回调 —— 解耦更彻底，但多一层抽象。
 * 数据量不大、接口不多的时候，①的成本收益比更好。**别为了"分层纯洁"提前抽象。**
 *
 * ==========================================================================
 * 【★ 这三个方法都遵守同一条铁律 ★】
 * ==========================================================================
 *     任何可能失败的检查，都必须发生在"往响应里写第一个字节"之前。
 *
 * 因为 HTTP 响应是单向的：一旦开始输出，就没有"改用 500 状态码"这个选项了。
 * 顺序必须是：
 *      ① 限流检查（拿不到许可 → 直接拒绝）
 *      ② 计数检查（超过上限 → 直接拒绝）
 *      ③ 参数检查（比如统计导出缺 category）
 *      ——以上全部通过——
 *      ④ 设置响应头
 *      ⑤ 开始写 Excel
 *
 * 违反这条的后果很具体：用户会下载到一个**打不开的 xlsx**，
 * 而且因为他看到的是"下载完成"，会反复重试，把服务器再压一遍。
 */
public interface ExportService {

    /**
     * 导出指标值明细（大表，游标分页 + 流式写）。
     *
     * @param query    筛选条件，全部为可选
     * @param response 用于设置响应头和写文件
     */
    void exportFact(FactExportQuery query, HttpServletResponse response);

    /**
     * 导出统计结果（区域汇总 / 周期趋势 / TOP 排行 / 同比）。
     *
     * 结果集最大也就一千多行，所以**一次性写完**，不需要游标分页。
     * 另外它走的是 StatService，能吃到 Redis 缓存 ——
     * 缓存命中时导出几乎不碰数据库。
     */
    void exportStat(StatExportQuery query, HttpServletResponse response);

    /**
     * 导出维表（区域 / 指标 / 周期）。
     *
     * @param type region / indicator / period
     */
    void exportDict(String type, HttpServletResponse response);
}

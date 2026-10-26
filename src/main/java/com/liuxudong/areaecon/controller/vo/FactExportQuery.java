package com.liuxudong.areaecon.controller.vo;

import lombok.Data;

/**
 * 明细导出的筛选条件。
 *
 * ==========================================================================
 * 【为什么把 5 个参数包成一个对象】
 * ==========================================================================
 * 因为"计数"和"分页取数"这两个 SQL **必须用完全一样的 WHERE 条件**。
 *
 * 如果写成两个方法各传 5 个参数：
 *     long countForExport(String region, String indicator, String type, String from, String to)
 *     List<Xxx> selectForExport(String region, String indicator, String type, String from, String to,
 *                               Long lastId, int limit)
 * 那"总数"和"实际导出行数"就存在对不上的风险 —— 哪天改了一边忘了另一边，
 * 导出文件的行数和界面上显示的总数不一致，用户会以为数据丢了。
 * （我们 Day 11 刚踩过一个同源的坑：百分比分母算错，加起来只有 3.3%。）
 *
 * 包成一个对象后，两边都引用 `query.xxx`，XML 里再用 &lt;sql&gt; 片段复用同一段 WHERE，
 * **只有一份条件定义，改一处就全改**。
 *
 * ==========================================================================
 * 【这几个字段的语义】
 * ==========================================================================
 * 全部是「可选的」。传 null / 空 表示不按这个条件过滤。
 * 这是导出接口的常规设计：用户可能只导湖南省（regionCodePrefix=43），
 * 也可能导全部（什么都不传）。
 */
@Data
public class FactExportQuery {

    /**
     * 区域编码前缀，如 "43" = 湖南省全部、"4301" = 长沙市全部。
     *
     * 【为什么是"前缀"而不是"精确匹配"】
     * 因为 region_code 是层级编码：430000（湖南省）→ 430100（长沙市）→ 430102（芙蓉区）。
     * 上级编码天然是下级编码的前缀，所以 LIKE '43%' 就等于"省内所有区域"，
     * 一个条件搞定整棵子树，不用递归查维表把几百个子区域编码都查出来再 IN。
     *
     * 【LIKE '43%' 会不会用不上索引】
     * 会用到。"前缀匹配"（右边带 %）可以在 B+ 树上做范围扫描；
     * 只有"两边都带 %"（如 '%43%'）才必然全表扫描。
     * 这条区别面试常问：**左模糊会废掉索引，右模糊不会。**
     */
    private String regionCodePrefix;

    /** 指标编码，精确匹配。如 "1-1" */
    private String indicatorCode;

    /** 周期类型：Y / Q / M */
    private String periodType;

    /** 周期起始（含），字符串比较。如 "2024" —— 因为周期编码设计成可按字典序排序 */
    private String periodFrom;

    /** 周期结束（含） */
    private String periodTo;
}

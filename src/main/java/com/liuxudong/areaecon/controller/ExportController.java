package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.controller.vo.FactExportQuery;
import com.liuxudong.areaecon.controller.vo.StatExportQuery;
import com.liuxudong.areaecon.service.ExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出接口。
 *
 * ==========================================================================
 * 【为什么这三个方法的返回值都是 void，而不是 Result&lt;Xxx&gt;】
 * ==========================================================================
 * 因为它们的响应体是**二进制的 xlsx 字节流**，不是 JSON。
 * 成功时浏览器直接开始下载；失败时（在还没开始写文件的前提下）由
 * GlobalExceptionHandler 返回 JSON 错误 —— 前端通过 Content-Type 就能区分
 * application/json 和 application/vnd.openxmlformats-...sheet。
 *
 * 【但这里有一个不能回避的矛盾】
 * 一个接口"有时返回 JSON、有时返回文件"，对前端来说是不友好的：
 * axios 默认按 responseType:'json' 解析，遇到二进制会得到乱码。
 * 所以真实项目一般会约定：
 *   · 前端请求导出时用 responseType: 'blob'
 *   · 拿到响应后先看 Content-Type：是 json 就解析成错误提示，是 sheet 就下载
 * 这段判断逻辑必须在**前端**做（后端做不到，因为流已经发出去了）。
 * 手册"前端怎么接"一节给了这段代码。
 *
 * ==========================================================================
 * 【Controller 在这里到底该干多少事】
 * ==========================================================================
 * 只干三件：
 *   ① 把 HTTP 参数收下来（字符串）
 *   ② 做**便宜的**参数校验（格式、取值范围、必填）
 *   ③ 交给 Service
 *
 * 不做的事：查库、判断数据量、决定要不要拒绝。
 * 因为"数据量"必须查一次库才知道，那属于业务判断，放 Service。
 *
 * 这个边界的好处很实在：所有和"数据"相关的规则都在 Service 里，
 * 将来加一个"定时导出"（不走 HTTP），逻辑一行都不用重写。
 */
@Slf4j
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 导出指标值明细。
     *
     * 例子：
     * <pre>
     *   # 全量导出（48 万行）
     *   GET /api/export/fact
     *
     *   # 只导湖南省 2025 年的数据
     *   GET /api/export/fact?regionCodePrefix=43&amp;periodType=Y&amp;periodFrom=2025&amp;periodTo=2025
     *
     *   # 只导某一个指标
     *   GET /api/export/fact?indicatorCode=1-1&amp;regionCodePrefix=4301
     * </pre>
     *
     * 【regionCodePrefix 为什么叫"前缀"而不叫 regionCode】
     * 因为区域编码是层级编码，上级天然是下级的前缀：
     *   43 0000 湖南省 → 4301 00 长沙市 → 430102 芙蓉区
     * 传 "43" = 全省，传 "4301" = 全市，传 "430102" = 单区。
     * 一个参数覆盖三种粒度，比"传 regionCode + 传 level"简洁得多。
     *
     * 代价是：用户如果传了一个不存在的编码（比如 99），
     * 会得到"没有符合条件的数据"，而不是"区域不存在"。
     * 这个体验上可以接受 —— 导出场景下，用户通常是从界面上的下拉框选的。
     */
    @GetMapping("/fact")
    public void exportFact(
            @RequestParam(required = false) String regionCodePrefix,
            @RequestParam(required = false) String indicatorCode,
            @RequestParam(required = false) String periodType,
            @RequestParam(required = false) String periodFrom,
            @RequestParam(required = false) String periodTo,
            HttpServletResponse response) {

        FactExportQuery q = new FactExportQuery();
        q.setRegionCodePrefix(trimToNull(regionCodePrefix));
        q.setIndicatorCode(trimToNull(indicatorCode));
        q.setPeriodType(validatePeriodType(periodType));
        q.setPeriodFrom(trimToNull(periodFrom));
        q.setPeriodTo(trimToNull(periodTo));

        // 便宜的校验放这里：区间反了就立刻报错，不用等查完库才发现
        if (q.getPeriodFrom() != null && q.getPeriodTo() != null
                && q.getPeriodFrom().compareTo(q.getPeriodTo()) > 0) {
            throw new BizException("periodFrom（" + q.getPeriodFrom() + "）不能大于 periodTo（"
                    + q.getPeriodTo() + "）");
        }
        if (q.getRegionCodePrefix() != null && q.getRegionCodePrefix().length() > 12) {
            throw new BizException("regionCodePrefix 过长（最多 12 位），请传区域编码前缀，如 43");
        }

        exportService.exportFact(q, response);
    }

    /**
     * 导出统计结果。
     *
     * 例子：
     * <pre>
     *   GET /api/export/stat?kind=category&amp;category=工业&amp;periodType=Y&amp;periodCodes=2024,2025,2026
     *   GET /api/export/stat?kind=trend&amp;category=工业&amp;periodCodes=2024,2025,2026
     *   GET /api/export/stat?kind=top&amp;category=工业&amp;periodCodes=2024,2025&amp;topN=10
     *   GET /api/export/stat?kind=yoy&amp;category=工业&amp;basePeriod=2024&amp;currentPeriod=2025
     * </pre>
     *
     * 【为什么四个统计共用一个接口，而不是四个接口】
     * 因为它们的参数几乎一样（category + periodType + periodCodes），
     * 只有 yoy 用 basePeriod/currentPeriod、top 用 topN。
     * 做成一个接口 + kind 参数：
     *   · 前端只维护一个下载地址，加 kind 就行
     *   · 后端的限流、日志、异常处理都能复用一份
     * 代价是"没传 kind 时不知道要什么"—— 那就明确报错，列出可选值。
     *
     * 反过来说，**如果四个统计的参数结构完全不同，就该拆成四个接口**。
     * 合并的前提是"参数同构"，不是"我觉得接口太多了"。
     */
    @GetMapping("/stat")
    public void exportStat(
            @RequestParam String kind,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "Y") String periodType,
            @RequestParam(required = false) String periodCodes,
            @RequestParam(required = false) String basePeriod,
            @RequestParam(required = false) String currentPeriod,
            @RequestParam(required = false, defaultValue = "10") Integer topN,
            HttpServletResponse response) {

        StatExportQuery q = new StatExportQuery();
        q.setKind(trimToNull(kind));
        q.setCategory(trimToNull(category));
        q.setPeriodType(validatePeriodType(periodType));
        q.setPeriodCodes(splitCodes(periodCodes));
        q.setBasePeriod(trimToNull(basePeriod));
        q.setCurrentPeriod(trimToNull(currentPeriod));
        q.setTopN(topN);

        exportService.exportStat(q, response);
    }

    /**
     * 导出维表。
     *
     * 例子：
     * <pre>
     *   GET /api/export/dict?type=region      # 区域（约 1350 行）
     *   GET /api/export/dict?type=indicator   # 指标（约 18 行）
     *   GET /api/export/dict?type=period      # 周期（约 72 行）
     * </pre>
     *
     * 【这个接口的实际用途】
     * 业务方拿到明细表之后，第一件事通常是问："430102 是哪个区？1-1 是什么指标？"
     * 让他们自己下维表去 VLOOKUP，比每次来问你高效得多。
     * 真实项目里，这类"字典导出"几乎是标配 —— 它的价值不在技术难度，在**减少沟通成本**。
     */
    @GetMapping("/dict")
    public void exportDict(@RequestParam String type, HttpServletResponse response) {
        exportService.exportDict(type, response);
    }

    // ==========================================================================
    // 参数处理小工具
    // ==========================================================================

    /**
     * 校验周期类型。
     *
     * 【为什么必须校验，而不是"让 SQL 查不到数据就返回空"】
     * 因为传 "月" 而不是 "M" 的时候，SQL 会正常执行、返回空数组 ——
     * 用户看到的是"没有数据"，而不是"你参数写错了"。
     * 这两者的排查成本差了一个数量级：
     *   前者：用户去查数据是不是没导进来、去问开发、开发去查库……最后才发现是参数
     *   后者：一句话就结束了
     *
     * 这就是"fail fast"在接口设计上的体现：**宁可报错，也不要返回一个看起来正常但无意义的结果。**
     */
    private String validatePeriodType(String periodType) {
        if (periodType == null || periodType.isBlank()) {
            return null;
        }
        String t = periodType.trim().toUpperCase();
        if (!t.equals("Y") && !t.equals("Q") && !t.equals("M")) {
            throw new BizException("periodType 只能是 Y（年）/ Q（季）/ M（月），本次传入：" + periodType);
        }
        return t;
    }

    /** "2024,2025,2026" → ["2024","2025","2026"]，顺手去掉空项 */
    private List<String> splitCodes(String periodCodes) {
        if (periodCodes == null || periodCodes.isBlank()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (String s : periodCodes.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                list.add(v);
            }
        }
        return list;
    }

    /** 空串统一成 null —— 避免 XML 里 `&& != ''` 判断被空串绕过 */
    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

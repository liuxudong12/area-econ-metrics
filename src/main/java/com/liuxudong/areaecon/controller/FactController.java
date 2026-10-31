package com.liuxudong.areaecon.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.entity.FactIndicatorValue;
import com.liuxudong.areaecon.mapper.FactIndicatorValueMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指标值明细分页 —— 一个很小的接口，但有三个"教学用途"。
 *
 * ============================================================================
 * 一、它是"数据权限 + 分页插件顺序"的唯一验证手段
 * ============================================================================
 * 前 12 天的统计全是手写 SQL，一条都没走分页插件，所以"插件顺序"这个问题
 * 从来没暴露过。Day 13 加了数据权限之后，两个插件第一次同时存在，
 * 它们的先后立刻就成了正确性问题：
 *
 *   正确顺序（权限在前）：COUNT 数的是**过滤后**的行 → total 正确
 *   反了（分页在前）：      COUNT 数的是**全表**的行   → total 虚高
 *
 * 表现是：数据没错、只有 total 错，前端页码器显示几千页，翻几页就空了。
 * 手册实验 E6 会把顺序换过来，实测出这个差异。
 *
 * ============================================================================
 * 二、上限保护为什么必须写在这里
 * ============================================================================
 * 分页参数来自 URL，是用户可控的。不设上限会发生什么：
 *   {@code GET /api/fact/page?size=1000000}
 *   → MP 生成 {@code LIMIT 0, 1000000} → 100 万个对象一次性进堆
 *   → 48 万行的表直接全量加载 → 单请求几百 MB → 几个并发就 OOM
 *
 * 第 12 天已经实测过：48.96 万行全量查询峰值 508MB，256MB 堆下直接把服务打死。
 * 所以分页接口必须给 size 封顶，而且**封顶位置要在进 SQL 之前**。
 *
 * 【封顶还是报错】
 * 和 topN 一样，我选报错。理由也一样：静默把 size=1000 改成 200，
 * 用户以为拿到了 1000 条，实际 200 条，这种 bug 最难查。
 * 区别是这里给了明确的提示，告诉他上限是多少。
 */
@RestController
@RequestMapping("/api/fact")
public class FactController {

    /** 单页最大条数。200 是"足够展示一屏 + 不会拖慢"的常见取值 */
    private static final long MAX_PAGE_SIZE = 200;

    private final FactIndicatorValueMapper factMapper;

    public FactController(FactIndicatorValueMapper factMapper) {
        this.factMapper = factMapper;
    }

    /**
     * <pre>
     *   GET /api/fact/page?page=1&amp;size=20
     *   Header: X-User-Id: 2
     *   → {"total":35904, "pages":1796, "records":[...20条...]}
     * </pre>
     *
     * 注意 total：长沙用户看到的是 35904，不是 489600。
     * 这个数字对不对，就是"插件顺序对不对"的判据。
     */
    @GetMapping("/page")
    public Result<Page<FactIndicatorValue>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        if (page < 1) {
            throw new BizException("page 必须从 1 开始，本次传入：" + page);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BizException("size 必须在 1~" + MAX_PAGE_SIZE + " 之间，本次传入：" + size);
        }

        return Result.ok(factMapper.selectFactPage(new Page<>(page, size)));
    }
}

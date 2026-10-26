package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.controller.vo.FactExportQuery;
import com.liuxudong.areaecon.entity.FactIndicatorValue;
import com.liuxudong.areaecon.mapper.FactIndicatorValueMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【仅实验用】内存对比接口：一次性全查 vs 游标流式。
 *
 * ==========================================================================
 * 【为什么这个 Controller 要单独存在，而且默认不加载】
 * ==========================================================================
 * 因为"流式导出内存恒定"这句话，光看代码是说服不了人的 ——
 * 必须有两个可复现的数字放在一起才有说服力。
 *
 * 但这个接口本身**没有任何业务价值**，纯粹是测量工具。
 * 如果把它混进正式接口里，就是给生产环境留了一个"一次查 48 万行"的后门 ——
 * 谁调一下都能把服务打挂。
 *
 * 所以用 {@code @ConditionalOnProperty} 把它整类禁用：
 * 配置里不写 `stat.export.test.enabled=true` 时，**这个 Bean 根本不会被创建**，
 * 连 URL 都不存在（访问会 404，而不是 500）。
 *
 * 这比"在方法里 if (!enabled) return"好在哪？
 *   · 前者：类都没加载，不可能被绕过
 *   · 后者：多一层判断，万一判断写错（比如把 == 写成 !=）就漏了
 * **能在加载期解决的问题，不要留到运行期。**（这也是 @Profile 的价值）
 *
 * 用法见 docs/00-第12天操作手册.md 的"实验 C"。
 */
@Slf4j
@RestController
@RequestMapping("/api/export/_test")
@ConditionalOnProperty(prefix = "stat.export.test", name = "enabled", havingValue = "true")
public class ExportMemoryTestCase {

    /** 流式模式下的每页条数，和正式导出的默认值保持一致，才有可比性 */
    private static final int STREAM_PAGE_SIZE = 2000;

    private final FactIndicatorValueMapper factMapper;

    public ExportMemoryTestCase(FactIndicatorValueMapper factMapper) {
        this.factMapper = factMapper;
    }

    /**
     * 对比两种取数方式的内存占用。
     *
     * 例子：
     * <pre>
     *   # 一次性全查 48.96 万行
     *   GET /api/export/_test/memory?mode=list&amp;limit=489600
     *
     *   # 游标分页，每页 2000 条，总共也是 48.96 万行
     *   GET /api/export/_test/memory?mode=stream&amp;limit=489600
     * </pre>
     *
     * @param mode  list = 一次性全查（反面教材）；stream = 游标流式（我们的做法）
     * @param limit 取多少行，默认全表
     */
    @GetMapping("/memory")
    public Result<Map<String, Object>> memory(
            @RequestParam(defaultValue = "list") String mode,
            @RequestParam(defaultValue = "489600") int limit) {

        FactExportQuery emptyQuery = new FactExportQuery();

        // 先把垃圾清干净，让"起始占用"这个基线可信。
        // 不做这一步的话，上一个请求留下的垃圾会让 before 偏高，
        // 算出来的"增量"就失真了。
        forceGc();
        long beforeMb = usedHeapMb();
        long start = System.currentTimeMillis();
        long rows = 0;
        long peakMb = beforeMb;

        if ("list".equalsIgnoreCase(mode)) {
            // ---------- 反面教材：一条 SQL 把 48 万行全查进 List ----------
            // 注意：这里的 limit 有多大，就要在内存里放下多少对象。
            // 48 万行 × 9 个字段的 POJO，加上 List 的引用数组，大约 100MB+。
            List<FactIndicatorValue> all = factMapper.selectForExportByCursor(emptyQuery, 0L, limit);
            rows = all.size();

            // 测量时 all 还活着（下面那行 all.size() 就是为了让 JIT 不能提前回收它），
            // 所以这个数字代表"这些对象同时在内存里"的真实占用。
            peakMb = Math.max(peakMb, usedHeapMb());
            log.info("[实验-内存] mode=list rows={} 堆 {}MB→{}MB", rows, beforeMb, peakMb);

            // 显式置空，帮 GC 一点忙。也说明一件事：
            // 这种写法的内存峰值**必然**出现在"查完、还没开始写"的那一刻。
            all = null;
        } else if ("stream".equalsIgnoreCase(mode)) {
            // ---------- 正确做法：游标分页，内存里只有一页 ----------
            long lastId = 0L;
            while (rows < limit) {
                int size = (int) Math.min(STREAM_PAGE_SIZE, limit - rows);
                List<FactIndicatorValue> page =
                        factMapper.selectForExportByCursor(emptyQuery, lastId, size);
                if (page.isEmpty()) {
                    break;
                }
                rows += page.size();
                lastId = page.get(page.size() - 1).getId();

                // 每一轮都采一次样，取最大值 —— 峰值才是我们关心的，
                // 因为 OOM 是峰值决定的，不是平均值。
                peakMb = Math.max(peakMb, usedHeapMb());

                if (page.size() < size) {
                    break;
                }
                // page 出作用域后就可以被回收了，下一轮重新分配。
                // 真实导出时这里还会多一步 writer.write(page)，然后对象立刻变成垃圾。
            }
            log.info("[实验-内存] mode=stream rows={} 堆 {}MB→峰值{}MB", rows, beforeMb, peakMb);
        } else {
            return Result.fail("mode 只能是 list 或 stream");
        }

        long cost = System.currentTimeMillis() - start;
        long afterMb = usedHeapMb();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", mode);
        data.put("rows", rows);
        data.put("beforeMb", beforeMb);
        data.put("peakMb", peakMb);
        data.put("deltaMb", peakMb - beforeMb);
        data.put("afterMb", afterMb);
        data.put("costMs", cost);
        data.put("maxHeapMb", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        return Result.ok(data);
    }

    /**
     * 强制 GC。
     *
     * 【为什么敢在业务代码里写 System.gc()】
     * 平时绝对不该写（会触发 Full GC，停顿几百毫秒，影响在线用户）。
     * 但这里是个手动触发的实验接口，目的是"让基线可复现" ——
     * 不 GC 的话，同样的请求两次跑出来的数字能差 50MB，实验就没意义了。
     * **同一个方法在生产里是坏味道，在测量工具里是必需品。判断标准是"谁在什么场景调用它"。**
     */
    private void forceGc() {
        System.gc();
        try {
            // 给 GC 一点时间收尾。System.gc() 是"建议"不是"命令"，
            // 但 HotSpot 默认会执行（除非加了 -XX:+DisableExplicitGC）。
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
    }
}

-- ============================================================================
-- 05 · JOIN 结构优化实验（Day 6）
-- ============================================================================
-- 目的：把 /api/stat/category-summary 的聚合 SQL 从 43ms 优化到 10ms 以内，
--       并且证明"改 JOIN 结构"和"加索引"是两种不同的优化思路。
--
-- 数据库：area_econ（MySQL 8.0.46）
-- 数据量：fact_indicator_value 489,600 行
-- 现有索引：PRIMARY(id)、uk_fact(region_code, indicator_code, period_code)、
--           idx_tpi(period_type, period_code, indicator_code)
--
-- ⚠️ 本脚本只是"实验记录"，不需要再执行一遍就能看结论。
--    但如果你想亲手复现，按顺序跑即可（全部是只读查询，不会改数据）。
-- ============================================================================


-- ============================================================================
-- 第 1 步：先看改造前的 SQL 慢在哪
-- ============================================================================
-- 用 EXPLAIN ANALYZE（MySQL 8.0.18+ 才有），它会真的执行一次，
-- 然后告诉你每个执行节点的**真实耗时**。
-- 这是排查慢查询最直接的工具，比只看 EXPLAIN 的"预估"强得多。

EXPLAIN ANALYZE
SELECT f.region_code, r.region_name, SUM(f.indicator_value) AS totalValue
FROM fact_indicator_value f
         JOIN dim_indicator i ON i.indicator_code = f.indicator_code
         JOIN dim_region r ON r.region_code = f.region_code
WHERE f.deleted = 0 AND i.deleted = 0 AND r.deleted = 0
  AND f.period_type = 'Y' AND i.category = '工业'
  AND f.period_code IN ('2024', '2025', '2026')
GROUP BY f.region_code, r.region_name
ORDER BY totalValue DESC;

-- 实测输出（节选，已删掉无关层级）：
--
-- -> Sort: totalValue DESC                                    (actual time=44.3..44.3 rows=150)
--     -> Table scan on <temporary>                            (actual time=44..44.1)
--         -> Aggregate using temporary table                  (actual time=44..44)
--             -> Nested loop inner join                       (actual time=0.273..42.8 rows=1350)   ← ★瓶颈
--                 -> Inner hash join (no condition)            rows=600
--                     -> Table scan on r (150 行)
--                     -> Hash -> Table scan on i (18 行)
--                 -> Index lookup on f using uk_fact            (actual time=0.07..0.0708 rows=2.25 loops=600)
--
-- 【怎么读这棵树】
--   ① 缩进 = 层级。子节点先执行，结果交给父节点。从最里面往外读。
--   ② "actual time=A..B" 里，A 是"返回第一行花了多久"，B 是"全部返回完花了多久"。
--      看 B 那个数（最后一个），它代表这个节点**总共**花了多少毫秒。
--   ③ loops=600 表示这个节点被执行了 600 次（150 区域 × 4 个工业指标）。
--
-- 【结论（一句话）】
--   真正慢的不是聚合也不是排序（加起来才 2ms），而是那 600 次索引查找：
--   每次都要拿着 region_code + indicator_code 去 f 表翻一遍，翻完之后
--   1350 行结果还要**逐行回表**去 dim_region 换区域名字。42.8ms 全耗在这。


-- ============================================================================
-- 第 2 步：四种写法横向对比（同一台机器、同一份数据、同一个客户端）
-- ============================================================================

-- ---- 方案 A：原版，JOIN 两张维表（基线）------------------------------------
EXPLAIN ANALYZE
SELECT f.region_code, r.region_name, SUM(f.indicator_value) AS totalValue
FROM fact_indicator_value f
         JOIN dim_indicator i ON i.indicator_code = f.indicator_code
         JOIN dim_region r ON r.region_code = f.region_code
WHERE f.deleted = 0 AND i.deleted = 0 AND r.deleted = 0
  AND f.period_type = 'Y' AND i.category = '工业'
  AND f.period_code IN ('2024', '2025', '2026')
GROUP BY f.region_code, r.region_name
ORDER BY totalValue DESC;
-- → 实测 44.3 ms


-- ---- 方案 B：强制走 idx_tpi 索引（验证"换索引有没有用"）--------------------
-- 用优化器提示 INDEX(f idx_tpi) 逼它走那个新索引。
EXPLAIN ANALYZE
SELECT /*+ INDEX(f idx_tpi) */ f.region_code, r.region_name, SUM(f.indicator_value) AS totalValue
FROM fact_indicator_value f
         JOIN dim_indicator i ON i.indicator_code = f.indicator_code
         JOIN dim_region r ON r.region_code = f.region_code
WHERE f.deleted = 0 AND i.deleted = 0 AND r.deleted = 0
  AND f.period_type = 'Y' AND i.category = '工业'
  AND f.period_code IN ('2024', '2025', '2026')
GROUP BY f.region_code, r.region_name
ORDER BY totalValue DESC;
-- → 实测 48.8 ms，**反而更慢**
-- 证明了：这次的问题不在索引，在 JOIN 结构。换索引治不了这个病。
-- ★ 这个"失败"的尝试一定要留着 —— 面试时能主动说出"我先试了换索引，没用，
--   才意识到瓶颈是回表次数而不是扫描行数"，比一上来就答对更有说服力。


-- ---- 方案 C：去掉 dim_region 的 JOIN（Day 6 采用的方案）---------------------
EXPLAIN ANALYZE
SELECT f.region_code, SUM(f.indicator_value) AS totalValue
FROM fact_indicator_value f
         JOIN dim_indicator i ON i.indicator_code = f.indicator_code
WHERE f.deleted = 0 AND i.deleted = 0
  AND f.period_type = 'Y' AND i.category = '工业'
  AND f.period_code IN ('2024', '2025', '2026')
GROUP BY f.region_code
ORDER BY totalValue DESC;
-- → 实测 9.52 ms（4.6 倍）
-- 执行计划变成了：
--   Index range scan on f using idx_tpi over (period_type='Y' AND period_code='2024') OR ...
--       with index condition: ... (actual time=0.203..8.25 rows=5400)
--   -> Inner hash join (f.indicator_code = i.indicator_code) (actual time=1.36..9.16 rows=1350)
-- 少了的正是那 1350 次回表查 dim_region。


-- ---- 方案 E：连 dim_indicator 也不 JOIN（极限探索）------------------------
-- 先把"工业"分类下的指标编码查出来（4 个），硬编码进 IN 列表。
SELECT indicator_code, indicator_name FROM dim_indicator WHERE category = '工业' AND deleted = 0;
-- → 2 / 2-1 / 2-2 / 2-3（其中 '2' 是分类节点本身，实际有数据的是 2-1、2-2、2-3）
--
-- 再执行：
EXPLAIN ANALYZE
SELECT f.region_code, SUM(f.indicator_value) AS totalValue
FROM fact_indicator_value f
WHERE f.deleted = 0 AND f.period_type = 'Y'
  AND f.period_code IN ('2024', '2025', '2026')
  AND f.indicator_code IN ('2-1', '2-2', '2-3')
GROUP BY f.region_code
ORDER BY totalValue DESC;
-- → 实测 6.11 ms（7.2 倍），扫描行数从 5,400 降到 1,350（索引直接精确命中 9 个区间）
--
-- 【为什么最终没用方案 E】
--   收益只有 3.4ms，代价是：应用层要为了拼 SQL 多查一次 dim_indicator、
--   还要自己拼 IN 列表。SQL 从"一眼看懂"变成"要看两处才知道查了什么"。
--   而方案 C 已经把 43ms 压到 10ms 了，对一个读接口来说完全够用。
--   ★ **优化的终点不是"最快"，而是"足够快 + 还好维护"。** 这句话面试能加分。


-- ---- 汇总对比 ---------------------------------------------------------------
-- ┌──────┬──────────────────────────────────────┬──────────┬────────────┬──────────────────────┐
-- │ 方案 │ 写法                                 │ DB 耗时  │ 相对基线   │ 关键动作             │
-- ├──────┼──────────────────────────────────────┼──────────┼────────────┼──────────────────────┤
-- │  A   │ JOIN dim_region + dim_indicator      │ 44.3 ms  │ 1.0×       │ 基线                 │
-- │  B   │ 强制走 idx_tpi                       │ 48.8 ms  │ 0.9×（差） │ 证明换索引没用       │
-- │  C   │ 去掉 dim_region 的 JOIN ★采用        │  9.52 ms │ 4.6×       │ 先聚合，后补名       │
-- │  E   │ 两个维表都不 JOIN                    │  6.11 ms │ 7.2×       │ 极限，但牺牲可维护性 │
-- └──────┴──────────────────────────────────────┴──────────┴────────────┴──────────────────────┘


-- ============================================================================
-- 第 3 步：结果一致性校验（这一步绝对不能省）
-- ============================================================================
-- 性能优化最大的风险不是"没变快"，而是"变快了但数据算错了"。
-- 必须用同一组参数，把改造前后的返回值逐行比一遍。

-- 改造前（含 dim_region）
SELECT f.region_code, r.region_name, SUM(f.indicator_value) AS totalValue
FROM fact_indicator_value f
         JOIN dim_indicator i ON i.indicator_code = f.indicator_code
         JOIN dim_region r ON r.region_code = f.region_code
WHERE f.deleted = 0 AND i.deleted = 0 AND r.deleted = 0
  AND f.period_type = 'Y' AND i.category = '工业'
  AND f.period_code IN ('2024', '2025', '2026')
GROUP BY f.region_code, r.region_name
ORDER BY totalValue DESC
LIMIT 5;

-- 改造后（不含 dim_region）
SELECT f.region_code, SUM(f.indicator_value) AS totalValue
FROM fact_indicator_value f
         JOIN dim_indicator i ON i.indicator_code = f.indicator_code
WHERE f.deleted = 0 AND i.deleted = 0
  AND f.period_type = 'Y' AND i.category = '工业'
  AND f.period_code IN ('2024', '2025', '2026')
GROUP BY f.region_code
ORDER BY totalValue DESC
LIMIT 5;

-- 实测结果（两次完全一致）：
--   region_code | region_name | totalValue
--   430806      | 张家界第6区 | 67757.3316
--   430905      | 益阳第5区   | 63711.3076
--   430102      | 长沙第2区   | 63282.0554
--   431006      | 郴州第6区   | 62110.4127
--   431102      | 永州第2区   | 60833.8531
--
-- 另外还要校验"条数"：
SELECT COUNT(*) FROM (
    SELECT f.region_code FROM fact_indicator_value f
             JOIN dim_indicator i ON i.indicator_code = f.indicator_code
    WHERE f.deleted = 0 AND i.deleted = 0
      AND f.period_type = 'Y' AND i.category = '工业'
      AND f.period_code IN ('2024', '2025', '2026')
    GROUP BY f.region_code
) t;
-- → 150 条，和改造前一致


-- ============================================================================
-- 第 4 步：database 侧精确计时（performance_schema 口径）
-- ============================================================================
-- 这个方法在 Day 5 手册里介绍过。这里补一个"两条 SQL 分别统计"的写法：
-- 因为这两条 SQL 的**结构不同**（一个含 JOIN dim_region，一个不含），
-- MySQL 会把它们归为两条独立的 digest，所以可以分别读出各自的平均耗时。

SELECT ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS 平均ms,
       COUNT_STAR                           AS 执行次数,
       SUM_ROWS_EXAMINED DIV COUNT_STAR     AS 平均扫描行数,
       CASE
           WHEN DIGEST_TEXT LIKE '%dim_region%' THEN '改造前(三表 JOIN)'
           ELSE '改造后(两表 JOIN)'
           END                              AS 版本
FROM performance_schema.events_statements_summary_by_digest
WHERE DIGEST_TEXT LIKE '%fact_indicator_value%'
  AND DIGEST_TEXT LIKE '%region_code%'
ORDER BY AVG_TIMER_WAIT DESC;

-- 实测（各跑 80 次）：
-- ┌──────────────────────┬──────────┬──────────────┬──────────┐
-- │ 版本                 │ 平均耗时 │ 平均扫描行数 │ 执行次数 │
-- ├──────────────────────┼──────────┼──────────────┼──────────┤
-- │ 改造前(三表 JOIN)    │ 43.38 ms │ 1,668        │ 80       │
-- │ 改造后(两表 JOIN)    │ 10.57 ms │ 5,568        │ 80       │
-- └──────────────────────┴──────────┴──────────────┴──────────┘
--
-- ⚠️⚠️ 注意这张表里最反直觉的一行：
--      **改造后扫描的行数（5,568）比改造前（1,668）多了 3.3 倍，却快了 4.1 倍。**
--
--      为什么？因为"扫描行数"从来就不是性能的唯一指标：
--        · 改造前：扫 1,668 行 → 但走的是 600 次"点查 + 随机回表"，随机 I/O 极慢
--        · 改造后：扫 5,568 行 → 但是一次"顺序范围扫描"，顺序 I/O 快得多
--      这个反例能直接反驳"扫描行数少的 SQL 一定快"这个常见误解。
--      ★ 面试时主动讲这个，比背"rows 越小越好"显得你懂原理。


-- ============================================================================
-- 第 5 步：索引现状（确认实验前后没有多余索引）
-- ============================================================================

SHOW INDEX FROM fact_indicator_value;
-- 结果应为：
--   PRIMARY   (id)
--   uk_fact   (region_code, indicator_code, period_code)   ← Day 1 就建好的业务唯一索引
--   idx_tpi   (period_type, period_code, indicator_code)   ← Day 5 为 /trend 加的
--
-- ★ 划重点：**Day 6 一个索引都没加。**
--   同样的两个索引，Day 5 用它治好了 /trend（133ms → 9.66ms），
--   Day 6 治 /category-summary 靠的是改 SQL 的 JOIN 结构（43ms → 10.6ms）。
--   所以"优化"这件事有两把武器：**加索引** 和 **改 SQL**，
--   只会其中一把的人，遇到"索引已经加满了还是慢"的情况就没招了。


-- ============================================================================
-- 第 6 步：把实验结论落到接口层（端到端验证）
-- ============================================================================
-- 启动服务后执行（端口按你自己的来，本机因端口冲突用了 18080）：
--
--   服务端已改造完毕，直接用 curl 请求即可。作者实测（40 次请求，丢弃前 10 次预热）：
--
--   ┌────────────────────────┬──────────────┬────────────┐
--   │ 接口                   │ 端到端平均   │ 最大       │
--   ├────────────────────────┼──────────────┼────────────┤
--   │ /category-summary      │ 30.14 ms     │ 58.56 ms   │
--   │ /trend   (Day 5 优化)  │ 13.75 ms     │ 16.16 ms   │
--   │ /top-regions (新)      │ 21.35 ms     │ 40.77 ms   │
--   │ /yoy         (新)      │ 26.07 ms     │ 46.47 ms   │
--   └────────────────────────┴──────────────┴────────────┘
--
-- 【为什么 DB 侧 10.6ms，端到端却是 30ms？】
--   剩下的 20ms 是应用层的固定开销：Tomcat 收请求 → Spring MVC 路由 →
--   MyBatis 映射 150 行结果 → 打 SQL 日志（application.yml 里开着 StdOutImpl！）→
--   JSON 序列化 → 网络回包。
--   ★ 这也解释了一件事：**优化到后面，瓶颈会从"数据库"转移到"应用层"。**
--     当你发现 SQL 已经只剩 10ms，接口却还要 30ms 时，就该考虑：
--     ① 关掉生产环境的 SQL 日志；② 加缓存（Day 7 要做的就是这个）。

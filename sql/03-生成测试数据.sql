-- =============================================================
-- 03 · 生成测试数据（约 49 万行）
--
-- 为什么要灌这么多数据？
--   第 6-8 天要做"慢查询 → 加索引 → 变快"的实验。
--   数据太少的话，全表扫描也就几毫秒，你根本看不出索引优化前后的差别。
--   50 万行只是"够看出问题"，真实统计平台的指标表通常是千万级。
--
-- 行数是怎么算出来的？
--   区域数 × 叶子指标数 × 周期数
--   = 150 × 12 × 272
--   = 489,600
--
-- 这个脚本可以重复执行（全部 INSERT IGNORE）：
--   已经存在的数据会被跳过，不会报错也不会产生重复。
--
-- 说明：区域名称是合成的（"长沙第2区"这种）。目的只是造出数据分布，
--      不是还原真实行政区划。
-- =============================================================

USE area_econ;

-- -------------------------------------------------------------
-- 1) 补齐区域：13 个地级市 + 每个市 10 个区县
--    原有的 7 行（湖南省 / 娄底市 / 娄底 5 个区县）不动。
--    娄底市已经有区县了，所以下面跳过 4313，避免编码冲突。
-- -------------------------------------------------------------

-- 1.1 再补 13 个地级市（湖南共 14 个市州，娄底已有）
INSERT IGNORE INTO dim_region (region_code, region_name, parent_code, region_level, sort) VALUES
('430100', '长沙市',               '430000', 2, 1),
('430200', '株洲市',               '430000', 2, 2),
('430300', '湘潭市',               '430000', 2, 3),
('430400', '衡阳市',               '430000', 2, 4),
('430500', '邵阳市',               '430000', 2, 5),
('430600', '岳阳市',               '430000', 2, 6),
('430700', '常德市',               '430000', 2, 7),
('430800', '张家界市',             '430000', 2, 8),
('430900', '益阳市',               '430000', 2, 9),
('431000', '郴州市',               '430000', 2, 10),
('431100', '永州市',               '430000', 2, 11),
('431200', '怀化市',               '430000', 2, 12),
('433100', '湘西土家族苗族自治州', '430000', 2, 13);

-- 1.2 每个市补 10 个区县
--     区县编码 = 市编码前 4 位 + 2 位序号（4301 + 02 → 430102）
INSERT IGNORE INTO dim_region (region_code, region_name, parent_code, region_level, sort)
SELECT CONCAT(c.city4, LPAD(d.n, 2, '0'))                       AS region_code,
       CONCAT(c.short_name, '第', d.n, '区')                     AS region_name,
       CONCAT(c.city4, '00')                                    AS parent_code,
       3                                                        AS region_level,
       d.n                                                      AS sort
FROM (
              SELECT '4301' city4, '长沙'   short_name
    UNION ALL SELECT '4302', '株洲'
    UNION ALL SELECT '4303', '湘潭'
    UNION ALL SELECT '4304', '衡阳'
    UNION ALL SELECT '4305', '邵阳'
    UNION ALL SELECT '4306', '岳阳'
    UNION ALL SELECT '4307', '常德'
    UNION ALL SELECT '4308', '张家界'
    UNION ALL SELECT '4309', '益阳'
    UNION ALL SELECT '4310', '郴州'
    UNION ALL SELECT '4311', '永州'
    UNION ALL SELECT '4312', '怀化'
    UNION ALL SELECT '4331', '湘西'
) c
CROSS JOIN (
              SELECT 2 n
    UNION ALL SELECT 3  UNION ALL SELECT 4  UNION ALL SELECT 5
    UNION ALL SELECT 6  UNION ALL SELECT 7  UNION ALL SELECT 8
    UNION ALL SELECT 9  UNION ALL SELECT 10 UNION ALL SELECT 11
) d;

-- -------------------------------------------------------------
-- 2) 补齐周期：2011-2026 的年 / 季 / 月
--    原来只有 2021-2026（30 + 72 = 102 个周期），补齐后是 272 个。
--    周期数直接决定数据量，所以这一步很关键。
--    （已有的周期会被 IGNORE 跳过，不会重复）
-- -------------------------------------------------------------

-- 2.1 年度周期
INSERT IGNORE INTO dim_period (period_code, period_type, period_year, period_index, start_date, end_date)
SELECT CAST(y AS CHAR), 'Y', y, 1,
       MAKEDATE(y, 1),
       LAST_DAY(MAKEDATE(y, 1) + INTERVAL 11 MONTH)
FROM (
              SELECT 2011 y
    UNION ALL SELECT 2012 UNION ALL SELECT 2013 UNION ALL SELECT 2014
    UNION ALL SELECT 2015 UNION ALL SELECT 2016 UNION ALL SELECT 2017
    UNION ALL SELECT 2018 UNION ALL SELECT 2019 UNION ALL SELECT 2020
    UNION ALL SELECT 2021 UNION ALL SELECT 2022 UNION ALL SELECT 2023
    UNION ALL SELECT 2024 UNION ALL SELECT 2025 UNION ALL SELECT 2026
) ys;

-- 2.2 季度周期
INSERT IGNORE INTO dim_period (period_code, period_type, period_year, period_index, start_date, end_date)
SELECT CONCAT(y, 'Q', q), 'Q', y, q,
       MAKEDATE(y, 1) + INTERVAL ((q - 1) * 3) MONTH,
       LAST_DAY(MAKEDATE(y, 1) + INTERVAL ((q - 1) * 3 + 2) MONTH)
FROM (
              SELECT 2011 y
    UNION ALL SELECT 2012 UNION ALL SELECT 2013 UNION ALL SELECT 2014
    UNION ALL SELECT 2015 UNION ALL SELECT 2016 UNION ALL SELECT 2017
    UNION ALL SELECT 2018 UNION ALL SELECT 2019 UNION ALL SELECT 2020
    UNION ALL SELECT 2021 UNION ALL SELECT 2022 UNION ALL SELECT 2023
    UNION ALL SELECT 2024 UNION ALL SELECT 2025 UNION ALL SELECT 2026
) ys
CROSS JOIN (SELECT 1 q UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) qs;

-- 2.3 月度周期（2021-2026 的已有，这里补齐 2011-2020）
INSERT IGNORE INTO dim_period (period_code, period_type, period_year, period_index, start_date, end_date)
SELECT CONCAT(y, '-', LPAD(m.n, 2, '0'))                   AS period_code,
       'M'                                                  AS period_type,
       y                                                    AS period_year,
       m.n                                                  AS period_index,
       MAKEDATE(y, 1) + INTERVAL (m.n - 1) MONTH            AS start_date,
       LAST_DAY(MAKEDATE(y, 1) + INTERVAL (m.n - 1) MONTH)  AS end_date
FROM (
              SELECT 2011 y
    UNION ALL SELECT 2012 UNION ALL SELECT 2013 UNION ALL SELECT 2014
    UNION ALL SELECT 2015 UNION ALL SELECT 2016 UNION ALL SELECT 2017
    UNION ALL SELECT 2018 UNION ALL SELECT 2019 UNION ALL SELECT 2020
    UNION ALL SELECT 2021 UNION ALL SELECT 2022 UNION ALL SELECT 2023
    UNION ALL SELECT 2024 UNION ALL SELECT 2025 UNION ALL SELECT 2026
) ys
CROSS JOIN (
              SELECT 1 n
    UNION ALL SELECT 2  UNION ALL SELECT 3  UNION ALL SELECT 4
    UNION ALL SELECT 5  UNION ALL SELECT 6  UNION ALL SELECT 7
    UNION ALL SELECT 8  UNION ALL SELECT 9  UNION ALL SELECT 10
    UNION ALL SELECT 11 UNION ALL SELECT 12
) m;

-- -------------------------------------------------------------
-- 3) 灌入指标值（主体）
--    行数 = 150 区域 × 12 叶子指标 × 272 周期 = 489,600
--
--    "叶子指标" = 没有被任何指标当作上级的指标。
--    （"1 综合经济"、"2 工业"这种是汇总节点，不给值；
--      值只挂在最末端的具体指标上，这是维度建模的常规做法）
-- -------------------------------------------------------------
INSERT IGNORE INTO fact_indicator_value
    (region_code, indicator_code, period_code, period_type, indicator_value, data_source)
SELECT r.region_code,
       i.indicator_code,
       p.period_code,
       p.period_type,
       ROUND(RAND() * 10000, 4)      AS indicator_value,   -- 0 ~ 10000 的随机值
       '导入'                         AS data_source
FROM dim_region r
CROSS JOIN (
    -- 先把叶子指标算出来（只算一次，不要放在最外层每行都算）
    SELECT i.indicator_code
    FROM dim_indicator i
    WHERE i.deleted = 0
      AND NOT EXISTS (
          SELECT 1 FROM dim_indicator c WHERE c.parent_code = i.indicator_code
      )
) i
CROSS JOIN dim_period p
WHERE r.deleted = 0;

-- -------------------------------------------------------------
-- 4) 验收
-- -------------------------------------------------------------
SELECT '区域数'       AS 项目, COUNT(*) AS 数量 FROM dim_region
UNION ALL SELECT '指标数',             COUNT(*) FROM dim_indicator
UNION ALL SELECT '周期数',             COUNT(*) FROM dim_period
UNION ALL SELECT '指标值行数',         COUNT(*) FROM fact_indicator_value;

-- 看周期覆盖范围对不对
SELECT MIN(period_code) AS 最小周期, MAX(period_code) AS 最大周期, COUNT(*) AS 周期数 FROM dim_period;

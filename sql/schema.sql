-- =============================================================
-- 区域经济运行指标服务 · 建表脚本
-- MySQL 8.0+  字符集统一 utf8mb4
-- 直接整段执行即可
-- =============================================================

CREATE DATABASE IF NOT EXISTS area_econ
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE area_econ;

-- -------------------------------------------------------------
-- 1. 区域维表
--    层级：1 省 / 2 市 / 3 区县
-- -------------------------------------------------------------
DROP TABLE IF EXISTS dim_region;
CREATE TABLE dim_region (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  region_code   VARCHAR(32)  NOT NULL                COMMENT '区域编码，如 431300',
  region_name   VARCHAR(64)  NOT NULL                COMMENT '区域名称',
  parent_code   VARCHAR(32)  DEFAULT NULL            COMMENT '上级区域编码',
  region_level  TINYINT      NOT NULL                COMMENT '层级：1省 2市 3区县',
  sort          INT          NOT NULL DEFAULT 0      COMMENT '排序',
  deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0正常 1删除',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_region_code (region_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='区域维表';

-- -------------------------------------------------------------
-- 2. 指标维表
--    指标编码带层级：1 综合经济 → 1-1 GDP → 1-1-1 第一产业
--    这就是"统一建模"的核心：用编码体系抽象掉多张业务表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS dim_indicator;
CREATE TABLE dim_indicator (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  indicator_code  VARCHAR(32)  NOT NULL                COMMENT '指标编码，如 1-1',
  indicator_name  VARCHAR(64)  NOT NULL                COMMENT '指标名称，如 GDP',
  parent_code     VARCHAR(32)  DEFAULT NULL            COMMENT '上级指标编码',
  category        VARCHAR(32)  NOT NULL                COMMENT '分类：综合经济/工业/财政/民生',
  unit            VARCHAR(16)  DEFAULT NULL            COMMENT '单位：万元/亿元/人/%',
  value_type      TINYINT      NOT NULL DEFAULT 1      COMMENT '值类型：1数值 2百分比 3金额',
  sort            INT          NOT NULL DEFAULT 0      COMMENT '排序',
  deleted         TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_indicator_code (indicator_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标维表';

-- -------------------------------------------------------------
-- 3. 周期维表
--    统一表达 年 / 季 / 月，period_index 用于排序和"取最近N期"
-- -------------------------------------------------------------
DROP TABLE IF EXISTS dim_period;
CREATE TABLE dim_period (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  period_code   VARCHAR(16) NOT NULL                COMMENT '周期编码：2026 / 2026Q1 / 2026-01',
  period_type   CHAR(1)     NOT NULL                COMMENT '周期类型：Y年 Q季 M月',
  period_year   SMALLINT    NOT NULL                COMMENT '所属年份',
  period_index  INT         NOT NULL                COMMENT '年内序号，用于排序，如 2026Q1 → 1',
  start_date    DATE        NOT NULL,
  end_date      DATE        NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_period_code (period_code),
  KEY idx_type_year (period_type, period_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='周期维表';

-- -------------------------------------------------------------
-- 4. 指标值事实表（核心大表，第 6-8 天的慢查询实验就在这里做）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS fact_indicator_value;
CREATE TABLE fact_indicator_value (
  id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
  region_code     VARCHAR(32)    NOT NULL                COMMENT '区域编码',
  indicator_code  VARCHAR(32)    NOT NULL                COMMENT '指标编码',
  period_code     VARCHAR(16)    NOT NULL                COMMENT '周期编码',
  period_type     CHAR(1)        NOT NULL                COMMENT '周期类型：Y/Q/M',
  indicator_value DECIMAL(20,4)  DEFAULT NULL            COMMENT '指标值',
  data_source     VARCHAR(32)    DEFAULT NULL            COMMENT '数据来源：手工/导入/接口',
  remark          VARCHAR(255)   DEFAULT NULL            COMMENT '备注',
  deleted         TINYINT        NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  create_time     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  -- 业务唯一约束：同一区域 + 同一指标 + 同一周期，只能有一条数据
  -- 这个唯一索引同时是第 11 天"幂等"的兜底保障
  UNIQUE KEY uk_fact (region_code, indicator_code, period_code)

  -- ===========================================================
  -- ★ 第 6-8 天的实验：这里暂时"故意"不建额外索引 ★
  --
  -- 大屏聚合查询形如：
  --   WHERE period_type = 'Y' AND category = '工业' AND period_code IN (...)
  -- period_type 不在 uk_fact 的最左前缀里，所以这个查询会走全表扫描。
  --
  -- 灌完 10 万行数据后，先用 EXPLAIN 看执行计划并测耗时，
  -- 然后再执行文件末尾的「第 6-8 天 · 加索引」语句，重新对比。
  -- 这就是你简历上"索引优化"那一条的真实来源。
  -- ===========================================================
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标值事实表';

-- -------------------------------------------------------------
-- 5. 异步任务表（第 11-12 天用）
--    biz_key 唯一索引 = 幂等的兜底保障（防并发重复插入）
-- -------------------------------------------------------------
DROP TABLE IF EXISTS async_task;
CREATE TABLE async_task (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_type     VARCHAR(32)  NOT NULL                COMMENT '任务类型：EXCEL_IMPORT',
  biz_key       VARCHAR(128) NOT NULL                COMMENT '幂等键：文件MD5 或 业务唯一标识',
  file_name     VARCHAR(255) DEFAULT NULL            COMMENT '原始文件名',
  file_path     VARCHAR(500) DEFAULT NULL            COMMENT '文件存储路径',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                COMMENT '状态：PENDING待执行 RUNNING执行中 SUCCESS成功 FAILED失败',
  retry_count   INT          NOT NULL DEFAULT 0      COMMENT '已重试次数',
  total_rows    INT          DEFAULT NULL            COMMENT '总行数',
  success_rows  INT          DEFAULT NULL            COMMENT '成功行数',
  error_msg     VARCHAR(1000) DEFAULT NULL           COMMENT '失败原因',
  next_retry_time DATETIME   DEFAULT NULL            COMMENT '下次重试时间（指数退避用）',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_key (biz_key),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步任务表';


-- =============================================================
-- 基础数据（让服务能立刻跑起来）
-- =============================================================

INSERT INTO dim_region (region_code, region_name, parent_code, region_level, sort) VALUES
('430000', '湖南省',        NULL,     1, 1),
('431300', '娄底市',        '430000', 2, 1),
('431302', '娄星区',        '431300', 3, 1),
('431321', '双峰县',        '431300', 3, 2),
('431322', '新化县',        '431300', 3, 3),
('431381', '冷水江市',      '431300', 3, 4),
('431382', '涟源市',        '431300', 3, 5);

INSERT INTO dim_indicator (indicator_code, indicator_name, parent_code, category, unit, value_type, sort) VALUES
('1',       '综合经济',      NULL,    '综合经济', NULL,   1, 1),
('1-1',     'GDP',           '1',     '综合经济', '亿元', 3, 1),
('1-1-1',   '第一产业增加值', '1-1',   '综合经济', '亿元', 3, 1),
('1-1-2',   '第二产业增加值', '1-1',   '综合经济', '亿元', 3, 2),
('1-1-3',   '第三产业增加值', '1-1',   '综合经济', '亿元', 3, 3),
('1-2',     'GDP增速',       '1',     '综合经济', '%',    2, 2),
('2',       '工业',          NULL,    '工业',     NULL,   1, 2),
('2-1',     '规模以上工业增加值', '2', '工业',     '亿元', 3, 1),
('2-2',     '规上工业企业数', '2',    '工业',     '家',   1, 2),
('2-3',     '工业用电量',     '2',    '工业',     '万千瓦时', 1, 3),
('3',       '财政',          NULL,    '财政',     NULL,   1, 3),
('3-1',     '一般公共预算收入', '3',  '财政',     '亿元', 3, 1),
('3-2',     '税收收入',       '3',    '财政',     '亿元', 3, 2),
('4',       '民生',          NULL,    '民生',     NULL,   1, 4),
('4-1',     '城镇居民人均可支配收入', '4', '民生', '元', 1, 1),
('4-2',     '社会消费品零售总额', '4', '民生',   '亿元', 3, 2),
('5',       '投资',          NULL,    '投资',     NULL,   1, 5),
('5-1',     '固定资产投资增速', '5',   '投资',     '%',    2, 1);

-- 生成 2021-2026 的季度周期（Q1: 1/1-3/31，Q2: 4/1-6/30，Q3: 7/1-9/30，Q4: 10/1-12/31）
INSERT INTO dim_period (period_code, period_type, period_year, period_index, start_date, end_date)
SELECT CONCAT(y, 'Q', q), 'Q', y, q,
       MAKEDATE(y, 1) + INTERVAL ((q - 1) * 3) MONTH,
       LAST_DAY(MAKEDATE(y, 1) + INTERVAL ((q - 1) * 3 + 2) MONTH)
FROM (SELECT 2021 y UNION SELECT 2022 UNION SELECT 2023 UNION SELECT 2024 UNION SELECT 2025 UNION SELECT 2026) ys
CROSS JOIN (SELECT 1 q UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) qs;

-- 生成 2021-2026 的年度周期
INSERT INTO dim_period (period_code, period_type, period_year, period_index, start_date, end_date)
SELECT CAST(y AS CHAR), 'Y', y, 1,
       MAKEDATE(y, 1),
       LAST_DAY(MAKEDATE(y, 1) + INTERVAL 11 MONTH)
FROM (SELECT 2021 y UNION SELECT 2022 UNION SELECT 2023 UNION SELECT 2024 UNION SELECT 2025 UNION SELECT 2026) ys;


-- =============================================================
-- ★ 第 6-8 天 · 加索引（先别执行！等测完优化前耗时再执行）
-- =============================================================
--
-- 执行前先记录：
--   EXPLAIN SELECT f.region_code, f.period_code, SUM(f.indicator_value)
--   FROM fact_indicator_value f
--   JOIN dim_indicator i ON i.indicator_code = f.indicator_code
--   WHERE f.period_type = 'Y' AND i.category = '工业' AND f.period_code IN ('2024','2025','2026')
--   GROUP BY f.region_code, f.period_code;
--
-- 记录下 type / key / rows 三个字段，以及实际耗时。
-- 然后再执行下面这行，重复上面的 EXPLAIN，做前后对比。
--
-- ALTER TABLE fact_indicator_value
--   ADD INDEX idx_type_period_indicator (period_type, period_code, indicator_code, region_code);

-- 可选进阶（做完基础版可以试）：
-- 1) 试试把索引列顺序换一下，看执行计划有什么变化，为什么
-- 2) 试试能不能做成"覆盖索引"，避免回表
-- 3) 试试 dim_indicator 的 category 上加索引，看有没有效果

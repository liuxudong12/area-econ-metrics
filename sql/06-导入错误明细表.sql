-- =============================================================
-- 导入错误明细表
-- 目标：把「哪一行错了、错在哪、原始值是什么」完整存下来
-- =============================================================
USE area_econ;

-- -------------------------------------------------------------
-- 0. async_task 补一列 error_rows
--
-- 为什么要在任务表上单独记一个"错误行数"？
--   因为 import_error_row 里存的是**明细**，而明细有上限（默认 10000 条）。
--   截断之后，明细条数 != 真实错误行数。
--   用户必须知道"到底错了多少行"，这个数字不能因为明细存不下而变小 ——
--   否则他会以为"改完这 1 万行就好了"，结果重传还是失败。
--
--   （这一列也让任务列表页能直接显示"3000 行有误"，不用再 JOIN 明细表做 count。）
--
-- ⚠️ 这条 ALTER 只需要执行一次。重复执行会报 "Duplicate column name 'error_rows'"。
-- -------------------------------------------------------------
ALTER TABLE async_task
  ADD COLUMN error_rows INT NOT NULL DEFAULT 0 COMMENT '校验失败的行数（真实总数，不受明细上限影响）';

DROP TABLE IF EXISTS import_error_row;
CREATE TABLE import_error_row (
  id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_id          BIGINT       NOT NULL                COMMENT '所属导入任务 async_task.id',
  row_num          INT          NOT NULL                COMMENT 'Excel 文件里的行号（含表头，从 1 开始，和用户在 Excel 里看到的一致）',

  -- ------------------------------------------------------------------
  -- 下面 5 列 = Excel 模板里用户填的 5 个字段，**原样存文本**
  --
  -- ★ 为什么 indicator_value 是 VARCHAR 而不是 DECIMAL(20,4)？
  --   因为这一行之所以成为"错误行"，很可能就是因为这一列填了 "abc"、"1.5万"、"12,000"。
  --   如果建表时写 DECIMAL，那么"插入一条错误记录"这个动作本身就会失败 ——
  --   **记录错误的表，不能假设错误数据符合 schema。**
  --   这是错误处理设计里最容易忽略的一条。
  --
  -- ★ 为什么 region_code / indicator_code 也用 VARCHAR(64) 而不是 VARCHAR(32)？
  --   正常数据是 32 位够了，但错误数据可能被用户填成 200 个字符。
  --   如果和主表一样窄，记录错误时又会撞"字段超长"。
  --   写入前代码里还会再截断一次（双保险）。
  -- ------------------------------------------------------------------
  region_code      VARCHAR(64)  DEFAULT NULL            COMMENT '用户填的区域编码原值',
  indicator_code   VARCHAR(64)  DEFAULT NULL            COMMENT '用户填的指标编码原值',
  period_code      VARCHAR(64)  DEFAULT NULL            COMMENT '用户填的周期编码原值',
  period_type      VARCHAR(32)  DEFAULT NULL            COMMENT '用户填的周期类型原值',
  indicator_value  VARCHAR(64)  DEFAULT NULL            COMMENT '用户填的指标值原值（可能不是合法数字）',

  -- ------------------------------------------------------------------
  -- 错误分类。单独一列的价值：可以 GROUP BY 做统计
  --   "本次导入 3000 行错误中，89% 是周期类型填成了中文"
  --   这句话比"有 3000 行错了"有用一百倍 —— 它直接告诉用户该改哪里。
  -- ------------------------------------------------------------------
  error_type       VARCHAR(32)  NOT NULL                COMMENT '错误类型枚举，见 ImportErrorType',
  error_msg        VARCHAR(500) NOT NULL                COMMENT '给人看的详细原因',

  create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),

  -- 按任务查（下载错误报告、统计都用它打头）
  KEY idx_task (task_id),

  -- 覆盖索引：统计接口只查 error_type + count，
  -- 走这个索引可以"索引覆盖"，不回表
  KEY idx_task_type (task_id, error_type)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Excel 导入错误行明细';

-- -------------------------------------------------------------
-- 验证：建表成功
-- -------------------------------------------------------------
SELECT COUNT(*) AS should_be_0 FROM import_error_row;

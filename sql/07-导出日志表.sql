-- =============================================================================
-- 导出日志表
--
-- 【为什么"只读"的操作也要记日志】
-- 因为导出是典型的「便宜接口、昂贵资源」—— 用户点一下，数据库就要顺序吐出几十万行，
-- 应用要生成几十 MB 的 Excel，网络要传几十 MB。
-- 一旦线上卡顿，第一个要回答的问题是"刚才谁在导出？导了多少？"
-- 没有这张表，你只能去翻应用日志（还未必留得住），或者靠猜。
--
-- 这张表要能回答四个问题：
--   ① 谁在导（client_ip）
--   ② 导的什么（export_type + param_json）
--   ③ 导了多少（total_rows / sheet_count）
--   ④ 花了多久、成没成功（cost_ms / status / error_msg）
--
-- 【为什么 param_json 用 VARCHAR 而不是 JSON 类型】
-- MySQL 5.7+ 确实有 JSON 类型，能校验格式、能按路径查询。
-- 但这里的需求只是"出问题时能看懂用户提了什么条件"，不需要在 SQL 里查 JSON 内部字段。
-- 用 VARCHAR(500) 更简单，也不受 MySQL 版本限制。
-- **功能用不到的东西，就不要引入它的复杂度。**（真要做审计分析再换 JSON 不迟）
--
-- 【为什么不记录导出的文件内容/路径】
-- 因为我们压根不落盘：Excel 是边查边写、直接写进 HTTP 响应的输出流。
-- 不落盘的好处：省磁盘、省一次全量读写、也不会有"临时文件清理"的运维负担。
-- 代价：出了问题没法"重放"那个文件 —— 但日志里有参数，用户重导一次即可。
-- =============================================================================

USE area_econ;

DROP TABLE IF EXISTS export_log;
CREATE TABLE export_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  export_type  VARCHAR(32)  NOT NULL                COMMENT '导出类型：FACT / STAT_CATEGORY / STAT_TREND / STAT_TOP / STAT_YOY / DICT_REGION / DICT_INDICATOR / DICT_PERIOD',
  param_json   VARCHAR(500) DEFAULT NULL            COMMENT '请求参数快照，排查问题用',
  file_name    VARCHAR(200) DEFAULT NULL            COMMENT '返回给用户的文件名',
  total_rows   INT          NOT NULL DEFAULT 0      COMMENT '导出的数据行数（不含表头）',
  sheet_count  INT          NOT NULL DEFAULT 0      COMMENT '生成了几个 sheet（超 104 万行会切分）',
  cost_ms      INT          NOT NULL DEFAULT 0      COMMENT '从开始取数到写完文件的耗时',
  status       VARCHAR(16)  NOT NULL                COMMENT 'SUCCESS 成功 / FAILED 失败 / REJECTED 被限流或超上限拒绝',
  error_msg    VARCHAR(500) DEFAULT NULL            COMMENT '失败原因',
  client_ip    VARCHAR(64)  DEFAULT NULL            COMMENT '发起导出的客户端 IP',
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  -- 排查时最常见的两个查询：
  --   "最近一小时谁在导出"  → WHERE create_time > ?
  --   "FACT 导出最近都导了多少" → WHERE export_type = ? AND create_time > ?
  -- 联合索引把最左列设成 export_type，是因为按类型查的区分度更高；
  -- 只按时间查的走不到这个索引，所以另建一个单列时间索引。
  KEY idx_type_time (export_type, create_time),
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出操作日志';

-- 验证
SELECT COUNT(*) AS log_rows FROM export_log;

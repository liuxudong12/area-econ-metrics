-- =============================================================================
-- 行级数据权限
--
-- 【这一天的需求和前 12 天完全不同】
-- 前 12 天解决的都还是"让功能跑通、跑快、跑稳"。
-- 数据权限解决的是另一个维度的问题：**同一个接口，不同的人看到的数据不一样**。
--
-- 举个大白话的例子：
--   长沙市的管理员和张家界市的管理员，打开的是同一个"区域经济指标"大屏，
--   调的是同一个 /api/stat/category-summary 接口，
--   但长沙的人只能看到长沙 11 个区县，张家界的人只能看到张家界 11 个区县。
--   谁都不能看到对方的。
--
-- 【为什么这件事必须做，而且必须在后端做】
-- 前端藏菜单、隐藏按钮，那叫"界面友好度"，不叫权限。
-- 打开浏览器 F12，把菜单那行 div 删掉，接口地址还是能直接敲的。
--   前端做的是"不让进门"，后端做的才是"进门了也看不见东西"。
-- 真实项目里数据越权是甲方验收必查项，也是等保测评的高频扣分点。
--
-- 【这张表为什么这么简单】
-- 完整的企业级权限有 RBAC（用户-角色-权限点）+ 数据范围 + 字段级脱敏，能做一个月。
-- 我们只做最核心的那一层：**用户 → 数据范围**。
--   data_scope = ALL     → 不看辖区，全省/全量
--   data_scope = REGION  → 只看 region_code 前缀匹配的行
-- 这两档就足以覆盖真实项目 80% 的场景（省级管理员 / 地市管理员 / 区县管理员
-- 其实都是 REGION，只是 region_code 的取值范围不同）。
--
-- 【为什么用"前缀"而不是"等于"】
-- 区域编码是层级编码，上级天然是下级的前缀：
--   43 0000 湖南省  →  43 01 00 长沙市  →  43 01 02 芙蓉区
-- 所以 "4301" 这一个值，天然覆盖了长沙市的全部区县，不需要再建一张
-- "用户-区域"关联表去枚举 11 个编码。
-- 代价是：区域编码必须严格按国家标准（GB/T 2260）分段，
-- 自己瞎编的编码体系用不了这招。
-- =============================================================================

USE area_econ;

DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
  id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(32) NOT NULL                COMMENT '登录名',
  display_name  VARCHAR(64) NOT NULL                COMMENT '姓名',
  role_code     VARCHAR(32) NOT NULL                COMMENT '角色编码（仅用于展示与日志，本项目的权限判定只看 data_scope）',
  data_scope    VARCHAR(16) NOT NULL                COMMENT '数据范围：ALL 全部 / REGION 按辖区',
  region_code   VARCHAR(32) DEFAULT NULL            COMMENT '辖区编码前缀，如 43（全省）、4301（长沙市）、430102（单区县）。data_scope=ALL 时忽略',
  status        TINYINT     NOT NULL DEFAULT 1      COMMENT '1 启用 / 0 停用',
  deleted       TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除',
  create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (id),
  -- 登录名唯一。登录功能本项目没做（不在今天的目标里），但唯一索引先建好，
  -- 等以后接入登录时不用改表结构。**约束是越早加越便宜**，等有脏数据再加就晚了。
  UNIQUE KEY uk_username (username),
  -- 按辖区反查"这个区有哪些管理员"，做数据推送/通知时会用到
  KEY idx_region (region_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '系统用户';

-- -----------------------------------------------------------------------------
-- 测试用户：5 个人，覆盖 5 种必须验证的场景
--
-- ⚠️ 第 5 个用户是关键：data_scope = REGION，但 region_code 是 NULL。
--    这是真实项目里最常见的配置事故 —— 新建用户时忘了选辖区。
--    此时系统只有两种可能的行为：
--      A. 拼不出过滤条件 → 不过滤 → **他看到了全省所有数据**（越权，而且是静默的）
--      B. 明确视为"没有可看的数据" → 返回 0 行（fail-closed）
--    正确答案是 B。为什么会写成 A？因为代码常写成：
--        if (user.getRegionCode() != null) { 加过滤条件 }
--    —— 少了个 else。这一行省略的后果是"新员工一入职就能看全省"。
--    手册里的实验 E5 就是专门验证这一条的。
-- -----------------------------------------------------------------------------
INSERT INTO sys_user (username, display_name, role_code, data_scope, region_code)
VALUES ('admin',       '省厅管理员（全量）',     'ADMIN',           'ALL',    '430000'),
       ('changsha',    '长沙市管理员',           'CITY_ADMIN',      'REGION', '4301'),
       ('yuelu',       '长沙第2区管理员',        'DISTRICT_ADMIN',  'REGION', '430102'),
       ('zhangjiajie', '张家界市管理员',         'CITY_ADMIN',      'REGION', '4308'),
       ('viewer',      '省厅只读（未配辖区）',   'VIEWER',          'REGION', NULL)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name),
                        role_code    = VALUES(role_code),
                        data_scope   = VALUES(data_scope),
                        region_code  = VALUES(region_code),
                        status       = 1,
                        deleted      = 0;

-- =============================================================================
-- 验证：查一下每个用户"应该"能看到多少行
--
-- 这段 SQL 是给手册里的实测做对照用的。它模拟的正是拦截器要做的事：
--   data_scope = ALL    → 不加条件
--   data_scope = REGION → region_code LIKE concat(辖区, '%')
--   辖区为 NULL         → 0 行
-- =============================================================================
SELECT u.username,
       u.data_scope,
       u.region_code,
       (SELECT COUNT(*)
          FROM fact_indicator_value f
         WHERE f.deleted = 0
           AND (u.data_scope = 'ALL'
             OR (u.region_code IS NOT NULL
                 AND f.region_code LIKE CONCAT(u.region_code, '%')))) AS visible_rows
FROM sys_user u
WHERE u.deleted = 0
ORDER BY u.id;

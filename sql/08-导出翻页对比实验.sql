-- =============================================================================
-- Day 12 实验 A：OFFSET 深翻页 vs 游标分页（完整跑一遍全表导出）
--
-- 【为什么要用存储过程，而不是在命令行里手敲几十条 SQL】
-- 因为"一页 2000 条、全表 48.96 万行"要跑 245 页。
-- 手敲不可能，写脚本循环又会把"客户端启动开销"算进去（Windows 上 mysql.exe
-- 启动一次就 50~100ms，245 次就是 20 秒，把真正要测的差异全淹了）。
--
-- 存储过程在**服务端**跑，计时的 NOW(6) 也是服务端的微秒时钟，
-- 所以测出来的就是纯 SQL 执行时间，没有客户端和网络的干扰。
-- **测性能时，先把"不是你要测的东西"排除掉，否则数字没意义。**
--
-- 用法：
--   mysql ... < sql/08-导出翻页对比实验.sql
-- =============================================================================

USE area_econ;

DROP PROCEDURE IF EXISTS bench_export_paging;

DELIMITER $$

CREATE PROCEDURE bench_export_paging(
    IN  mode      VARCHAR(16),   -- 'offset' 或 'cursor'
    IN  page_size INT,           -- 每页条数
    OUT total_ms  DECIMAL(12,2), -- 总耗时（毫秒）
    OUT total_rows BIGINT,       -- 一共取了多少行
    OUT pages     INT            -- 一共翻了多少页
)
BEGIN
    DECLARE off     INT DEFAULT 0;
    DECLARE cnt     BIGINT DEFAULT 0;
    DECLARE last_id BIGINT DEFAULT 0;
    DECLARE t0      DATETIME(6);

    SET total_ms = 0;
    SET total_rows = 0;
    SET pages = 0;

    -- 计时从"第一页开始查"算起，不含过程创建等开销
    SET t0 = NOW(6);

    IF mode = 'offset' THEN
        -- ---------------------------------------------------------------
        -- 反面教材：LIMIT offset, size
        -- 每一页的代价是 O(offset)：第 245 页要先数出 48.8 万行再丢掉。
        -- ---------------------------------------------------------------
        REPEAT
            SELECT COUNT(*) INTO cnt FROM (
                SELECT id
                FROM fact_indicator_value
                WHERE deleted = 0
                ORDER BY id
                LIMIT off, page_size
            ) t;
            SET total_rows = total_rows + cnt;
            SET off = off + page_size;
            SET pages = pages + 1;
        UNTIL cnt < page_size END REPEAT;
    ELSE
        -- ---------------------------------------------------------------
        -- 正确做法：游标（keyset）分页
        -- WHERE id > 上一页最后一个 id
        -- 每一页的代价恒定：直接在 B+ 树上定位，往后再取 page_size 行。
        -- ---------------------------------------------------------------
        REPEAT
            SELECT COUNT(*), IFNULL(MAX(id), last_id) INTO cnt, last_id FROM (
                SELECT id
                FROM fact_indicator_value
                WHERE deleted = 0
                  AND id > last_id
                ORDER BY id
                LIMIT page_size
            ) t;
            SET total_rows = total_rows + cnt;
            SET pages = pages + 1;
        UNTIL cnt < page_size END REPEAT;
    END IF;

    SET total_ms = TIMESTAMPDIFF(MICROSECOND, t0, NOW(6)) / 1000;
END$$

DELIMITER ;

-- 先跑一遍"暖机"，把磁盘页读进 buffer pool。
-- 不暖机的话，第一次跑的数字里混着大量物理 IO，第二次跑就快很多 ——
-- 那不是算法差异，是缓存差异。**对比实验一定要要么都冷、要么都热。**
CALL bench_export_paging('cursor', 2000, @ms, @rows, @pages);
CALL bench_export_paging('offset', 2000, @ms, @rows, @pages);

-- ---------- 正式测：游标 ----------
CALL bench_export_paging('cursor', 2000, @cur_ms, @cur_rows, @cur_pages);
SELECT '游标分页' AS 方式, @cur_pages AS 页数, @cur_rows AS 行数, @cur_ms AS 耗时毫秒;

-- ---------- 正式测：OFFSET ----------
CALL bench_export_paging('offset', 2000, @off_ms, @off_rows, @off_pages);
SELECT 'OFFSET 分页' AS 方式, @off_pages AS 页数, @off_rows AS 行数, @off_ms AS 耗时毫秒;

-- ---------- 结论 ----------
SELECT '倍数（OFFSET / 游标）' AS 指标,
       ROUND(@off_ms / GREATEST(@cur_ms, 0.001), 1) AS 倍数,
       IF(@off_rows = @cur_rows, '两者取到的行数一致', '⚠️ 行数不一致，实验无效') AS 校验;

-- 用完删掉，不留在库里
DROP PROCEDURE IF EXISTS bench_export_paging;

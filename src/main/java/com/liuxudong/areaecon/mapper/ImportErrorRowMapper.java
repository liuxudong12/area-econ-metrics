package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.ImportErrorRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ImportErrorRowMapper extends BaseMapper<ImportErrorRow> {

    /**
     * 批量插入错误行。
     *
     * 【为什么不像 fact 表那样用 upsert】
     * fact 表用 upsert 是因为"重试要能重入"——同一批数据写两遍不能变成两倍。
     * 错误表不需要：每次开始执行任务前，先 `DELETE FROM import_error_row WHERE task_id=?`
     * 把上一轮的清掉，然后重新写。错误明细是"当前这一轮的快照"，不是累积账本。
     * 这样比 upsert 简单得多，语义也更清楚。
     *
     * ⚠️ 和 insertBatch 一样的坑：空列表会拼出非法的 SQL（VALUES 后面什么都没有），
     *    调用方必须先判空。
     */
    int insertBatch(@Param("list") List<ImportErrorRow> list);

    /**
     * 按任务 + 游标做**分批查询**，给导出用。
     *
     * 【为什么要分批，不能一次 select 全部？】
     * 一个 10 万行的文件全错，就有 10 万条错误记录。
     * 一次性查出来：MyBatis 要为每一行创建一个对象，10 万个对象加字段字符串，
     * 轻松吃掉 50~100MB 堆内存。同时有 3 个人在下载 = 300MB，直接 OOM。
     *
     * 用 id 游标翻页（WHERE id > lastId ORDER BY id LIMIT N）而不是 LIMIT offset, N：
     *   · offset 越大越慢：MySQL 要先扫描并丢弃 offset 行
     *   · 游标法始终走主键范围扫描，第 1 万页和第 1 页一样快
     * 这个技巧在大数据量分页里非常常用。
     *
     * @param taskId     任务 ID
     * @param lastId     上一页最后一行的 id，第一页传 0
     * @param limit      每页多少条
     * @param onlyErrors true = 只取错误行（导出错误报告）；false 暂无用途，保留扩展
     */
    List<ImportErrorRow> selectPageByCursor(@Param("taskId") Long taskId,
                                            @Param("lastId") Long lastId,
                                            @Param("limit") int limit);

    /**
     * 按错误类型统计。
     *
     * SQL 其实就是：
     *   SELECT error_type, COUNT(*) AS cnt
     *   FROM import_error_row WHERE task_id = ?
     *   GROUP BY error_type ORDER BY cnt DESC
     *
     * 返回 Map 而不是专门的 VO，是因为这个查询只有两列，
     * 建一个类反而啰嗦。真实项目里也常这么干（但也别滥用 ——
     * 一旦列数超过 3 个，就该建 VO 了，否则字段名全靠字符串硬编码）。
     *
     * 走 idx_task_type 复合索引，**索引覆盖**：查的两列都在索引里，不用回表。
     */
    List<Map<String, Object>> countByType(@Param("taskId") Long taskId);

    /** 某个任务的错误行数（用于展示"共 N 行有误"） */
    long countByTaskId(@Param("taskId") Long taskId);
}

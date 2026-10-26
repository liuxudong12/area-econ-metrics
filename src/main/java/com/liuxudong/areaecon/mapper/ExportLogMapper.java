package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.ExportLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导出日志 Mapper。
 *
 * 只有单表插入，所以用 BaseMapper 就够了 —— 一行 SQL 都不用写。
 *
 * 【为什么不必给它建"清理历史日志"的定时任务（今天不做）】
 * 真实项目里 export_log 会无限增长，需要定期清理（比如保留 90 天）。
 * 但那是运维层面的问题，可以用：
 *   ① 定时任务 DELETE ... WHERE create_time < NOW() - INTERVAL 90 DAY（分批删，别一次删太多）
 *   ② 表分区（按 create_time 月分区，直接 DROP PARTITION，比 DELETE 快几个数量级）
 *   ③ 干脆不存库，写日志文件 / 上报到监控系统
 * 本地练习项目数据量小，先不做 —— **但面试问"日志表越来越大怎么办"要知道这三条。**
 */
@Mapper
public interface ExportLogMapper extends BaseMapper<ExportLog> {
}

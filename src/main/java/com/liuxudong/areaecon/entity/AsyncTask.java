package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务表（async_task）。
 *
 * ==========================================================================
 * 【为什么异步任务一定要落库，不能只丢进内存的线程池？】
 * ==========================================================================
 * 这是整个"异步导入"里最该讲清楚的一点。
 *
 * 反面做法（很多人第一版都这么写）：
 *      ThreadPoolExecutor.execute(() -> doImport(file));
 *      return "提交成功";
 * 看起来没问题，但服务一重启（发版、OOM、断电），队列里还没跑的任务**全没了**。
 * 而用户手里已经拿到了"提交成功"的提示 —— 他会以为数据在导，其实什么都没发生。
 * 更糟的是：他下次再传，又会新建一个任务，永远不知道上一次丢了。
 *
 * 落库之后，任务有了**持久化的身份**：
 *   - 用户随时能查到"我那个任务到底怎么样了"
 *   - 服务重启后，可以扫描 PENDING / RUNNING 的"僵尸任务"重新投递（Day 10 做）
 *   - 出问题时有据可查：谁、什么时候、导了多少行、错在哪
 *
 * 一句话：**线程池只是"干活的"，任务表才是"记账的"。记账必须在数据库里。**
 *
 * ==========================================================================
 * 【biz_key 是干什么的】
 * ==========================================================================
 * 这张表里 {@code biz_key} 上有唯一索引 uk_biz_key（见 sql/schema.sql:127）。
 * 对 Excel 导入来说，它存的是**文件的 MD5**。
 *
 * 它是幂等的最后一道闸门：
 *   业务层先查"这个 MD5 有没有任务"，查不到就插入。
 *   但两个请求可能同时查到"没有"，然后都去插入 —— 这个"查完到插入"的间隙里，
 *   只有唯一索引能兜住。后提交的那个会抛 DuplicateKeyException，被我们捕获后转成
 *   "该文件已存在任务"。
 *
 * 面试时这句可以直接说：
 *   "我先用业务查询做第一道幂等，再用唯一索引做第二道兜底。
 *    只靠查是不够的 —— 查和插之间有窗口，高并发下必然会重复。"
 */
@Data
@TableName("async_task")
public class AsyncTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型，目前只有 EXCEL_IMPORT。以后加"报表导出""批量重算"都复用这张表 */
    private String taskType;

    /** 幂等键：文件 MD5 */
    private String bizKey;

    /** 原始文件名 */
    private String fileName;

    /** 文件落地路径。异步线程要靠它重新读文件，所以**必须持久化到磁盘** */
    private String filePath;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED，取值见 AsyncTaskStatus */
    private String status;

    /** 已重试次数（Day 10 用） */
    private Integer retryCount;

    /** 解析出的总行数 */
    private Integer totalRows;

    /** 成功写入的行数 */
    private Integer successRows;

    /** 失败原因。VARCHAR(1000)，所以写错误信息时要注意截断，别超长 */
    private String errorMsg;

    /** 下次重试时间（Day 10 的指数退避用） */
    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

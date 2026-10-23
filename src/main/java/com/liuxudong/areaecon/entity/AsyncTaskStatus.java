package com.liuxudong.areaecon.entity;

/**
 * 异步任务的状态常量。
 *
 * 状态机（这就是今天要画在纸上的那张图）：
 *
 *   PENDING ──提交线程池──→ RUNNING ──导入成功──→ SUCCESS
 *      ↑                        │
 *      │                        └──导入失败──→ 还有重试次数？
 *      │                                          ├── 有 → 算出 next_retry_time，回到 PENDING
 *      └──────────────────────────────────────────┘
 *                                                 └── 没有 → FAILED
 *
 * 【为什么用常量而不是枚举？】
 * 可以枚举，但枚举要在实体上配 @EnumValue 或写 TypeHandler 才能存成字符串，
 * 对一个"只有 4 个值、只在本项目用"的状态来说，成本不划算。
 *
 * 【为什么不用数字 0/1/2/3？】
 * 数字在数据库里根本看不懂。运维 DBA 半夜查表，看到 status=2 得翻代码；
 * 存字符串 'RUNNING' 一眼就懂。存储成本几乎可以忽略（VARCHAR(16)），
 * 可读性收益是长期的。
 *
 * 这也是一条通用经验：**状态、类型这类字段，能用有意义的字符串就别用魔法数字。**
 */
public final class AsyncTaskStatus {

    private AsyncTaskStatus() {
    }

    /** 已建单，等待线程池执行 */
    public static final String PENDING = "PENDING";

    /** 正在执行 */
    public static final String RUNNING = "RUNNING";

    /** 执行成功 */
    public static final String SUCCESS = "SUCCESS";

    /** 执行失败（重试耗尽，或遇到不可重试的错误） */
    public static final String FAILED = "FAILED";

    /** 任务类型 */
    public static final String TYPE_EXCEL_IMPORT = "EXCEL_IMPORT";

    /** 判断是不是"还在跑"的状态（PENDING 也算，因为用户看到的就是"还没好"） */
    public static boolean isRunning(String status) {
        return PENDING.equals(status) || RUNNING.equals(status);
    }
}

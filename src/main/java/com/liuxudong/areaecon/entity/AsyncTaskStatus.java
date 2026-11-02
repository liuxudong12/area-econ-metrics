package com.liuxudong.areaecon.entity;

/**
 * 异步任务的状态常量。
 *
 * ==========================================================================
 * 【状态机（含重试回路）】
 * ==========================================================================
 *
 *   PENDING ──线程池抢到──→ RUNNING ──成功──→ SUCCESS
 *      ↑                       │
 *      │                       │ 失败
 *      │                       ↓
 *      │              ┌─────────────────────────┐
 *      │              │ 是临时故障 且 还有次数？ │
 *      │              └──────────┬──────────────┘
 *      │                 有 ─────┤───── 没有 / 确定性失败
 *      │                  ↓                  ↓
 *      │            RETRY_WAIT            FAILED
 *      │                  │                  │
 *      │  （调度器扫到 next_retry_time       └─ 用户可调
 *      └──  到期，抢占式改成 PENDING 重投）     POST /task/{id}/retry 人工重试
 *
 *   RUNNING ──超时没动静（服务重启 / 线程被杀）──→ 僵尸回收 ──→ RETRY_WAIT 或 FAILED
 *
 *
 * ==========================================================================
 * 【为什么加了 RETRY_WAIT，而不是直接用 PENDING 表示"等着重试"】
 * ==========================================================================
 * 因为两者的**语义不同，排障时的含义也完全不同**：
 *
 *   PENDING     = "我刚建好，正在排队等线程池"（正常，用户就该等）
 *   RETRY_WAIT  = "我失败过一次，正在等退避时间到"（异常，但在自动恢复中）
 *
 * 如果混用 PENDING，运维看到一堆 PENDING 会以为"队列堵了"，
 * 实际上它们是"失败后在等 1/2/4 秒"。
 * 用户侧也一样：接口返回"任务排队中"和"上次失败，1 秒后自动重试"，
 * 体验差别巨大。
 *
 * **状态字段的价值不在于能用，而在于能让人一眼看懂"它现在到底卡在哪一步"。**
 * 这也是为什么状态用字符串（PENDING）而不是数字（0/1/2/3）——
 * 运维 DBA 半夜查表，看到 status=2 得翻代码，看到 'RUNNING' 一眼就懂。
 *
 * ==========================================================================
 * 【为什么用常量而不是枚举？】
 * ==========================================================================
 * 可以枚举，但枚举要在实体上配 @EnumValue 或写 TypeHandler 才能存成字符串，
 * 对一个"只有 5 个值、只在本项目用"的状态来说，成本不划算。
 */
public final class AsyncTaskStatus {

    private AsyncTaskStatus() {
    }

    /** 已建单，等待线程池执行（首次执行） */
    public static final String PENDING = "PENDING";

    /** 正在执行 */
    public static final String RUNNING = "RUNNING";

    /**
     * 失败过一次，正在等 next_retry_time 到点后自动重试。
     *
     * 这个状态**不会**由用户操作产生，只由后台的失败处理逻辑写入。
     */
    public static final String RETRY_WAIT = "RETRY_WAIT";

    /** 执行成功 */
    public static final String SUCCESS = "SUCCESS";

    /** 执行失败（重试耗尽，或遇到不可重试的错误） */
    public static final String FAILED = "FAILED";

    /** 任务类型 */
    public static final String TYPE_EXCEL_IMPORT = "EXCEL_IMPORT";

    /** 判断是不是"还没好"的状态（PENDING / RUNNING / RETRY_WAIT 都算） */
    public static boolean isRunning(String status) {
        return PENDING.equals(status) || RUNNING.equals(status) || RETRY_WAIT.equals(status);
    }
}

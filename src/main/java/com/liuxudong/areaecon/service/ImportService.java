package com.liuxudong.areaecon.service;

import com.liuxudong.areaecon.controller.vo.ImportResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 指标值 Excel 导入。
 *
 * 【两个方法，对应两种截然不同的设计思路】
 *
 *   importSync  —— 同步阻塞。用户上传后**站在这里等**，等到 1 万行全部入库才返回。
 *                  优点：简单，看到返回就知道结果。
 *                  缺点：等得住吗？实测见手册 —— 这就是我们要改造它的原因。
 *
 *   importAsync —— 异步接单。用户上传后**立刻**拿到一个 taskId，后台慢慢跑。
 *                  优点：接口秒回，不会占着 Tomcat 线程，也不会被网关的超时掐断。
 *                  代价：要自己维护任务表、状态机、线程池、幂等、重试。
 *
 * 【这段的设计要点】
 *   "同一个功能我做了两版。第一版同步，1 万行要 X 秒，浏览器早就超时了，
 *    用户还会重复点击导致重复提交。第二版改成异步：接口只做三件事 ——
 *    存文件、算 MD5 去重、投递任务，200 毫秒内返回 taskId，
 *    前端轮询状态。这样既解决了超时，也用 MD5 天然解决了重复提交。"
 */
public interface ImportService {

    /**
     * 同步导入（改造前的版本，保留下来做性能对照）。
     *
     * @param file      上传的 xlsx
     * @param batchSize 每批写多少行。1 = 逐行插入（最慢），500 = 批量插入（快）
     *                  —— 这个参数就是用来做对比实验的，生产环境应该从配置读，不该暴露给前端
     */
    ImportResultVO importSync(MultipartFile file, int batchSize);

    /**
     * 异步导入 —— 只"接单"，不干活。
     *
     * 这个方法里**不会解析 Excel、不会插一条业务数据**，只做四件事：
     *   ① 校验文件
     *   ② 算 MD5（幂等键）
     *   ③ 查/建 async_task 记录
     *   ④ 把 taskId 丢进线程池
     * 然后立刻返回。真正的解析和入库在线程池的另一个线程里跑。
     *
     * 【为什么不在接口返回前就把状态改成 RUNNING】
     * 因为改了也没用 —— 用户拿到 response 时，后台可能还没开始跑。
     * 状态流转交给后台线程自己维护，接口只负责"接单+登记"。
     */
    ImportResultVO importAsync(MultipartFile file);

    /**
     * 查任务状态（前端轮询用）。
     *
     * @param taskId 异步接口返回的任务 ID
     */
    ImportResultVO getTask(Long taskId);

    /**
     * 把一条已落库的任务投递到线程池 —— 供重试调度器使用。
     *
     * 【为什么调度器不自己调线程池，非要绕一层接口？】
     * 因为"怎么执行一个任务"（解析、分批、事务、失败分类）是 ImportService 的内部知识。
     * 调度器只需要说一句"这条该跑了"，不应该关心实现。
     *
     * 如果哪天把执行引擎换成消息队列（RabbitMQ / RocketMQ），
     * 只需要改这个方法的实现，调度器一行都不用动 —— 这就是接口的价值。
     *
     * @param taskId 任务 ID
     * @throws java.util.concurrent.RejectedExecutionException 队列满时抛出，
     *         由调用方决定"退回等待"还是"直接报错"
     */
    void dispatch(Long taskId);

    /**
     * 手动重试 —— 把 FAILED / RETRY_WAIT 的任务重新投递。
     *
     * 【和自动重试的分工】
     *   自动重试：只处理"临时故障"，退避 1/2/4 秒，最多 3 次，全自动
     *   手动重试：由人触发，retry_count 归零，等于给任务一次全新的机会
     *
     * @param taskId 任务 ID
     * @return 重试后的任务状态（PENDING）
     */
    ImportResultVO retryTask(Long taskId);
}

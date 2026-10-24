package com.liuxudong.areaecon.service.impl;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.liuxudong.areaecon.common.BizException;
import com.liuxudong.areaecon.common.RetryableImportException;
import com.liuxudong.areaecon.config.ImportThreadPoolConfig;
import com.liuxudong.areaecon.controller.vo.FactImportRow;
import com.liuxudong.areaecon.controller.vo.ImportResultVO;
import com.liuxudong.areaecon.entity.AsyncTask;
import com.liuxudong.areaecon.entity.AsyncTaskStatus;
import com.liuxudong.areaecon.entity.FactIndicatorValue;
import com.liuxudong.areaecon.excel.FactImportListener;
import com.liuxudong.areaecon.mapper.AsyncTaskMapper;
import com.liuxudong.areaecon.mapper.FactIndicatorValueMapper;
import com.liuxudong.areaecon.service.ImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 导入服务：同步版（对照）+ 异步版（正式）。
 */
@Slf4j
@Service
public class ImportServiceImpl implements ImportService {

    private static final String DATA_SOURCE_IMPORT = "导入";

    /** async_task.error_msg 是 VARCHAR(1000)，留点余量 */
    private static final int ERROR_MSG_MAX = 950;

    /**
     * 【解析阶段】攒多少行进一次内存收集器。
     *
     * 注意这个数字和写库的 batchSize 是**两回事**：
     *   COLLECT_BATCH  控制"解析时多久往内存里搬一次"
     *   batchSize      控制"写库时一条 SQL 塞多少行"
     * 两者的作用点和优化目标完全不同，不要混。
     */
    private static final int COLLECT_BATCH = 2000;

    private final FactIndicatorValueMapper factIndicatorValueMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ThreadPoolExecutor importExecutor;
    /** 编程式事务模板：用来给"每一批"单独开事务 */
    private final TransactionTemplate transactionTemplate;

    @Value("${stat.import.max-rows:50000}")
    private int maxRows;

    @Value("${stat.import.batch-size:500}")
    private int defaultBatchSize;

    @Value("${stat.import.dir:./data/import}")
    private String importDir;

    // ==========================================================================
    // Day 10 新增：重试相关的三个配置
    // ==========================================================================

    /**
     * 最多重试几次（不含第一次执行）。
     *
     * maxRetry=3 的含义是"总共最多跑 4 次"：首次 + 3 次重试。
     * 为什么是 3？—— 退避总时长是 1+2+4=7 秒，用户还等得起。
     * 如果设成 10，累计等待 1+2+4+8+...+512 ≈ 17 分钟，用户早就关页面了。
     * **重试次数的上限不是技术问题，是"用户愿意等多久"的问题。**
     */
    @Value("${stat.import.retry.max-retry:3}")
    private int maxRetry;

    /**
     * 退避时间表（秒）。第 N 次失败后等 backoffSeconds[N] 秒再试。
     *
     * 1,2,4 是"指数退避"（每次翻倍）加上封顶 —— 实际上这里长度只有 3，
     * 等于 maxRetry 次重试用完了，所以没体现封顶。真实项目常见的是
     * 1,2,4,8,16,...,60（封顶 60 秒），避免重试次数多时等待时间爆炸。
     *
     * 为什么是指数而不是固定 1 秒？
     * 因为"临时故障"通常有恢复过程：数据库连接池要重建、被 kill 的实例要重启、
     * 上游服务要摘掉故障节点。固定 1 秒会在这段时间里疯狂无效重试，
     * 反而给已经过载的系统加压（这叫"重试风暴"）。
     * 指数退避的直觉：**越失败越说明对方还没好，就越该少打扰它。**
     */
    @Value("${stat.import.retry.backoff-seconds:1,2,4}")
    private long[] backoffSeconds;

    /**
     * 【实验用】故障注入：跑到第几批时强制失败。-1 表示关闭。
     *
     * 这是为了做 Day 10 的对照组实验 —— 没有它就没法复现
     * "第 10 批写完、第 11 批断了"这个真实场景，也就拿不出"重试前 vs 重试后"的数字。
     *
     * ⚠️ 生产环境必须保持 -1。它默认就是 -1，不配就不会生效。
     *    真实项目里这类开关一般放在 test profile 里，或者用混沌工程工具
     *    （ChaosBlade / ToxiProxy）在基础设施层注入，不写进业务代码。
     *    这里写进来是因为：自建项目要让面试官看到"我知道怎么验证它"。
     */
    @Value("${stat.import.test.fail-at-batch:-1}")
    private int failAtBatch;

    public ImportServiceImpl(FactIndicatorValueMapper factIndicatorValueMapper,
                             AsyncTaskMapper asyncTaskMapper,
                             @Qualifier(ImportThreadPoolConfig.IMPORT_EXECUTOR) ThreadPoolExecutor importExecutor,
                             PlatformTransactionManager transactionManager) {
        this.factIndicatorValueMapper = factIndicatorValueMapper;
        this.asyncTaskMapper = asyncTaskMapper;
        this.importExecutor = importExecutor;
        // TransactionTemplate 是 Spring 提供的"编程式事务"工具：
        //   transactionTemplate.executeWithoutResult(status -> { ... });
        // 在大括号里的代码有事务保护，正常返回自动提交，抛异常自动回滚。
        //
        // 【为什么这里必须用它，不能用 @Transactional】
        // 因为"每批独立事务"是**在方法内部循环**决定的，@Transactional 只能包住整个方法。
        // 而且 @Transactional 靠 AOP 代理生效 —— 同类内部方法互调（this.method()）会绕过代理，
        // 注解直接失效，这是个极其常见的坑（表现是"明明加了注解却没回滚"）。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ==========================================================================
    // 一、同步导入（改造前）
    // ==========================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importSync(MultipartFile file, int batchSize) {
        long start = System.currentTimeMillis();
        validateFile(file);

        // ★ 关键设计：解析阶段**只收藏、不写库**。
        //   如果边解析边写，"第 8000 行错了"的时候前面 7999 行已经插进去了。
        //   虽然 @Transactional 能回滚，但那是靠事务兜底；而异步版分批提交，事务兜不住。
        //   所以统一改成"全部校验通过之后才开始写" —— 语义最简单：**要么全对，要么一行不写**。
        //
        //   代价：1 万行对象要暂存在内存里（约 3~5MB）。
        //   我们用 max-rows=50000 给内存设了上限。这是"原子性"和"内存"的交换，
        //   真实项目里如果要导 100 万行，就得改成"两遍扫描"（第一遍只校验，第二遍才写）。
        List<FactImportRow> allRows = new java.util.ArrayList<>();
        FactImportListener listener = new FactImportListener(COLLECT_BATCH, allRows::addAll);

        try (InputStream in = file.getInputStream()) {
            EasyExcel.read(in, FactImportRow.class, listener).sheet().doRead();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            log.error("[导入-同步] 解析或入库失败", e);
            throw new BizException("导入失败：" + cause.getMessage(), cause);
        }

        if (listener.getErrorRows() > 0) {
            // 此时还一行都没写 —— 不需要回滚，因为没有可回滚的东西
            throw new BizException("导入失败，共 " + listener.getErrorRows()
                    + " 行数据不合法，本次一行都未写入。明细：" + listener.errorSummary());
        }
        if (listener.getTotalRows() == 0) {
            throw new BizException("文件里没有读到任何数据行，请检查表头是否与模板一致"
                    + "（表头必须是：区域编码 / 指标编码 / 周期编码 / 周期类型 / 指标值）");
        }
        if (listener.getTotalRows() > maxRows) {
            throw new BizException("单次导入最多 " + maxRows + " 行，本文件有 " + listener.getTotalRows() + " 行");
        }

        // 校验全过 → 开始写库。batchSize 决定"逐行"还是"批量"
        writeAllInBatches(allRows, batchSize, new java.util.concurrent.atomic.AtomicInteger());

        long elapsed = System.currentTimeMillis() - start;
        ImportResultVO vo = new ImportResultVO();
        vo.setStatus(AsyncTaskStatus.SUCCESS);
        vo.setTotalRows(allRows.size());
        vo.setSuccessRows(allRows.size());
        vo.setErrorRows(0);
        vo.setErrors(java.util.Collections.emptyList());
        vo.setElapsedMs(elapsed);
        vo.setBatchSize(batchSize);
        vo.setMessage("导入成功，共 " + allRows.size() + " 行");

        log.info("[导入-同步] 完成。总行数={} 耗时={}ms batchSize={}",
                allRows.size(), elapsed, batchSize);
        return vo;
    }

    // ==========================================================================
    // 二、异步导入（正式版）
    // ==========================================================================

    /**
     * 异步导入。全程不解析 Excel、不写业务数据 —— 只做"接单登记"。
     *
     * 五个步骤，顺序很关键：
     *   ① 校验文件（便宜，先做）
     *   ② 算 MD5（要读整个文件，中等成本）
     *   ③ 查 biz_key 判重（一次索引查询，很便宜）
     *   ④ 存文件到磁盘（要写盘）
     *   ⑤ 建任务 + 提交线程池
     *
     * 为什么"判重"要放在"存文件"之前？
     *   因为重复文件占大多数 —— 用户点了两次"上传"、刷新了页面、网络超时后重试。
     *   先判重能省掉一次磁盘写。而且如果判重放后面，同一份 10MB 文件会被写两遍。
     *
     * 为什么存文件要在"建任务"之前？
     *   因为任务一旦落库，后台线程随时可能被唤醒去读文件。
     *   如果先建任务再存文件，存在一个极短的窗口：线程读到 taskId、去读文件路径、文件还不存在 → 失败。
     *   顺序错了就是偶发 bug。**"先准备好一切，再发信号"** 是并发编程的通用原则。
     */
    @Override
    public ImportResultVO importAsync(MultipartFile file) {
        long start = System.currentTimeMillis();
        validateFile(file);

        // ② MD5：内容指纹，同一个文件不管改多少次名字，MD5 都一样
        String md5 = md5Of(file);

        // ③ 第一道幂等：业务查询
        AsyncTask exist = findByBizKey(md5);
        if (exist != null) {
            log.info("[导入-异步] 命中已有任务，直接返回。taskId={} status={} md5={}",
                    exist.getId(), exist.getStatus(), md5);
            ImportResultVO vo = buildResult(exist);
            vo.setElapsedMs(System.currentTimeMillis() - start);
            return vo;
        }

        // ④ 存文件（用 MD5 当文件名，天然去重，也避免了用户文件名带来的安全问题）
        Path savedPath = saveFile(file, md5);

        // ⑤ 建任务
        AsyncTask task = new AsyncTask();
        task.setTaskType(AsyncTaskStatus.TYPE_EXCEL_IMPORT);
        task.setBizKey(md5);
        task.setFileName(file.getOriginalFilename());
        task.setFilePath(savedPath.toString());
        task.setStatus(AsyncTaskStatus.PENDING);
        task.setRetryCount(0);

        try {
            asyncTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // ★ 第二道幂等：唯一索引兜底。
            // 走到这里说明：两个请求几乎同时查到"没有这个 MD5"，然后都去插入，
            // 数据库只允许一个成功。这不是异常情况，是正常并发，所以只记 info。
            // 处理方式：把先插入的那条查出来，返回给用户 —— 对用户来说结果完全一样。
            log.info("[导入-异步] 并发插入撞唯一索引，返回已存在的任务。md5={}", md5);
            AsyncTask again = findByBizKey(md5);
            if (again == null) {
                // 理论上不可能（唯一索引冲突说明记录一定存在）。
                // 但如果对方在同一瞬间被删了，这里兜一下，别返回 null 让前端懵。
                throw new BizException("任务创建冲突，请重试");
            }
            ImportResultVO vo = buildResult(again);
            vo.setElapsedMs(System.currentTimeMillis() - start);
            return vo;
        }

        // ⑥ 提交线程池
        try {
            importExecutor.execute(() -> runTask(task.getId()));
        } catch (RejectedExecutionException e) {
            // 队列满了（AbortPolicy）。任务已经落库，必须把它标成 FAILED，
            // 否则会留下一条永远 PENDING 的"僵尸任务"，用户轮询到天荒地老。
            markFailed(task.getId(), "系统繁忙：导入队列已满，请稍后重试");
            log.warn("[导入-异步] 线程池拒绝。taskId={} active={} queue={}",
                    task.getId(), importExecutor.getActiveCount(), importExecutor.getQueue().size());
            throw new BizException("系统繁忙，导入任务较多，请稍后重试");
        }

        long elapsed = System.currentTimeMillis() - start;
        ImportResultVO vo = buildResult(task);
        vo.setElapsedMs(elapsed);
        vo.setMessage("任务已受理，正在后台导入。请用 taskId 轮询进度。");
        log.info("[导入-异步] 已受理。taskId={} 文件={} 大小={}KB 接单耗时={}ms",
                task.getId(), file.getOriginalFilename(), file.getSize() / 1024, elapsed);
        return vo;
    }

    @Override
    public ImportResultVO getTask(Long taskId) {
        if (taskId == null) {
            throw new BizException("taskId 不能为空");
        }
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("任务不存在：" + taskId);
        }
        return buildResult(task);
    }

    /**
     * 把一条已经落库的任务投递到线程池。
     *
     * 【为什么单独暴露这个方法，而不是让调度器自己 new 线程？】
     * 因为"怎么跑一个任务"是 ImportService 的内部知识（runTask 是 private）。
     * 调度器只应该知道"这条任务该跑了，你帮它排上队"，
     * 不应该知道"要不要加事务""解析用哪个 Listener""失败怎么分类"。
     *
     * **调用方只需要一个语义动作，实现细节不外泄** —— 这就是接口存在的意义。
     *
     * 抛出的 RejectedExecutionException 由调用方处理：
     *   - importAsync 里：直接告诉用户"系统繁忙"（用户会自己重试上传）
     *   - 调度器里：把任务退回 RETRY_WAIT 再等一轮（不能判死刑，队列满了不是数据的错）
     *
     * @param taskId 任务 ID
     */
    @Override
    public void dispatch(Long taskId) {
        importExecutor.execute(() -> runTask(taskId));
    }

    /**
     * 手动重试 —— 用户/运维在任务 FAILED 之后"再来一次"。
     *
     * 【为什么需要手动重试？自动重试不是已经有了吗】
     * 因为自动重试只能处理"临时故障"，而下面这些情况需要人来判断：
     *   · 数据库恢复后，昨天失败的那批数据要补进去
     *   · 用户改好了 Excel 里的错误行，重新上传 —— 但文件 MD5 变了？
     *     不，改了内容 MD5 就变了，会建新任务。真正需要重试的是"数据没改，
     *     当时是环境问题"的那一批
     *   · 运维临时扩了连接池，想把积压的失败任务一次性冲掉
     *
     * 【为什么要把 retry_count 归零？】
     * 自动重试的 retry_count 是"这一轮尝试的次数"。
     * 用户手动重试是**重新发起**，等于开了新的一轮，计数自然归零。
     * 如果不归零，一个失败过 3 次的任务手动重试后，
     * 下一次再遇到临时故障就直接 FAILED 了 —— 用户会莫名其妙："我刚点的重试啊"。
     *
     * 【为什么只允许 FAILED / RETRY_WAIT】
     *   SUCCESS  → 已经成功了，再跑一遍纯属浪费
     *   RUNNING  → 正在跑，两个线程同时跑就是重复导入
     *   PENDING  → 已经在队列里了，重试会把队列里的那份变成僵尸投递
     * 用 `where status in ('FAILED','RETRY_WAIT')` 做抢占，
     * 和 runTask 里抢 PENDING 是同一个套路 —— **状态机迁移必须带前置条件**。
     */
    @Override
    public ImportResultVO retryTask(Long taskId) {
        if (taskId == null) {
            throw new BizException("taskId 不能为空");
        }
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException("任务不存在：" + taskId);
        }

        // 先做一次友好提示（不是并发保护，只是让报错信息好看点）
        if (AsyncTaskStatus.SUCCESS.equals(task.getStatus())) {
            throw new BizException("该任务已经成功，无需重试");
        }
        if (AsyncTaskStatus.RUNNING.equals(task.getStatus())) {
            throw new BizException("该任务正在执行中，请稍后再看结果");
        }
        if (AsyncTaskStatus.PENDING.equals(task.getStatus())) {
            throw new BizException("该任务已在队列中，无需重复提交");
        }

        int updated = asyncTaskMapper.update(null,
                new LambdaUpdateWrapper<AsyncTask>()
                        .eq(AsyncTask::getId, taskId)
                        .in(AsyncTask::getStatus, AsyncTaskStatus.FAILED, AsyncTaskStatus.RETRY_WAIT)
                        .set(AsyncTask::getStatus, AsyncTaskStatus.PENDING)
                        .set(AsyncTask::getRetryCount, 0)
                        .set(AsyncTask::getNextRetryTime, null));

        // 真正的并发保护：上面 select 完之后，可能别的线程已经把它改成 RUNNING 了
        if (updated == 0) {
            throw new BizException("任务状态已变化，当前不允许重试，请刷新后查看");
        }

        try {
            dispatch(taskId);
        } catch (RejectedExecutionException e) {
            markFailed(taskId, "系统繁忙：导入队列已满，请稍后重试");
            log.warn("[导入-手动重试] 线程池拒绝 taskId={}", taskId);
            throw new BizException("系统繁忙，导入任务较多，请稍后重试");
        }

        log.info("[导入-手动重试] 已重新投递 taskId={} 之前失败次数={} 历史原因={}",
                taskId, task.getRetryCount(), task.getErrorMsg());

        // 重新查一次库，把最新的 PENDING 状态返回给前端
        return getTask(taskId);
    }

    // ==========================================================================
    // 三、后台真正干活的部分
    // ==========================================================================

    /**
     * 线程池里执行的任务。
     *
     * 【这个方法里的每一行都在"兜底"，因为它是最后一道防线】
     * 它跑在独立线程里，没有任何人接住它的异常 —— 抛出去就是线程池的
     * uncaughtExceptionHandler 打一行日志，任务永远停在 RUNNING。
     * 所以整个方法体必须是 try-catch 全包的，任何失败都要落成 FAILED 状态。
     *
     * 【为什么不加 @Transactional】
     * 因为这里要"分批提交"。加一个大事务就回到了同步版的老问题（长事务）。
     * 更关键的是：状态更新（RUNNING → SUCCESS）**不能**和业务数据在一个事务里。
     * 如果一起回滚，导入失败时状态会从 RUNNING 回滚成 RUNNING —— 看起来没变，
     * 实际上是这条 update 被撤销了，任务永远卡在 RUNNING。这是最难查的一类 bug。
     */
    private void runTask(Long taskId) {
        long start = System.currentTimeMillis();
        log.info("[导入-异步] 开始执行 taskId={} 线程={}", taskId, Thread.currentThread().getName());

        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("[导入-异步] 任务不存在，放弃执行 taskId={}", taskId);
            return;
        }

        // ==================================================================
        // 抢占式更新：只有"还没被谁抢走"的任务才能变成 RUNNING
        // ==================================================================
        // 为什么不能直接 updateById？
        //   同一个任务有可能被投递两次，三种真实场景：
        //     ① 调度器刚把它从 RETRY_WAIT 改成 PENDING 并投递，
        //        同一瞬间用户又点了"立即重试"
        //     ② 多实例部署时，两台机器的调度器同时扫到同一条到期任务
        //     ③ 线程池拒绝后重新入队，而老线程其实还活着
        //   如果两个线程都跑，就是"同一份文件导两遍"。
        //
        //   用 `update ... where status in (...)` 做抢占：数据库保证只有一个
        //   线程能把它改成 RUNNING（affected=1），另一个拿到 0。
        //   这是**无锁互斥**，比 synchronized / Redis 锁都轻 ——
        //   因为"判断 + 修改"在同一条 SQL 里原子完成（UPDATE 会加行锁）。
        //
        // ★ Day 10 顺手修了 Day 9 的一个隐蔽 bug：
        //   原来写的是
        //       AsyncTask running = new AsyncTask();
        //       running.setStatus(RUNNING);
        //       running.setNextRetryTime(null);          // ← 以为能清空
        //       asyncTaskMapper.update(running, wrapper);
        //   但 MyBatis-Plus 的 update 默认字段策略是 **NOT_NULL**：
        //   实体里为 null 的字段**不会进 SET 子句**。
        //   所以 next_retry_time 根本没被清空，旧值一直挂着。
        //
        //   这类 bug 的可怕之处：不报错、不抛异常、接口正常返回。
        //   表现是"重试成功的任务，next_retry_time 还留在过去某个时刻"，
        //   运维查表时会误判"它还在等重试"，白查半天。
        //
        //   修法：改用 LambdaUpdateWrapper 显式 .set(字段, null)。
        //   —— "想写 null 就必须显式声明"是 MyBatis-Plus 的一条铁律。
        int locked = asyncTaskMapper.update(null,
                new LambdaUpdateWrapper<AsyncTask>()
                        .eq(AsyncTask::getId, taskId)
                        .in(AsyncTask::getStatus, AsyncTaskStatus.PENDING, AsyncTaskStatus.RETRY_WAIT)
                        .set(AsyncTask::getStatus, AsyncTaskStatus.RUNNING)
                        .set(AsyncTask::getNextRetryTime, null));
        if (locked == 0) {
            log.warn("[导入-异步] 任务已被别人抢走或状态不允许执行，跳过 taskId={} status={}",
                    taskId, task.getStatus());
            return;
        }

        List<FactImportRow> allRows = new java.util.ArrayList<>();
        FactImportListener listener = new FactImportListener(COLLECT_BATCH, allRows::addAll);
        // 精确记账：真正提交进数据库的行数。
        // 校验失败时它是 0；写库中途失败时是"已提交的那几批的总和"。
        // 用户最关心"到底进去多少行"，这个数字必须真实。
        java.util.concurrent.atomic.AtomicInteger written = new java.util.concurrent.atomic.AtomicInteger();

        try (InputStream in = Files.newInputStream(Paths.get(task.getFilePath()))) {
            EasyExcel.read(in, FactImportRow.class, listener).sheet().doRead();

            if (listener.getErrorRows() > 0) {
                throw new BizException("共 " + listener.getErrorRows()
                        + " 行数据不合法，本次一行都未写入：" + listener.errorSummary());
            }
            if (listener.getTotalRows() == 0) {
                throw new BizException("文件里没有读到任何数据行，请检查表头");
            }
            if (listener.getTotalRows() > maxRows) {
                throw new BizException("单次导入最多 " + maxRows + " 行，本文件有 " + listener.getTotalRows() + " 行");
            }

            // 校验通过才开始写。这一步是"分批提交"——每 500 行一个独立事务。
            // 如果第 10 批写库失败（比如数据库连接断了），前 9 批已经提交、撤不回来了。
            // 这种"半截数据"由 Day 10 的"幂等重试"（upsert）来收尾，而不是靠事务。
            writeAllInBatches(allRows, defaultBatchSize, written);

            long elapsed = System.currentTimeMillis() - start;

            // 成功落账。这里同样用 UpdateWrapper 而不是 updateById，原因见上面那段
            // —— error_msg 和 next_retry_time 都要写成 null，updateById 写不进去。
            //
            // ★ 注意 retry_count **不清零**：它记录"这个任务历史上失败过几次"，
            //   是排查问题的证据。只有用户手动重试（retryTask）才会归零，
            //   因为那是"人重新发起了这件事"，语义上是新的开始。
            asyncTaskMapper.update(null,
                    new LambdaUpdateWrapper<AsyncTask>()
                            .eq(AsyncTask::getId, taskId)
                            .set(AsyncTask::getStatus, AsyncTaskStatus.SUCCESS)
                            .set(AsyncTask::getTotalRows, allRows.size())
                            .set(AsyncTask::getSuccessRows, allRows.size())
                            .set(AsyncTask::getErrorMsg, null)
                            .set(AsyncTask::getNextRetryTime, null));

            log.info("[导入-异步] 执行成功 taskId={} 总行数={} 耗时={}ms 历史失败次数={}",
                    taskId, allRows.size(), elapsed,
                    task.getRetryCount() == null ? 0 : task.getRetryCount());

        } catch (Exception e) {
            // ★ 所有失败都收敛到一个地方处理：分类 → 重试 / 终结。
            //   为什么不在 catch 里直接 sleep 然后重试？见 handleFailure 的注释。
            handleFailure(task, listener.getTotalRows(), written.get(), e);
        }
    }

    /**
     * ==========================================================================
     * Day 10 核心：失败之后到底怎么办
     * ==========================================================================
     *
     * 【★ 为什么不在 catch 里 sleep 1 秒然后自己重跑？—— 这是最容易写错的地方】
     *
     * 直觉写法：
     *     catch (Exception e) {
     *         for (int i = 0; i < 3; i++) {
     *             sleep(1000);
     *             try { doImport(); return; } catch (Exception ignore) {}
     *         }
     *     }
     * 这段代码在**单线程脚本**里是对的，在我们的线程池里是三重灾难：
     *
     *   ① 白白占着线程
     *      线程池 core=4。4 个任务都在 sleep 3 秒，等于 4 个线程全部瘫痪，
     *      第 5 个用户上传的文件连"接单"都排不上。
     *      **sleep 的线程和干活的线程是同一种资源，睡一秒就是浪费一秒的并发能力。**
     *
     *   ② 服务一重启，重试就没了
     *      重试的"计划"只存在于栈帧里。发版、OOM、断电 —— 全部丢失。
     *      用户还在轮询 taskId，永远等不到结果。
     *
     *   ③ 没法观测、没法干预
     *      运营想知道"现在有几个任务在等重试"，答案在某个线程的 for 循环里，
     *      你查不出来，也没法让它"立刻重试"或"别重试了"。
     *
     * 正确做法（本方法）：
     *     把"什么时候重试"**写进数据库**（status=RETRY_WAIT + next_retry_time），
     *     线程立刻释放，去干别的活。
     *     到点了由调度器扫出来重新投递（见 ImportRetryScheduler）。
     *
     * 一句话：**重试的"记忆"必须落库，不能留在内存里。**
     * 这和 Day 9"线程池只干活、任务表才记账"是同一条原则的延伸。
     *
     * ==========================================================================
     * 【为什么是 RETRY_WAIT 而不是直接改回 PENDING】
     * ==========================================================================
     * 如果直接改回 PENDING，next_retry_time 到了之前，谁去投递它？
     * 没人 —— importAsync 早就返回了，没有第二个入口。
     * 所以必须有一个"等待态"让调度器有东西可扫。
     * （也可以复用 PENDING + next_retry_time 非空，但那样状态含义就模糊了，
     *   运维看表分不清"刚建单"和"失败等重试"，见 AsyncTaskStatus 的注释。）
     *
     * @param task      失败时的任务快照（注意：retry_count 用的是快照里的值，
     *                  而不是重新查库 —— 因为这个任务只可能被当前线程持有，
     *                  抢占式 update 已经保证了排他性）
     * @param totalRows 解析出的总行数
     * @param written   本次真正提交成功的行数
     * @param e         原始异常
     */
    private void handleFailure(AsyncTask task, long totalRows, int written, Exception e) {
        Long taskId = task.getId();
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int retried = task.getRetryCount() == null ? 0 : task.getRetryCount();

        boolean retryable = isRetryable(e);
        boolean canRetry = retryable && retried < maxRetry;

        if (canRetry) {
            // 取退避表里的第 retried 项（retried 从 0 开始，所以第一次失败取 backoff[0]=1 秒）
            long delay = backoffSeconds[Math.min(retried, backoffSeconds.length - 1)];
            LocalDateTime next = LocalDateTime.now().plusSeconds(delay);

            String historyMsg = truncate("第 " + (retried + 1) + " 次执行失败（" + delay
                    + " 秒后自动重试）：" + msg, ERROR_MSG_MAX);

            asyncTaskMapper.update(null,
                    new LambdaUpdateWrapper<AsyncTask>()
                            .eq(AsyncTask::getId, taskId)
                            .set(AsyncTask::getStatus, AsyncTaskStatus.RETRY_WAIT)
                            .set(AsyncTask::getRetryCount, retried + 1)
                            .set(AsyncTask::getNextRetryTime, next)
                            .set(AsyncTask::getTotalRows, (int) totalRows)
                            .set(AsyncTask::getSuccessRows, written)
                            .set(AsyncTask::getErrorMsg, historyMsg));

            log.warn("[导入-重试] taskId={} 第 {} 次执行失败，{} 秒后重试（剩余重试 {}/{}）。"
                            + "本次已提交 {} 行，下次将从文件开头重跑（upsert 保证不重复）。原因={}",
                    taskId, retried + 1, delay, maxRetry - retried - 1, maxRetry,
                    written, msg);
            return;
        }

        // ---------- 走到这里：要么不可重试，要么次数用完了 ----------
        String reason;
        if (!retryable) {
            reason = msg;
            log.error("[导入-失败] taskId={} 遇到不可重试的错误，不再重试。已写入={}行 原因={}",
                    taskId, written, msg, e);
        } else {
            reason = "已重试 " + retried + " 次仍失败：" + msg;
            log.error("[导入-失败] taskId={} 重试次数用尽（{}次）。已写入={}行 原因={}",
                    taskId, retried, written, msg, e);
        }

        asyncTaskMapper.update(null,
                new LambdaUpdateWrapper<AsyncTask>()
                        .eq(AsyncTask::getId, taskId)
                        .set(AsyncTask::getStatus, AsyncTaskStatus.FAILED)
                        .set(AsyncTask::getNextRetryTime, null)
                        .set(AsyncTask::getTotalRows, (int) totalRows)
                        // ★ 必须用 written（真实提交行数），不能用 listener.getHandledRows()。
                        //   handledRows 是"解析出多少行"，跟"写进去多少行"完全是两回事。
                        //   早期版本写错了，导致"校验失败、一行没写"的任务却显示 successRows=1，
                        //   用户看到"成功 1 行 / 失败"会彻底懵掉。
                        .set(AsyncTask::getSuccessRows, written)
                        .set(AsyncTask::getErrorMsg, truncate(reason, ERROR_MSG_MAX)));
    }

    /**
     * 【异常分类器】判断这个异常"再试一次有没有意义"。
     *
     * 判断顺序很关键：**先判不可重试，再判可重试**。
     * 因为 Spring 的异常有继承关系，父类判断会把子类一起吃掉：
     *   DataIntegrityViolationException（唯一索引冲突、字段超长）是 DataAccessException 的子类，
     *   如果先判 `instanceof DataAccessException → return true`，
     *   唯一的索引冲突就会被误判成"可重试"，然后重试 3 次全撞同一批，纯浪费时间。
     *
     * 遍历 cause 链（最多 5 层）是因为框架喜欢套娃：
     *   EasyExcel 会把 IO 异常包进 ExcelAnalysisException，
     *   Spring 的 TransactionTemplate 会把业务异常包进 TransactionSystemException。
     *   真实原因往往藏在第三四层。
     *
     * 判据只有一句：**换个时间点重试，结果会不会变？**
     *   会变 → 可重试；不会变 → 不可重试。
     */
    private boolean isRetryable(Throwable e) {
        Throwable cur = e;
        for (int depth = 0; depth < 5 && cur != null; depth++) {

            // ---------- 第一类：确定性失败，重试一万次结果一样 ----------
            if (cur instanceof BizException) {
                // 数据校验不通过、行数超限、表头不对 —— 这些是"文件内容"的问题
                return false;
            }
            if (cur instanceof NoSuchFileException) {
                // 落盘的文件被人删了 / 磁盘挂载丢了。重试也不会自己长回来
                return false;
            }
            if (cur instanceof DataIntegrityViolationException) {
                // 包含 DuplicateKeyException（撞唯一索引）、字段超长、非空约束。
                // ⚠️ 这条必须排在 DataAccessException 判断**之前**
                return false;
            }
            if (cur instanceof BadSqlGrammarException) {
                // SQL 写错了（表名/字段名拼错、语法错误）。这是代码 bug，重试无意义
                return false;
            }

            // ---------- 第二类：临时故障，过一会儿很可能就好了 ----------
            if (cur instanceof RetryableImportException) {
                // 我们自己标记的"临时故障"（也是故障注入实验用的类型）
                return true;
            }
            if (cur instanceof DataAccessException) {
                // 排除掉上面那些之后，剩下的基本是：
                //   CannotGetJdbcConnectionException  连接池空了 / 数据库挂了
                //   DataAccessResourceFailureException 连接被服务端掐断
                //   DeadlockLoserDataAccessException   死锁被回滚（重试是标准解法）
                //   QueryTimeoutException              查询超时
                //   CannotAcquireLockException         锁等待超时
                // 全都可以靠"等一会儿再试"解决
                return true;
            }
            if (cur instanceof java.net.SocketTimeoutException) {
                // 网络抖动 / 对方响应慢
                return true;
            }

            cur = cur.getCause();
        }
        // 未知异常一律不重试。
        // 这是**保守选择**：不知道能不能重试的时候，不重试。
        // 因为对不可重入的操作盲目重试，可能造成重复扣款、重复发短信这类真正的事故。
        return false;
    }

    /**
     * 按固定大小切块写入。
     *
     * 【★ 这个方法被同步版和异步版共用，但事务行为完全不同 —— 这正是今天最妙的一点】
     *
     *   同步版调用时：外层有 @Transactional，方法内部 TransactionTemplate 的默认传播级别是
     *                REQUIRED，会**加入**外层事务 → 最终还是一个大的事务（原子，但慢、锁久）。
     *
     *   异步版调用时：外层**没有**事务，TransactionTemplate 会自己开一个新事务并提交
     *                → 每批一个独立事务（快、省锁，但失去全局原子性）。
     *
     * 同一份循环代码，两种事务语义 —— 因为**事务边界是由调用方决定的，不是被调方**。
     * 理解了这一点，就不会写出"我给这个方法加了 @Transactional 怎么不生效"的困惑：
     * 注解只对"从代理外部调用进来"的那一次生效。
     */
    private void writeAllInBatches(List<FactImportRow> rows, int batchSize, java.util.concurrent.atomic.AtomicInteger written) {
        int size = rows.size();
        int batchNo = 0;
        for (int from = 0; from < size; from += batchSize) {
            int to = Math.min(from + batchSize, size);
            batchNo++;

            // ==============================================================
            // 【Day 10 实验用 · 故障注入】默认关闭（failAtBatch = -1）
            // ==============================================================
            // 目的：造出"前面的批次已经提交、中间某一批突然失败"这个真实场景。
            //
            // 为什么抛在 writeBatchInNewTx **之前**？
            //   这样第 batchNo 批**一个事务都没开**，前 batchNo-1 批已经提交落库 ——
            //   正是我们要的"半截数据"状态。
            //   如果抛在事务内部，当前批会被回滚，效果等价，但日志里会多几条 rollback，
            //   不如现在干净。
            //
            // 为什么抛 RetryableImportException 而不是随便一个 RuntimeException？
            //   因为要验证"可重试异常 → 自动退避重试"这条链路。
            //   随便抛 RuntimeException 会被判成不可重试，直接 FAILED，实验就跑不下去了。
            if (failAtBatch > 0 && batchNo == failAtBatch) {
                throw new RetryableImportException(String.format(
                        "【故障注入】模拟第 %d 批写库时数据库连接中断（前 %d 批、共 %d 行已提交）",
                        batchNo, batchNo - 1, written.get()));
            }

            writeBatchInNewTx(rows.subList(from, to));
            // ★ 必须在事务成功提交之后才累加。
            //   如果放在方法之前累加，事务回滚了计数却没回退，记账就假了。
            written.addAndGet(to - from);
        }
    }

    /**
     * 一批数据 + 一个独立事务。
     *
     * 这是"分批提交"的实现：每 500 行一个事务，提交完就释放锁、释放 undo log。
     * 好处是长事务问题消失；代价是失去全局原子性 ——
     * 第 10 批失败时，前 9 批（4500 行）已经落库了。
     *
     * 【那这 4500 行怎么办？—— Day 10 已经给了答案】
     * 如果重试时还是普通 INSERT，这 4500 行会撞唯一索引 → 整批失败 → 永远重试不成功。
     * 所以 Day 10 把 insertBatch 改成了 `INSERT ... ON DUPLICATE KEY UPDATE`（upsert），
     * 让"重复插入"变成"覆盖更新"，重试才真正安全。
     * **"可重试"的前提是"可重入"。** 这句话值得记住。
     *
     * 注意这里的分工：
     *   事务负责"一批之内的原子性"（500 行要么全进要么全不进）
     *   upsert 负责"跨批次的可重入性"（重跑不会产生重复数据）
     * 两者解决的是不同层面的问题，**缺一个都不行**：
     *   只有事务没 upsert → 重试永远失败
     *   只有 upsert 没事务 → 一批里部分成功，数据脏
     */
    private void writeBatchInNewTx(List<FactImportRow> rows) {
        transactionTemplate.executeWithoutResult(status -> writeBatch(rows));
    }

    // ==========================================================================
    // 四、工具方法
    // ==========================================================================

    /**
     * 一批数据的落库动作（不含事务，事务由调用方决定）。
     *
     * 同步版：外层 @Transactional 管，整个方法一个大事务。
     * 异步版：writeBatchInNewTx 用 TransactionTemplate 给每批单独开。
     * 同一个方法，两种事务边界 —— 这正是"事务是调用方的决定，不是被调方的"的体现。
     */
    private void writeBatch(List<FactImportRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return; // 空列表会让 SQL 拼出 "VALUES" 后面啥也没有，直接语法错误
        }
        List<FactIndicatorValue> entities = rows.stream().map(this::toEntity).toList();
        factIndicatorValueMapper.insertBatch(entities);
    }

    private FactIndicatorValue toEntity(FactImportRow row) {
        FactIndicatorValue entity = new FactIndicatorValue();
        // trim 一遍：Excel 里最常见的脏数据就是末尾多一个空格，
        // "430102 " 和 "430102" 在数据库里是两个不同的值，join 不上、唯一索引也拦不住。
        entity.setRegionCode(row.getRegionCode().trim());
        entity.setIndicatorCode(row.getIndicatorCode().trim());
        entity.setPeriodCode(row.getPeriodCode().trim());
        entity.setPeriodType(row.getPeriodType().trim());
        entity.setIndicatorValue(row.getIndicatorValue());
        entity.setDataSource(DATA_SOURCE_IMPORT);
        return entity;
    }

    private AsyncTask findByBizKey(String bizKey) {
        return asyncTaskMapper.selectOne(
                new LambdaQueryWrapper<AsyncTask>().eq(AsyncTask::getBizKey, bizKey).last("LIMIT 1"));
    }

    /**
     * 算文件 MD5。
     *
     * ⚠️ 这一步要**把整个文件读完一遍**。所以对于大文件，它的成本不能忽略：
     *    10MB 文件算 MD5 大概几十毫秒，100 次上传就是好几秒的 CPU。
     *    真实项目里如果文件特别大，可以只算"前 1MB + 文件大小"做弱指纹，
     *    用极小的碰撞概率换速度。我们这里 287KB 的测试文件，直接算全量。
     */
    private String md5Of(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return DigestUtils.md5DigestAsHex(in);
        } catch (IOException e) {
            throw new BizException("计算文件摘要失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把上传的文件落到磁盘，返回绝对路径。
     *
     * 【★ 一个必须讲的安全点：为什么文件名用 MD5，不用原始文件名】
     * 原始文件名是**用户可控的**。如果直接用它拼路径：
     *     上传文件名叫 "../../../etc/passwd"
     *     或者 "..\..\application.yml"
     * 就可能把文件写到目录之外，覆盖掉敏感文件 —— 这叫**路径穿越（Path Traversal）**。
     * 用 MD5 当文件名，内容固定则名字固定，用户完全无法影响路径，从根上避免了这个问题。
     * （如果要保留原始文件名，得先做 normalize() 再校验 startsWith(目标目录)，缺一不可。）
     *
     * 【为什么文件必须落磁盘，不能只留 MultipartFile】
     * 因为接口返回后，这次 HTTP 请求就结束了，Spring 会把临时文件清理掉。
     * 后台线程几秒后才开始读文件 —— 那时候内存里/临时目录里都没了。
     * 所以"异步任务引用的资源，必须持久化到请求生命周期之外"。
     * 真实项目会传到 OSS / MinIO，这里落本地磁盘。
     */
    private Path saveFile(MultipartFile file, String md5) {
        Path dir;
        Path dest;
        try {
            dir = Paths.get(importDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            dest = dir.resolve(md5 + ".xlsx");
        } catch (IOException e) {
            throw new BizException("创建导入目录失败：" + e.getMessage(), e);
        }

        if (Files.exists(dest)) {
            // 同一个 MD5 说明内容一样，之前已经存过了
            return dest;
        }

        // ★ 先写临时文件，再原子改名 —— 这是并发安全的写法。
        //   如果直接往 dest 写：10 个并发请求会同时打开同一个文件，
        //   Windows 上第二个就会报 "另一个程序正在使用此文件，进程无法访问"。
        //   加锁能解决，但没必要 —— 临时文件方案是无锁的、性能更好。
        //   每个请求写自己的 .tmp.xxx，写完后 move 到最终名字：
        //   谁先 move 成功算谁的，后来的发现目标已存在就删掉自己的临时文件。
        Path tmp = dir.resolve(md5 + ".tmp." + java.util.UUID.randomUUID());
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                // Files.move 不带 REPLACE_EXISTING 时，目标存在会抛 FileAlreadyExistsException
                Files.move(tmp, dest);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // 别人抢先写好了。内容一样（MD5 相同），直接用他的。
                Files.deleteIfExists(tmp);
            }
            log.info("[导入-异步] 文件已保存 path={} size={}KB", dest, file.getSize() / 1024);
            return dest;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 清理失败不影响主流程
            }
            throw new BizException("保存上传文件失败：" + e.getMessage(), e);
        }
    }

    /** 把任务记录转成前端看的 VO */
    private ImportResultVO buildResult(AsyncTask task) {
        ImportResultVO vo = new ImportResultVO();
        vo.setTaskId(task.getId());
        vo.setStatus(task.getStatus());
        vo.setTotalRows(task.getTotalRows());
        vo.setSuccessRows(task.getSuccessRows());
        vo.setRetryCount(task.getRetryCount());
        vo.setNextRetryTime(task.getNextRetryTime());
        vo.setElapsedMs(task.getCreateTime() == null ? null
                : java.time.Duration.between(task.getCreateTime(),
                        task.getUpdateTime() == null ? LocalDateTime.now() : task.getUpdateTime()).toMillis());

        String message;
        if (AsyncTaskStatus.SUCCESS.equals(task.getStatus())) {
            message = "导入成功，共 " + (task.getSuccessRows() == null ? 0 : task.getSuccessRows()) + " 行";
            if (task.getRetryCount() != null && task.getRetryCount() > 0) {
                // 重试过的任务要告诉用户"它是经过波折才成功的"，不然用户会以为一直很顺
                message += "（期间自动重试 " + task.getRetryCount() + " 次后成功）";
            }
        } else if (AsyncTaskStatus.FAILED.equals(task.getStatus())) {
            message = "导入失败：" + (task.getErrorMsg() == null ? "未知原因" : task.getErrorMsg());
        } else if (AsyncTaskStatus.RUNNING.equals(task.getStatus())) {
            message = "任务执行中，请稍候";
        } else if (AsyncTaskStatus.RETRY_WAIT.equals(task.getStatus())) {
            // ★ Day 10：这是"失败后正在等退避时间"的状态，不能笼统说"排队中"
            //   用户看到"排队中"会以为很顺利，实际上刚刚失败过一次。
            //   明说还差几秒、还剩几次机会，用户才知道该不该继续等。
            long remain = task.getNextRetryTime() == null ? 0
                    : Math.max(0, java.time.Duration.between(LocalDateTime.now(), task.getNextRetryTime()).getSeconds());
            int used = task.getRetryCount() == null ? 0 : task.getRetryCount();
            // remain 会等于 0：调度器每 500ms 扫一次，而前端每 200ms 轮询一次，
            // 总有那么一个瞬间"时间已到但调度器还没扫到"。此时说"0 秒后"很别扭，
            // 说"即将"更准确 —— **倒计时显示 0 不等于卡住了，只是轮询赶在了调度前面。**
            String when = remain > 0 ? remain + " 秒后" : "即将";
            message = "上次执行失败，" + when + "自动重试（第 " + used + "/" + maxRetry + " 次）："
                    + (task.getErrorMsg() == null ? "" : task.getErrorMsg());
        } else {
            message = "任务已受理，正在排队";
        }
        vo.setMessage(message);
        return vo;
    }

    private void markFailed(Long taskId, String reason) {
        AsyncTask failed = new AsyncTask();
        failed.setId(taskId);
        failed.setStatus(AsyncTaskStatus.FAILED);
        failed.setErrorMsg(truncate(reason, ERROR_MSG_MAX));
        asyncTaskMapper.updateById(failed);
    }

    /** 截断字符串到指定长度，防止超出数据库列宽报错 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的 Excel 文件");
        }
        String name = file.getOriginalFilename();
        if (StringUtils.isBlank(name)) {
            throw new BizException("文件名不能为空");
        }
        String lower = name.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new BizException("只支持 .xlsx / .xls 格式，当前文件：" + name);
        }
    }
}

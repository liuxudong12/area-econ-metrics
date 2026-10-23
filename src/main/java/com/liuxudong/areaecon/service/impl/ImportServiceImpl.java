package com.liuxudong.areaecon.service.impl;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.liuxudong.areaecon.common.BizException;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

        // 抢占式更新：只有 PENDING 的任务才能变 RUNNING。
        // 用 update ... where status='PENDING' 而不是直接 updateById，
        // 是为了防止"同一任务被投递了两次"时两个线程同时跑（重复导入）。
        // 这里用了一个 MyBatis-Plus 的小技巧：EntityWrapper 里的 eq 会拼进 where 条件。
        AsyncTask running = new AsyncTask();
        running.setStatus(AsyncTaskStatus.RUNNING);
        running.setNextRetryTime(null);
        int locked = asyncTaskMapper.update(running,
                new LambdaQueryWrapper<AsyncTask>()
                        .eq(AsyncTask::getId, taskId)
                        .eq(AsyncTask::getStatus, AsyncTaskStatus.PENDING));
        if (locked == 0) {
            log.warn("[导入-异步] 任务状态不是 PENDING，跳过执行 taskId={} status={}",
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
            AsyncTask success = new AsyncTask();
            success.setId(taskId);
            success.setStatus(AsyncTaskStatus.SUCCESS);
            success.setTotalRows(allRows.size());
            success.setSuccessRows(allRows.size());
            success.setErrorMsg("");
            asyncTaskMapper.updateById(success);

            log.info("[导入-异步] 执行成功 taskId={} 总行数={} 耗时={}ms",
                    taskId, allRows.size(), elapsed);

        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("[导入-异步] 执行失败 taskId={} 已写入={}行 原因={}", taskId, written.get(), msg, e);

            AsyncTask failed = new AsyncTask();
            failed.setId(taskId);
            failed.setStatus(AsyncTaskStatus.FAILED);
            failed.setTotalRows((int) listener.getTotalRows());
            // ★ 这里必须用 written（真实提交行数），不能用 listener.getHandledRows()。
            //   handledRows 是"解析出多少行"，跟"写进去多少行"完全是两回事。
            //   早期版本写错了，导致"校验失败、一行没写"的任务却显示 successRows=1，
            //   用户看到"成功 1 行 / 失败"会彻底懵掉。
            failed.setSuccessRows(written.get());
            failed.setErrorMsg(truncate(msg, ERROR_MSG_MAX));
            asyncTaskMapper.updateById(failed);
        }
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
        for (int from = 0; from < size; from += batchSize) {
            int to = Math.min(from + batchSize, size);
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
     * 【那这 4500 行怎么办？靠 Day 10 的重试 + 幂等】
     * 重试时如果还是普通 INSERT，这 4500 行会撞唯一索引 → 整批失败 → 永远重试不成功。
     * 所以 Day 10 要把 insertBatch 改成 `INSERT ... ON DUPLICATE KEY UPDATE`（upsert），
     * 让"重复插入"变成"覆盖更新"，重试才真正安全。
     * **"可重试"的前提是"可重入"。** 这句话值得记住。
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
        vo.setElapsedMs(task.getCreateTime() == null ? null
                : java.time.Duration.between(task.getCreateTime(),
                        task.getUpdateTime() == null ? LocalDateTime.now() : task.getUpdateTime()).toMillis());

        String message;
        if (AsyncTaskStatus.SUCCESS.equals(task.getStatus())) {
            message = "导入成功，共 " + (task.getSuccessRows() == null ? 0 : task.getSuccessRows()) + " 行";
        } else if (AsyncTaskStatus.FAILED.equals(task.getStatus())) {
            message = "导入失败：" + (task.getErrorMsg() == null ? "未知原因" : task.getErrorMsg());
        } else if (AsyncTaskStatus.RUNNING.equals(task.getStatus())) {
            message = "任务执行中，请稍候";
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

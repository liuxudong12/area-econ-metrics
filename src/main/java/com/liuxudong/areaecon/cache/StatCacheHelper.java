package com.liuxudong.areaecon.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 统计接口的缓存助手 —— Day 7 的主角。
 *
 * ============================================================================
 * 一、为什么要有这个类，而不是在每个 Service 方法里各写一遍？
 * ============================================================================
 * 缓存的代码骨架永远是这三步：
 *
 *     1. 拼 key → 查 Redis
 *     2. 命中 → 直接返回；未命中 → 查数据库
 *     3. 把查库结果写回 Redis（设 TTL）→ 返回
 *
 * 如果 4 个统计方法各写一遍，就是 4 份"看起来差不多但细节会写错"的代码。
 * 所以抽成一个方法，业务代码只提供两样东西：**key** 和 **"怎么查库"**。
 *
 * 那个"怎么查库"用 {@link Supplier} 传进来 —— 这是 Java 8 的函数式接口，
 * 它就是"一个没有参数、返回一个值的方法"的抽象。
 * 调用方写成 lambda：{@code () -> mapper.selectXxx(...)}，
 * 只有真正需要查库（缓存未命中）时它才会被执行，这就叫**惰性求值**。
 *
 * ============================================================================
 * 二、为什么用 StringRedisTemplate + 手写 JSON，不用默认的 RedisTemplate？
 * ============================================================================
 * 这是个必考的坑。Spring 默认的 {@code RedisTemplate} 用的是 **JDK 序列化**
 * （JdkSerializationRedisSerializer），后果有三个：
 *
 *   ① redis-cli 里**根本看不懂** —— 存进去的是二进制，长这样：
 *        "\xac\xed\x00\x05t\x00\x11stat:category-sum..."
 *      排查线上问题时，你打不开缓存看内容，这是致命的；
 *   ② **跨语言不通** —— 你存的 Java 二进制，隔壁的 Python / Go 服务读不了；
 *   ③ **类结构一改就炸** —— 给 VO 加个字段，反序列化老数据直接抛异常
 *      （serialVersionUID 不匹配）。
 *
 * 所以我们用 {@link StringRedisTemplate}（key 和 value 都按字符串处理），
 * 再自己用 Jackson 把对象转成 **JSON 字符串** 存进去。
 * 好处：redis-cli 里一眼能读懂、任何语言都能读、加字段不影响老数据。
 * 代价：多写两行转换代码 —— 完全值。
 *
 * ============================================================================
 * 三、除了"读写"，这个类还顺手做了三件"防坑"的事
 * ============================================================================
 *   ① **空值缓存（防穿透）**：查库结果是空列表时，也往 Redis 写一个空标记
 *      （TTL 只给 60 秒）。否则"查一个根本不存在的分类"，每次都会穿到数据库。
 *   ② **TTL 随机抖动（防雪崩）**：基础 600 秒，再加 0~60 秒的随机数。
 *      否则一批 key 同时写入、同时过期，过期瞬间数据库会被打穿。
 *   ③ **反序列化失败自愈**：万一 Redis 里存的是老结构的数据（发版后类改了），
 *      解析失败不应该让接口 500，而是删掉这条脏数据、当未命中处理、重新查库。
 */
@Component
public class StatCacheHelper {

    private static final Logger log = LoggerFactory.getLogger(StatCacheHelper.class);

    /** key 统一前缀。**一定要加**：Redis 是多个业务共用的，不加前缀迟早撞车。 */
    public static final String KEY_PREFIX = "stat:";

    /** 正常数据的 TTL（秒）：10 分钟 */
    private static final long DEFAULT_TTL_SECONDS = 600;

    /**
     * 空值标记的 TTL（秒）：60 秒。
     * 为什么比正常数据短得多？—— 因为"空"这个结论**可能很快就不成立**了
     * （比如运维刚导入了一批数据）。缓存太久的"空"，会让新数据显示不出来。
     * 正常数据 10 分钟、空数据 1 分钟，是常见的取舍。
     */
    private static final long NULL_TTL_SECONDS = 60;

    /** TTL 抖动范围（秒）：基础 TTL 之上再加 0~60 秒随机，避免"集体过期" */
    private static final int TTL_JITTER_SECONDS = 60;

    /**
     * 空值标记的内容：空字符串。
     *
     * 【为什么用空字符串而不是 "null" 这种字面量？】
     * 因为 StringRedisTemplate 里 "null" 就是四个普通字符，和真的数据没区别。
     * 而**空字符串在 JSON 里不合法**（JSON 里空的值应该写 null），
     * 所以它天然不会和任何合法的业务数据冲突。用它当哨兵是最省事的。
     *
     * 【关键区别，务必分清】
     *   redis.get(key) == null    → key 不存在，**从未缓存过**（或已过期）→ 要查库
     *   redis.get(key).isEmpty() → key 存在，值是被**特意缓存的"空结果"** → 直接返回空
     * 这两件事完全不同。分不清就会把"空结果缓存"写坏，越缓存越慢。
     */
    private static final String NULL_SENTINEL = "";

    // ========================================================================
    // Day 8：防击穿的互斥锁 + 批量失效的 SCAN
    // ========================================================================

    /**
     * 锁的 key 前缀。
     *
     * ⚠️ 注意它**故意不是** {@code stat:} 开头 —— 因为 {@link #evictAllStat()}
     * 用的是 {@code stat:*} 做模式匹配，如果锁也放在 stat: 下面，
     * 一次"清空统计缓存"会把正在被别人持有的锁一起删掉，等于把互斥失效了。
     * **命名空间隔离是设计 key 时最容易忽略、又最容易埋坑的一点。**
     */
    private static final String LOCK_PREFIX = "lock:stat:";

    /** 锁的存活时间（秒）。比一次查库耗时（几十毫秒）大得多，又能兜住"持锁线程崩了"的情况。 */
    private static final long LOCK_TTL_SECONDS = 10;

    /** 抢锁结果：抢到了（我负责查库） */
    private static final int LOCK_ACQUIRED = 1;
    /** 抢锁结果：别人正在查库（我应该等，不该重复查） */
    private static final int LOCK_BUSY = 0;
    /** 抢锁结果：Redis 出错了（直接查库，绝不能等） */
    private static final int LOCK_ERROR = -1;

    /** 没抢到锁时，最多等多少轮 */
    private static final int MAX_WAIT_ROUNDS = 20;

    /** 每轮等多久（毫秒）。20 × 50ms = 最多等 1 秒 */
    private static final long WAIT_INTERVAL_MILLIS = 50;

    /** SCAN 每次扫多少个 key。太小=往返次数多，太大=单次扫描变慢，1000 左右是常见折中。 */
    private static final int SCAN_BATCH = 500;

    /**
     * 解锁用的 Lua 脚本。
     *
     * 【为什么解锁需要脚本，不能直接 del？】
     * 因为存在这种时序：
     *   A 抢到锁 → A 卡住超过 10 秒 → 锁 TTL 到期自动释放 → B 抢到锁
     *   → A 醒过来执行 del → **把 B 的锁删了** → C 又能抢到锁 → 互斥彻底失效。
     * 所以解锁前必须确认"这把锁还是我的"（比对 value）。
     * 而"比对"和"删除"这两步必须是**原子**的，否则中间还是能被插进来。
     * Redis 保证单条 Lua 脚本原子执行，这是分布式锁的标准写法。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 是否启用"防击穿"的互斥锁。
     * 做成配置项是为了能**做 A/B 实测**：关掉它，并发打同一个冷 key，
     * 就能亲眼看到 50 个请求全部穿到数据库；打开后只穿 1 次。
     * 面试讲"我做过缓存击穿防护"时，这组对比数字比空口说有用得多。
     */
    @Value("${stat.cache.breakdown-guard:true}")
    private boolean breakdownGuard;

    /** 命中/未命中计数。只为打印命中率，重启即清零，不追求持久化。 */
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    /** 没抢到锁、去等别人回填的次数（正常并发下这个数字大，说明锁真的在起作用） */
    private final AtomicLong lockWaitCount = new AtomicLong();

    /** 等超时、只好自己去查库的次数（理想情况下应该接近 0） */
    private final AtomicLong waitTimeoutCount = new AtomicLong();

    /**
     * 构造器注入。
     * StringRedisTemplate 由 Spring Boot 自动配置提供（RedisAutoConfiguration），
     * 我们不用写任何 @Bean —— 只要 pom 里有 starter、yml 里配了地址就行。
     * ObjectMapper 同理（JacksonAutoConfiguration），而且它带着 Spring Boot 的全局配置，
     * 比 new ObjectMapper() 更省事也更一致。
     */
    public StatCacheHelper(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    // 对外主方法
    // ========================================================================

    /**
     * 「查缓存 → 未命中就查库 → 回填缓存」三段式。
     *
     * @param key         缓存 key（用 {@link #buildKey} 拼）
     * @param elementType 列表里元素的类型，如 RegionMetricStatVO.class。
     *                    **反序列化必须知道元素类型** —— JSON 只有"数组"这个概念，
     *                    它不知道自己该变成 List&lt;RegionMetricStatVO&gt; 还是别的，
     *                    所以必须由调用方告诉它。
     * @param loader      "怎么查库"。只有未命中时才会被调用。
     */
    public <T> List<T> getList(String key, Class<T> elementType, Supplier<List<T>> loader) {

        // ---------- 第 1 步：查缓存 ----------
        long t0 = System.nanoTime();
        String cached = null;
        // 这个标记用来判断"Redis 是不是健康"。如果读缓存就抛异常了，
        // 后面就**不要去抢锁**（抢锁也是一次 Redis 调用，必然也失败），
        // 更要**不要去等待**（等 20×50ms = 1 秒，等于给每个请求白白加了 1 秒延迟）。
        // 第一次写这段代码时漏了这个判断，实测把 Redis 指到不存在的端口后，
        // 四个接口耗时从 20ms 涨到 1000ms —— 业务没挂，但慢了 50 倍，这叫"降级得不干净"。
        boolean redisHealthy = true;
        try {
            cached = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            // 【缓存降级】Redis 连不上 / 超时 / 命令报错 —— 绝不能因此让整个接口挂掉。
            // 记一笔日志，把 cached 留成 null（等价于"未命中"），继续往下走查库。
            //
            // 这是缓存设计的第一原则：**缓存是锦上添花，不是必需品。**
            // 缓存挂掉时，系统的正确行为是"变慢"（每次查库），而不是"报错"。
            // 很多线上事故就是把 Redis 当成了强依赖 —— 缓存一抖，整个服务全 500。
            //
            // ⚠️ 进阶（今天不做，知道有这回事即可）：
            //    如果 Redis 持续不可用，这段 catch 会被每次请求触发，日志刷屏、还白等超时。
            //    生产做法是加一个**熔断/降级开关**：连续失败 N 次后，直接跳过缓存
            //    一段时间（比如 30 秒）再试探，避免在缓存故障时反复受伤。
            //    可以用 Resilience4j 的 CircuitBreaker，也可以自己用 AtomicLong 记失败次数。
            redisHealthy = false;
            log.warn("[缓存] 读取失败，降级为直接查库。key={} 原因={}", key, e.getMessage());
        }

        if (cached != null) {
            // 情况 A：命中"空结果"标记
            if (cached.isEmpty()) {
                hitCount.incrementAndGet();
                log.info("[缓存] HIT(空值) key={} 耗时={}ms", key, millisSince(t0));
                return Collections.emptyList();
            }
            // 情况 B：命中了正常数据
            try {
                List<T> list = objectMapper.readValue(cached, listTypeOf(elementType));
                hitCount.incrementAndGet();
                log.info("[缓存] HIT  key={} 条数={} 耗时={}ms", key, list.size(), millisSince(t0));
                return list;
            } catch (JsonProcessingException e) {
                // 解析不了就删掉，别让脏数据一直卡在这儿（见类注释第 ③ 条）
                log.warn("[缓存] 反序列化失败，删除脏数据后按未命中处理。key={} 原因={}", key, e.getMessage());
                try {
                    redis.delete(key);
                } catch (RuntimeException ex) {
                    // 连删都失败说明 Redis 本身有问题，忽略即可（反正后面查库会拿到正确结果）
                    log.warn("[缓存] 删除脏数据失败，忽略。key={} 原因={}", key, ex.getMessage());
                }
            }
        }

        // ---------- 第 2 步：未命中 —— 先抢锁，防止"缓存击穿" ----------
        //
        // 【先把三个容易混的概念分开，面试必问】
        //   · 穿透：查的是**数据库里根本不存在**的数据（比如 category="不存在的分类"），
        //          缓存永远不可能命中 → 每次都打到库。解法：缓存空值（Day 7 已做）。
        //   · 击穿：**一个热点 key 过期的那一瞬间**，大量并发同时发现缓存没了，
        //          一起冲去查库。杀伤力集中在一个 key 上。解法：互斥锁（今天做）。
        //   · 雪崩：**大批 key 同时过期**（或 Redis 整个挂了），库的压力整体失衡。
        //          解法：TTL 抖动（Day 7 已做）+ 高可用。
        //
        // 【今天要挡的是"击穿"，它到底怎么发生的】
        //   假设这个 key 刚好过期，此刻 50 个请求同时进来：
        //     50 个线程都执行到"第 1 步查缓存" → 都是 null → 50 个都去查库。
        //   查库只要 20 多毫秒，但已经足够 50 个请求挤进这个窗口了。
        //   本机压测（见 docs/00-第8天操作手册.md）能实测出 50 次查库 vs 1 次查库的差别。
        //
        // 【锁怎么加才安全 —— 三个要点，缺一个就是线上事故】
        //   ① 必须是 SETNX（setIfAbsent）："不存在才写入"，天然互斥；
        //   ② 必须带过期时间：否则持锁线程一崩，锁永远留在那儿，所有人都卡死（死锁）；
        //   ③ 释放时必须比对 value：只删"自己那把锁"，还要用 Lua 保证比对+删除原子
        //      （原因见 UNLOCK_SCRIPT 上的注释）。
        //
        // 【为什么用"等一会儿再看缓存"，而不是"抢不到锁就自己查库"？】
        //   因为抢不到锁说明**已经有人在查了**，我再查一遍是纯重复劳动 ——
        //   击穿防护的意义正是"只让 1 个人去查"。等他回填好，我读缓存就行，
        //   连数据库的往返都省了。
        //
        // 【但绝不允许无限等】最多等 1 秒（20×50ms），还不出来就自己查库。
        //   宁可多查一次库，也不能让请求线程挂在那里 —— 这是"可用性优先于最优解"。
        String lockKey = LOCK_PREFIX + key;
        String token = UUID.randomUUID().toString();
        boolean locked = false;

        // 注意这里多了 redisHealthy 这个前置条件：Redis 已经不健康了，就别再去抢锁。
        if (breakdownGuard && redisHealthy) {
            int lockState = tryLock(lockKey, token);
            locked = lockState == LOCK_ACQUIRED;
            if (lockState == LOCK_BUSY) {
                lockWaitCount.incrementAndGet();
                String waited = waitForCache(key);
                if (waited != null) {
                    List<T> list = decode(waited, key, elementType);
                    if (list != null) {
                        hitCount.incrementAndGet();
                        log.info("[缓存] HIT(等他人回填后命中) key={} 条数={} 耗时={}ms",
                                key, list.size(), millisSince(t0));
                        return list;
                    }
                }
                waitTimeoutCount.incrementAndGet();
                log.warn("[缓存] 等待他人回填超时({}ms)，降级为自行查库。key={}",
                        MAX_WAIT_ROUNDS * WAIT_INTERVAL_MILLIS, key);
            }
            // 如果是 LOCK_ERROR：Redis 刚刚出问题了，**不要等**，直接走查库。
            // 这一点很容易写漏 —— 写漏了 Redis 一挂，每个请求都白等 1 秒。
        }

        try {
            // 抢到锁之后再查一次缓存 —— 叫 **double-check**（双重检查）。
            // 因为从"第 1 步查缓存"到"真的抢到锁"之间可能已经过了几毫秒，
            // 这期间别的线程可能已经查完库、写好缓存了。这时直接返回，白省一次查库。
            // 这一步在"缓存刚过期、瞬时并发"的场景里能再挡掉一批重复查库。
            if (locked) {
                String again = safeGet(key);
                if (again != null) {
                    List<T> list = decode(again, key, elementType);
                    if (list != null) {
                        hitCount.incrementAndGet();
                        log.info("[缓存] HIT(二次确认) key={} 条数={} 耗时={}ms",
                                key, list.size(), millisSince(t0));
                        return list;
                    }
                }
            }

            // ---------- 第 3 步：查库（整个链路里唯一真正查库的地方）----------
            long dbStart = System.nanoTime();
            List<T> data = loader.get();
            double dbCost = millisSince(dbStart);
            missCount.incrementAndGet();

            // ---------- 第 4 步：回填缓存 ----------
            // ⚠️ 回填写缓存失败（比如 Redis 正好挂了）**绝对不能影响接口返回**。
            //    缓存是"锦上添花"，不是"必须成功"。所以这里 try-catch 只记日志、不往外抛。
            //    这是缓存设计的第一原则：**缓存挂了，业务要能降级到查库继续跑。**
            try {
                if (data == null || data.isEmpty()) {
                    redis.opsForValue().set(key, NULL_SENTINEL, Duration.ofSeconds(NULL_TTL_SECONDS));
                    log.info("[缓存] MISS(空结果，已写空值标记 {}s) key={} 查库={}ms", NULL_TTL_SECONDS, key, dbCost);
                    return Collections.emptyList();
                }
                String json = objectMapper.writeValueAsString(data);
                long ttl = ttlWithJitter();
                redis.opsForValue().set(key, json, Duration.ofSeconds(ttl));
                log.info("[缓存] MISS key={} 条数={} 查库={}ms 回填TTL={}s 总耗时={}ms",
                        key, data.size(), dbCost, ttl, millisSince(t0));
                return data;
            } catch (JsonProcessingException | RuntimeException e) {
                // 序列化失败或 Redis 连接异常：记一笔，照常返回数据库结果
                log.warn("[缓存] 回填失败，本次直接返回数据库结果（不影响业务）。key={} 原因={}", key, e.getMessage());
                return data == null ? Collections.emptyList() : data;
            }
        } finally {
            // ⚠️ 释放锁**必须写在 finally 里**。
            // 上面任何一步抛异常（比如查库超时）都必须把锁放掉，否则这个 key
            // 只能等 10 秒 TTL 到期才能再被加载 —— 一个异常就能让接口卡 10 秒。
            if (locked) {
                unlock(lockKey, token);
            }
        }
    }

    /**
     * 删除单个 key。
     *
     * 注意它跟下面 {@link #evictAllStat()} 的分工：
     *   · 知道确切 key → 用这个（精准、只影响一条缓存）；
     *   · 只知道"这类缓存都脏了" → 用 evictAllStat（按前缀批量扫）。
     * **能精准删就别批量删** —— 批量删得越多，缓存被清空后瞬间涌入数据库的请求就越多。
     */
    public boolean evict(String key) {
        try {
            Boolean deleted = redis.delete(key);
            boolean ok = Boolean.TRUE.equals(deleted);
            log.info("[缓存] 主动删除 key={} 是否存在={}", key, ok);
            return ok;
        } catch (RuntimeException e) {
            // 同样遵循"缓存故障不影响业务"：删不掉最多是脏数据留到 TTL 到期
            log.warn("[缓存] 删除失败，忽略。key={} 原因={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 【Day 8 新增】按模式批量删除缓存。
     *
     * ⚠️⚠️ **绝对不能用 {@code KEYS} 命令**（{@code redis.keys("stat:*")}）。
     *   Redis 是**单线程**的，KEYS 会把整个库的所有 key 一次性遍历一遍，
     *   遍历期间**其它所有命令全部阻塞**。库里有几百万 key 时，
     *   一条 KEYS 就能让 Redis 卡住几百毫秒到几秒，全站接口跟着抖一下。
     *   这不是理论风险 —— 它是最经典的"一个命令搞垮 Redis"的案例。
     *
     * ✅ 正确做法是 {@code SCAN}：游标式分批扫描，每次只扫一小批（这里 500 个），
     *   扫完把"游标"还给你，你拿着游标继续扫，直到游标回到 0。
     *   它不保证"同一时刻的完整快照"（扫描期间新增/删除的 key 可能漏掉或多出），
     *   但对于"删缓存"这种场景完全够用 —— 漏掉的那一条本来就会自己过期。
     *
     * 【为什么要分批删】
     * DEL 一次传 10 万个 key，Redis 同样要在这条命令上阻塞很久。
     * 每批 500 个，中间有机会处理别的请求，整体更平滑。
     */
    public long evictByPattern(String pattern) {
        long deleted = 0;
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH).build())) {
            List<String> batch = new ArrayList<>(SCAN_BATCH);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH) {
                    deleted += deleteBatch(batch);
                    batch.clear();
                }
            }
            deleted += deleteBatch(batch);
        } catch (RuntimeException e) {
            // 删缓存失败 → 旧数据最多"脏"到 TTL 到期（600 秒），业务不中断。
            // 这一点必须想清楚才敢说"缓存一致性做到了什么程度"。
            log.warn("[缓存] 批量删除失败（旧缓存会撑到 TTL 到期）。pattern={} 原因={}",
                    pattern, e.getMessage());
        }
        return deleted;
    }

    /**
     * 【Day 8 新增】清空本项目所有统计缓存。
     *
     * 什么时候调它：**任何会让统计结果发生变化的数据变更之后**。
     * 本项目里就是 dim_region 的增删改（因为统计结果里的"区域名称"是它提供的）。
     *
     * 为什么是"全清"而不是"精确删某几条"？
     *   因为一条区域数据的名称变了，会影响**所有**带这个区域的统计结果：
     *   category-summary、top-regions、yoy 里都有它，而且这些 key 里都没有 region_code
     *   （region_code 是查询结果的一部分，不是入参）。
     *   要想精准删，得把所有可能的 key 组合都枚举出来 —— 不现实。
     *   **所以这里选择"宁可多删，不可漏删"**：多删的代价是几次缓存未命中（慢几十毫秒），
     *   漏删的代价是用户看到**错误的数据**。两者的严重程度不在一个量级。
     */
    public long evictAllStat() {
        long n = evictByPattern(KEY_PREFIX + "*");
        log.info("[缓存] 清空统计缓存，pattern={}* 共删除 {} 个 key", KEY_PREFIX, n);
        return n;
    }

    /** 打印累计命中率 + 锁的统计。测试阶段用，真实项目应该接监控（如 Micrometer）。 */
    public String stats() {
        long hit = hitCount.get();
        long miss = missCount.get();
        long total = hit + miss;
        double rate = total == 0 ? 0 : hit * 100.0 / total;
        return String.format(
                "hit=%d miss=%d 命中率=%.1f%% 抢锁失败(去等待)=%d 等待超时降级=%d 防击穿=%s",
                hit, miss, rate, lockWaitCount.get(), waitTimeoutCount.get(),
                breakdownGuard ? "开启" : "关闭");
    }

    // ========================================================================
    // 锁与并发相关（Day 8）
    // ========================================================================

    /**
     * 抢锁：{@code SET lockKey token NX EX 10}，用 Java 的写法就是 setIfAbsent + 过期时间。
     *
     * 【为什么返回 int 而不是 boolean？】
     * 因为"没抢到"其实有两种完全不同的情况，处理方式也完全不同：
     *   · LOCK_BUSY  —— 别人正在查库。**应该等**，等他回填好我读缓存；
     *   · LOCK_ERROR —— Redis 出错了。**绝对不能等**，因为等的是"别人会不会写好缓存"，
     *                   可 Redis 都连不上了，等 1 秒纯属浪费，直接查库才是对的。
     * 用 boolean 把这两种情况压成同一个 false，就一定会写错其中一个分支。
     * **这是"返回值要能区分语义"的典型例子。**
     */
    private int tryLock(String lockKey, String token) {
        try {
            Boolean ok = redis.opsForValue()
                    .setIfAbsent(lockKey, token, Duration.ofSeconds(LOCK_TTL_SECONDS));
            return Boolean.TRUE.equals(ok) ? LOCK_ACQUIRED : LOCK_BUSY;
        } catch (RuntimeException e) {
            log.warn("[缓存] 抢锁异常（Redis 不可用），跳过等待直接查库。lock={} 原因={}",
                    lockKey, e.getMessage());
            return LOCK_ERROR;
        }
    }

    /** 解锁：执行 Lua 脚本，只有"值还是自己的 token"才删，保证不会误删别人的锁。 */
    private void unlock(String lockKey, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (RuntimeException e) {
            // 删不掉也没关系：这把锁会在 10 秒后自己过期。**有 TTL 兜底才敢这么写。**
            log.warn("[缓存] 释放锁失败（等 TTL 自动过期）。lock={} 原因={}", lockKey, e.getMessage());
        }
    }

    /**
     * 等别的线程把缓存写好：每 50ms 看一眼，最多看 20 次（共约 1 秒）。
     *
     * 【为什么用 sleep 轮询，不用 Redis 的发布订阅/阻塞命令？】
     * 因为这是**尽力而为的等待**，不是必须成功。1 秒等不到就自己查库，逻辑简单、
     * 没有任何额外组件依赖。生产上如果想做得更精细，可以：
     *   · 用 {@code BLPOP} 让等待方阻塞在一个列表上，回填方 push 一下唤醒（省掉轮询）；
     *   · 或者干脆不用锁，改成"逻辑过期"方案（缓存永不删，值里带一个过期时间戳，
     *     过期后由**一个**线程异步刷新，其余线程先返回旧数据）。
     * 两种方案各有取舍，但**先有互斥锁、再谈优化**是更稳的落地顺序。
     */
    private String waitForCache(String key) {
        for (int i = 0; i < MAX_WAIT_ROUNDS; i++) {
            try {
                Thread.sleep(WAIT_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                // 被中断时必须恢复中断标记再退出，否则上层感知不到"有人要停我"。
                // 这是 Thread.sleep 的标准处理方式，面试问"为什么不能吞掉 InterruptedException"就答这个。
                Thread.currentThread().interrupt();
                return null;
            }
            String value = safeGet(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 读缓存但不抛异常：Redis 故障时统一当作"没有缓存"。 */
    private String safeGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把缓存里的 JSON 还原成列表。返回 null 表示"这条数据不能用"（解析失败），调用方应继续查库。
     * 注意：返回**空列表**和返回 **null** 是两件事 ——
     * 空列表是"缓存里明确记着：这个查询结果就是空"，null 是"这条缓存坏了/读不出来"。
     */
    private <T> List<T> decode(String value, String key, Class<T> elementType) {
        if (value.isEmpty()) {
            return Collections.emptyList();   // 空值哨兵（Day 7 防穿透用的）
        }
        try {
            return objectMapper.readValue(value, listTypeOf(elementType));
        } catch (JsonProcessingException e) {
            log.warn("[缓存] 反序列化失败，按未命中处理。key={} 原因={}", key, e.getMessage());
            try {
                redis.delete(key);
            } catch (RuntimeException ex) {
                log.warn("[缓存] 删除脏数据失败，忽略。key={} 原因={}", key, ex.getMessage());
            }
            return null;
        }
    }

    /** 批量删一小批 key，返回实际删掉的条数。 */
    private long deleteBatch(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        try {
            Long n = redis.delete(keys);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            log.warn("[缓存] 批量删除这一批失败，跳过。条数={} 原因={}", keys.size(), e.getMessage());
            return 0;
        }
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    /**
     * 拼缓存 key。
     *
     * 格式：{@code stat:category-summary:工业:Y:2024,2025,2026}
     *       └前缀┘ └──业务名──┘ └──..── 业务参数 ..
     *
     * 【为什么用冒号 : 分隔？】
     * Redis 社区约定俗成的层级分隔符（Spring Data Redis 的 RedisCache 也默认用它）。
     * 好处是可以按层级做批量操作，比如"删掉分区统计下的所有缓存"就能用
     * {@code stat:category-summary:*} 做模式匹配（Day 8 用 SCAN 实现）。
     * 另外 redis-cli 的客户端（如 Another Redis Desktop）会把冒号渲染成树形目录，
     * 一眼就能看出 key 的组织结构。
     *
     * 【为什么参数要排好序再拼？】
     * 因为 {@code [2024,2025]} 和 {@code [2025,2024]} 查出来的结果**完全一样**。
     * 如果不排序，同一个查询会因为"参数顺序不同"生成两个 key，
     * 缓存利用率直接砍半 —— 这类 bug 不报错，只是缓存命中率莫名其妙上不去。
     * 所以调用方必须用 {@link #joinSorted} 来拼列表参数。
     */
    public static String buildKey(String biz, String... parts) {
        StringBuilder sb = new StringBuilder(KEY_PREFIX).append(biz);
        for (String part : parts) {
            sb.append(':').append(part == null ? "-" : part);
        }
        return sb.toString();
    }

    /**
     * 把列表参数拼成 key 的一段：去重 + 排序 + 逗号连接。
     *
     * {@code ["2025","2024","2024"]} → {@code "2024,2025"}
     *
     * 去重是为了防止前端传重复值造出冗余 key；排序是为了让参数顺序不影响 key（见上）。
     */
    public static String joinSorted(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return values.stream().distinct().sorted().collect(Collectors.joining(","));
    }

    /** 构造 {@code List<T>} 的类型信息，Jackson 靠它把 JSON 数组正确还原成带泛型的列表。 */
    private <T> JavaType listTypeOf(Class<T> elementType) {
        return objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
    }

    /** 基础 TTL + 0~60 秒随机抖动，防止一批 key 同时过期（缓存雪崩）。 */
    private long ttlWithJitter() {
        return DEFAULT_TTL_SECONDS + ThreadLocalRandom.current().nextLong(TTL_JITTER_SECONDS + 1);
    }

    /** 把纳秒差换算成毫秒（保留一位小数，便于观察 1ms 以内的差异）。 */
    private double millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }
}

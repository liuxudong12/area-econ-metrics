package com.liuxudong.areaecon.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
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

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 命中/未命中计数。只为打印命中率，重启即清零，不追求持久化。 */
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

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

        // ---------- 第 2 步：未命中，查库 ----------
        long dbStart = System.nanoTime();
        List<T> data = loader.get();
        double dbCost = millisSince(dbStart);
        missCount.incrementAndGet();

        // ---------- 第 3 步：回填缓存 ----------
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
    }

    /**
     * 删除单个 key。Day 8 会用它做"数据变更时清缓存"。
     * 今天先用它做测试：验证删掉缓存后请求会重新查库。
     */
    public boolean evict(String key) {
        Boolean deleted = redis.delete(key);
        boolean ok = Boolean.TRUE.equals(deleted);
        log.info("[缓存] 主动删除 key={} 是否存在={}", key, ok);
        return ok;
    }

    /** 打印累计命中率。测试阶段用，真实项目应该接监控（如 Micrometer）。 */
    public String stats() {
        long hit = hitCount.get();
        long miss = missCount.get();
        long total = hit + miss;
        double rate = total == 0 ? 0 : hit * 100.0 / total;
        return String.format("hit=%d miss=%d 命中率=%.1f%%", hit, miss, rate);
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

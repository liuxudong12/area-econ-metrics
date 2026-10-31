package com.liuxudong.areaecon.security;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.liuxudong.areaecon.common.BizException;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 行级数据权限拦截器 —— Day 13 的主角。
 *
 * ============================================================================
 * 一、为什么要在 SQL 层做，而不是"查出来再在 Java 里过滤"
 * ============================================================================
 * 这是数据权限最核心的一个架构选择，面试必问。三种做法：
 *
 *   ✗ 做法 A：查全量 → Java 里 for 循环 filter
 *       · 48 万行全部搬到应用内存，只为返回 35,904 行 —— 13 倍的无用 I/O
 *       · 导出的场景直接 OOM（Day 12 已实测：全量查峰值 508MB）
 *       · 分页彻底失效：数据库给你 20 行，你过滤完剩 3 行，用户看到"每页只有 3 条"
 *
 *   ✗ 做法 B：每个 Service 方法手动 {@code wrapper.eq(...)} 加条件
 *       · 一定会漏。本项目有 9 条受约束的 SQL，将来加到 30 条，
 *         谁能保证每个人都记得加？**漏一条就是一次数据泄漏事故。**
 *       · "记得加"这件事没有测试能自动验证
 *
 *   ✓ 做法 C（本项目）：在 Mapper 层拦截 SQL，**执行前**改写 WHERE
 *       · 数据库只扫该扫的行 —— 性能最好
 *       · 权限逻辑只有一份，改一处全局生效
 *       · 漏标记的风险通过"显式注解 + 找不到别名就抛异常"降到最低
 *
 * 一句话：**过滤条件必须下推到数据库**。因为只有数据库知道怎么用索引把它变快，
 * 也因为应用层根本不该看到那些行。
 *
 * ============================================================================
 * 二、改写长什么样（这就是你面试要画的那张图）
 * ============================================================================
 * <pre>
 *   改写前： SELECT f.region_code, SUM(f.indicator_value)
 *            FROM fact_indicator_value f JOIN dim_indicator i ON ...
 *            WHERE f.deleted = 0 AND i.category = ? AND f.period_code IN (?,?)
 *            GROUP BY f.region_code
 *
 *   改写后： ... WHERE (f.deleted = 0 AND ... AND f.period_code IN (?,?))
 *                     AND f.region_code LIKE '4301%'
 *                            ↑ 就是这一句，由拦截器在运行时加上
 * </pre>
 *
 * 注意括号：新条件是 AND 到**整个原 WHERE 表达式**上的，不是拼在字符串末尾。
 * 用 JSqlParser 解析成 AST 再 AND，天然带括号；
 * 如果是 {@code sql + " AND f.region_code LIKE ..."} 这种字符串拼接，
 * 遇到 {@code WHERE a = 1 OR b = 2} 就会变成 {@code a = 1 OR (b = 2 AND region...)} ——
 * 权限过滤被 OR 短路掉，**越权而且不报错**。这是"用 AST 而不是拼字符串"最硬的理由。
 *
 * ============================================================================
 * 三、五个必须处理的边界（每一个都对应手册里的一组实验）
 * ============================================================================
 *   ① 没登录（ThreadLocal 里是 null）    → 抛 403，**不能不加条件**
 *   ② data_scope = ALL                  → 原样返回，不改写（省掉一次解析）
 *   ③ data_scope = REGION 但辖区是 null  → 加 {@code 1 = 0}，返回 0 行
 *      这一条最容易写成"不加条件"，写错了就是"新员工入职即看全省"
 *   ④ SQL 里有子查询 / 窗口函数          → 必须改**正确的层级**（按别名递归找）
 *   ⑤ 找不到注解指定的别名                → 抛异常，**绝不静默放过**
 */
@Slf4j
public class DataScopeInnerInterceptor implements InnerInterceptor {

    /**
     * 辖区编码白名单：2~12 位纯数字。
     *
     * 【为什么要有这个正则，而不是直接相信数据库里的值】
     * 因为我们要把 prefix 拼进 SQL（{@code LIKE '4301%'}）。
     * 拼接本身不是罪 —— **把不可信输入拼进 SQL** 才是。
     * 加了这道白名单之后，这个值只可能是数字，连一个引号都进不来，
     * 注入的可能性被物理消灭。
     *
     * 【不这么写行不行】
     * 可以，JSqlParser 也支持程序化构造 {@code LikeExpression}。
     * 这里选"校验 + 拼接"是因为代码更短更好读，且校验点只有一处。
     * 但要记住：**这种便利只在"值有明确字符集"时成立**。
     * 如果这个字段是备注、地址、搜索关键词这类自由输入，就必须老老实实走预编译参数。
     */
    private static final Pattern SAFE_REGION_PREFIX = Pattern.compile("^[0-9]{2,12}$");

    /** 恒假条件。用 {@code 1 = 0} 而不是 {@code FALSE}，兼容性最好（Oracle 没有 boolean 字面量） */
    private static final String ALWAYS_FALSE = "1 = 0";

    private final boolean enabled;

    /**
     * Mapper 方法 id → 注解。空 Optional 表示"确认这个方法没标"。
     *
     * 【为什么要缓存】
     * 每次查询都要反射找注解，而反射相对慢。一个导出请求要跑 245 次 SQL，
     * 就是 245 次反射。缓存之后只有第一次反射。
     * 更关键的是：**这个映射在运行期不会变**（注解是编译期固定的），所以可以永久缓存。
     *
     * ⚠️ 用 ConcurrentHashMap 而不是 HashMap：Mapper 会被多个 Tomcat 线程并发调用。
     * HashMap 在并发写入时可能丢数据或死循环，属于"平时没事、压测必炸"的经典问题。
     */
    private final ConcurrentMap<String, Optional<DataScope>> annotationCache = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------------
    // 【Day 13 新增】改写开销计数器
    //
    // 【为什么要专门统计这个】
    // 面试官看到"每个请求都要解析一遍 SQL 再拼回去"，第一个问题一定是
    // "那不是很慢吗？"。空口说"不慢"没有说服力，**要能报出数字**。
    //
    // 这里只统计"真正发生改写"的那条路径（ALL 用户和没标注解的 SQL 不计），
    // 因为只有它们才付了 JSqlParser 解析的代价。
    //
    // 和前几天的命中率统计一样，这个计数在 JVM 内存里，重启清零、多实例各算各的。
    // 生产环境该换成 Micrometer。
    // ------------------------------------------------------------------------
    private final java.util.concurrent.atomic.AtomicLong rewriteCount =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rewriteNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong cacheHitCount =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 改写结果缓存。
     *
     * 【为什么要加这一层】
     * 实测（手册实验 E8）：JSqlParser 解析 + toString 拼回，一条 SQL 约 **0.92ms**。
     * 一个导出请求有 245 条 SQL，就是 225ms 纯花在"拆字符串再拼回去"上，
     * 而这件事的结果**每次都一样**。
     *
     * 【为什么"每次都一样"是成立的】
     * 改写结果只取决于三件事：
     *   ① 哪条 SQL（结构）→ msId + SQL 文本
     *   ② 谁的权限范围     → scopeKey
     *   ③ 注解里的 alias    → 固定挂在 msId 上
     * 其中**参数值完全不影响改写结果** —— 我们只是往 WHERE 里 AND 一个
     * {@code f.region_code LIKE '4301%'}，`?` 占位符一个都不动。
     * 所以同样的 (msId, scopeKey, SQL文本) 必然得到同样的新 SQL。
     *
     * 【SQL 文本里带 `?` 会不会导致缓存爆炸】
     * 不会。{@code <foreach>} 生成的 IN 列表，元素个数不同确实会生成不同的 SQL 文本
     * （3 个周期是 {@code (?,?,?)}，2 个是 {@code (?,?)}），但那也就多几个 key。
     * key 的总量 ≈ 方法数 × 权限范围数 × IN 列表长度种类，**是有界的**。
     * （对比一下：如果 key 里带上参数**值**，那就无界了 —— 用户随便传什么都能造一个新 key，
     *   这就是缓存最经典的"key 空间被用户输入撑爆"事故。）
     *
     * 【缓存不会变脏吗】
     * 不会。注解是编译期固定的，用户的数据范围改了会导致 scopeKey 变化 → 换一个 key。
     * 旧 key 留在 map 里最多占几百字节，无伤大雅。
     */
    private final ConcurrentMap<String, String> rewriteCache = new ConcurrentHashMap<>();

    public DataScopeInnerInterceptor(boolean enabled) {
        this.enabled = enabled;
        log.info("[数据权限] 拦截器已注册，开关={}", enabled ? "开启" : "关闭（仅用于对比实验）");
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler,
                            BoundSql boundSql) throws SQLException {

        // 开关关掉时直接放行 —— 只为做对比实验（"有权限 vs 无权限"的耗时差）。
        // 生产环境必须恒为 true。手册里实验 E7 用它。
        if (!enabled) {
            return;
        }

        // 只有标了 @DataScope 的方法才处理。没标的（用户表查询、区域字典查询）原样放行。
        Optional<DataScope> annotation = findAnnotation(ms.getId());
        if (annotation.isEmpty()) {
            return;
        }
        DataScope ds = annotation.get();

        LoginUser user = DataScopeContext.get();

        // ① 没有身份 —— 绝不放过。
        //    抛异常而不是"跳过改写"，因为跳过 = 看到全量数据。
        //    正常流程下 UserContextInterceptor 已经拦过一道，走到这里说明
        //    是**非 HTTP 线程**在调 Mapper（定时任务、线程池、启动预热）。
        //    那些地方默认不该有数据权限 —— 但必须显式传身份，不能靠"碰巧没加条件"。
        if (user == null) {
            throw new BizException(403,
                    "当前线程没有用户身份，无法确定数据范围，已拒绝查询（异步/定时任务需显式传递用户上下文）");
        }

        // ② 全量用户 —— 不改写，省掉一次 SQL 解析
        if (user.isAllScope()) {
            return;
        }

        // ③④⑤ 算条件 → 改 SQL → 写回 BoundSql
        //
        // 计时只包住"改"这一段。SQL 执行本身要几百微秒到几十毫秒，
        // 混进去就测不出改写的真实成本了 —— **统计口径要和你想回答的问题对齐**。
        //
        // 顺序很重要：**先查缓存，再决定要不要解析**。
        // 如果写成"先解析、再拿结果去查缓存"，缓存就白加了。
        long t0 = System.nanoTime();
        String originalSql = boundSql.getSql();
        String cacheKey = ms.getId() + '|' + user.scopeKey() + '|' + originalSql;

        String newSql = rewriteCache.get(cacheKey);
        if (newSql != null) {
            // 命中：这条 SQL 之前已经用同一个权限范围改写过，直接复用
            cacheHitCount.incrementAndGet();
        } else {
            String condition = buildCondition(user, ds);
            newSql = injectCondition(originalSql, condition, ms.getId(), ds);
            rewriteCache.put(cacheKey, newSql);
            rewriteCount.incrementAndGet();
        }

        applySql(boundSql, newSql);
        rewriteNanos.addAndGet(System.nanoTime() - t0);
    }

    // ==========================================================================
    // 一、算条件
    // ==========================================================================

    /**
     * 生成要 AND 进 WHERE 的条件片段。
     *
     * @return 形如 {@code f.region_code LIKE '4301%'}，或恒假条件 {@code 1 = 0}
     */
    private String buildCondition(LoginUser user, DataScope ds) {
        String prefix = user.regionCode();

        // ★★★ 这几行是整个类里最重要的逻辑 ★★★
        //
        // 辖区为空 → 返回 1 = 0（什么都看不到），而不是"不加条件"（什么都看得到）。
        //
        // 【为什么这个分支如此危险】
        // 真实项目里 data_scope 和 region_code 是管理员在后台表单里分开填的。
        // 新建用户时只选了角色、忘了选辖区 → region_code = NULL。
        // 如果代码写成：
        //     if (user.getRegionCode() != null) { 加上过滤条件 }   ← 少了 else
        // 那么这个人**能看全省数据**，而且系统一切正常：接口 200、数据完整、日志无异常。
        // 直到某天有人发现"新来的小王能看到别的市的数据"。
        //
        // 根因是**默认值选错了方向**：配置缺失时的默认行为应该"最严格"，不是"最宽松"。
        // 安全领域管这叫 fail-closed（失败时关闭）：
        //     门锁坏了 → 打不开（fail-closed）✓
        //     门锁坏了 → 谁都能进（fail-open） ✗
        //
        // 手册里的"实验 E5"就是把这个分支临时改成 fail-open，看数据怎么漏出去的。
        if (prefix == null || prefix.isBlank()) {
            log.warn("[数据权限] 用户 {} 的 data_scope=REGION 但未配置辖区，按'无可见数据'处理",
                    user.username());
            return ALWAYS_FALSE;
        }

        // 白名单校验：值不合法宁可报错，也不能拼进 SQL
        if (!SAFE_REGION_PREFIX.matcher(prefix).matches()) {
            throw new BizException(403, "用户的辖区编码格式非法：" + prefix + "（应为 2~12 位数字）");
        }

        // 列名带不带表前缀，由注解的 alias 决定。
        // 不带前缀在多表 JOIN 时会报 "Column 'region_code' in where clause is ambiguous"。
        String alias = ds.alias().isEmpty() ? "" : ds.alias() + ".";
        return alias + ds.column() + " LIKE '" + prefix + "%'";
    }

    // ==========================================================================
    // 二、改 SQL
    // ==========================================================================

    /**
     * 把条件 AND 进 SQL 的 WHERE。
     *
     * 【为什么必须"按别名递归找层级"】
     * 因为我们的 SQL 里有子查询。看第 6 天的 TOP N 排行：
     * <pre>
     *   SELECT t.regionCode, RANK() OVER (...) FROM (
     *       SELECT f.region_code, SUM(...) FROM fact_indicator_value f
     *       WHERE f.deleted = 0 ...        ← 权限条件必须加在**这一层**
     *       GROUP BY f.region_code
     *   ) t
     *   ORDER BY t.totalValue DESC LIMIT 10
     * </pre>
     * 如果加到外层（别名 t 那层），外层的 FROM 是子查询、根本没有 f 这个别名，
     * MySQL 会直接报 <b>Unknown column 'f.region_code' in 'where clause'</b>。
     *
     * 更阴险的是**加到错的层也能跑通**的情况：如果外层恰好也有同名列，
     * SQL 不报错、但过滤的不是你以为的那批数据 —— 这种"能跑但结果错"最难查。
     * 所以这里宁可抛异常，也不猜。
     */
    private String injectCondition(String originalSql, String condition, String msId, DataScope ds) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(originalSql);
            if (!(stmt instanceof Select select)) {
                throw new IllegalStateException(
                        "Mapper 方法 " + msId + " 标了 @DataScope，但它不是查询语句，无法改写");
            }

            Expression condExpr = CCJSqlParserUtil.parseCondExpression(condition);

            if (!injectInto(select, condExpr, ds)) {
                // ⑤ 找不到注解指定的别名 —— 抛，不放过。
                //   这种情况几乎一定是"XML 里改了别名、注解忘了同步"。
                //   如果静默跳过，后果是这条 SQL 从此不受权限约束，而且没有任何提示。
                throw new IllegalStateException(
                        "Mapper 方法 " + msId + " 的 @DataScope(alias=" + ds.alias()
                                + ") 在 SQL 里找不到对应的表：" + originalSql);
            }
            return select.toString();

        } catch (JSQLParserException e) {
            // 解析失败通常意味着 SQL 里有 JSqlParser 不认识的方言写法。
            // 同样**不能降级成"不改写"** —— 那等于权限失效。
            // 正确做法是让接口 500、把日志打出来，然后改 SQL 或换解析方案。
            log.error("[数据权限] SQL 解析失败，已拒绝执行。ms={} sql={}", msId, originalSql, e);
            throw new BizException(500, "数据权限校验失败（SQL 解析异常），已拒绝本次查询");
        }
    }

    /**
     * 递归找"FROM 里含指定别名的那个 PlainSelect"，给它加条件。
     *
     * @return true = 找到了并已改写；false = 整棵 AST 里都没有这个别名
     */
    private boolean injectInto(Select select, Expression cond, DataScope ds) {
        PlainSelect ps = select.getPlainSelect();
        return ps != null && injectInto(ps, cond, ds);
    }

    private boolean injectInto(PlainSelect ps, Expression cond, DataScope ds) {
        FromItem from = ps.getFromItem();
        if (from == null) {
            return false;
        }

        // 情况 1：FROM 是一张表（可能带别名）
        if (from instanceof Table table) {
            if (!aliasMatches(table, ds)) {
                return false;
            }
            // 原来有 WHERE → 用 AND 把两个表达式连起来（AST 层面，自动带括号）
            // 原来没有 WHERE → 直接挂上去
            Expression old = ps.getWhere();
            ps.setWhere(old == null ? cond : new AndExpression(old, cond));
            return true;
        }

        // 情况 2：FROM 是括号包起来的子查询 → 递归进去
        // 这就是 TOP N 那条 SQL 的形态：FROM ( SELECT ... ) t
        if (from instanceof ParenthesedSelect sub) {
            return injectInto((Select) sub, cond, ds);
        }

        // 情况 3：其他形态（表函数、VALUES 等）本项目用不到，返回 false
        // 让调用方抛"找不到别名"，而不是悄悄跳过。
        return false;
    }

    /**
     * 判断这张表是不是注解里指定的那张。
     *
     * 注解没写 alias 时（{@code alias=""}），只要 FROM 是一张表就算匹配 ——
     * 这要求调用方自己保证 SQL 里只有一张表、不会有列名歧义。
     */
    private boolean aliasMatches(Table table, DataScope ds) {
        String want = ds.alias();
        if (want.isEmpty()) {
            return true;
        }
        return table.getAlias() != null && want.equalsIgnoreCase(table.getAlias().getName());
    }

    /**
     * 把改写后的 SQL 写回 BoundSql。
     *
     * ============================================================================
     * 一、这里为什么不是 {@code return new BoundSql(...)}
     * ============================================================================
     * 因为 MyBatis-Plus 3.5.9 的 {@code InnerInterceptor.beforeQuery} **返回值是 void**，
     * 没有"返回一个新 BoundSql 让框架用"这个口子。（MyBatis 原生 Interceptor 可以
     * 通过 {@code invocation.proceed()} 换参数，但 MP 的插件链不是那个模型。）
     *
     * 那 MP 自己怎么改 SQL？—— 用 {@link PluginUtils#mpBoundSql(BoundSql)}：
     * 它把原 BoundSql 包一层，然后通过 MetaObject 反射**直接写 sql 字段**。
     * 分页插件加 LIMIT 就是走的这条路。我们照抄同一套，保持一致。
     *
     * ============================================================================
     * 二、直接改对象，会不会影响别的请求？（这是必须先想清楚的安全问题）
     * ============================================================================
     * 不会。{@code BoundSql} 虽然字段是 final，但它**不是共享单例**：
     *   · DynamicSqlSource.getBoundSql() → 每次 new 一个
     *   · RawSqlSource.getBoundSql()     → 每次 new 一个
     * 换句话说，一次查询一个 BoundSql，改它只影响这一次。
     *
     * 反过来说：如果哪天有人把某条 SQL 缓存成了单例、或者你把改写写到了
     * {@code MappedStatement.getSqlSource()} 上（那是**共享**的），
     * 就会出现"长沙的管理员查完，全省其他人也只能看长沙"这种灾难。
     * **改之前先问：这个对象是"每次新建"还是"大家共用"？**
     *
     * ============================================================================
     * 三、⚠️ 用 MP 内部工具类的代价，老实说
     * ============================================================================
     * {@code PluginUtils} 不在 MP 的公开 API 里（javadoc 上没有它），
     * 理论上小版本升级可能改签名。这是"用框架内部机制"必须付的账。
     * 之所以还是用它：自己写一遍反射 {@code MetaObject} 也是同样的原理、
     * 同样的脆弱度，还不如跟框架保持一致 —— 框架要改，两边一起改，好过自己的一套暗坑。
     *
     * 至于 final 字段为什么能被改：{@code MetaObject → Reflector → Field.set}，
     * MyBatis 的 Reflector 只跳过 **static final**，实例上的 final 字段会
     * {@code setAccessible(true)} 后写入。Java 17/21 上这条路依然有效 ——
     * MP 的分页在 JDK 21 上能跑，就是对这件事最好的证明。
     */
    private void applySql(BoundSql boundSql, String newSql) {
        PluginUtils.mpBoundSql(boundSql).sql(newSql);
        if (log.isDebugEnabled()) {
            log.debug("[数据权限] SQL 已改写为：{}", newSql);
        }
    }

    /**
     * 打印改写开销。统计边界是"算条件 + 查/写缓存 + 必要时解析"这一段，不含 SQL 真正执行。
     *
     * 注意"平均每条"的分母是**总处理次数**（真实解析 + 缓存命中），
     * 这样得到的是"权限功能摊到每条 SQL 上的平均成本"，才是能拿去和查库耗时比的那个数。
     */
    public String stats() {
        long parsed = rewriteCount.get();
        long hit = cacheHitCount.get();
        long total = parsed + hit;
        long ns = rewriteNanos.get();
        double avgMs = total == 0 ? 0 : ns / 1_000_000.0 / total;
        double hitRate = total == 0 ? 0 : hit * 100.0 / total;
        return String.format(
                "总处理=%d（真实解析=%d 缓存命中=%d 命中率=%.1f%%）累计耗时=%.1fms 平均每条=%.3fms 开关=%s",
                total, parsed, hit, hitRate, ns / 1_000_000.0, avgMs, enabled ? "开启" : "关闭");
    }

    // ==========================================================================
    // 三、读注解
    // ==========================================================================

    /**
     * MappedStatement 的 id 形如 {@code com.xx.Mapper.selectXxx}。
     * 拆出类名和方法名，反射到 Method 上取 @DataScope。
     *
     * 【为什么不用 MyBatis 提供的更"正规"的入口】
     * 没有。MyBatis 没有"把 Mapper 方法元数据直接传给你"的钩子，
     * 反射是业界通行做法（MyBatis-Plus 自己的 @InterceptorIgnore 也是这么实现的）。
     */
    private Optional<DataScope> findAnnotation(String msId) {
        return annotationCache.computeIfAbsent(msId, id -> {
            int dot = id.lastIndexOf('.');
            if (dot <= 0) {
                return Optional.empty();
            }
            String className = id.substring(0, dot);
            String methodName = id.substring(dot + 1);
            try {
                Class<?> clazz = Class.forName(className);
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(methodName)) {
                        DataScope ds = m.getAnnotation(DataScope.class);
                        if (ds != null) {
                            return Optional.of(ds);
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                // 理论上不会发生：能执行说明类已经加载了。
                // 真发生了说明 XML 的 namespace 和实际接口名不一致（手写 XML 常见笔误）。
                log.warn("[数据权限] 找不到 Mapper 接口类，按'未标注'处理：{}", className);
            }
            return Optional.empty();
        });
    }

    /**
     * 暴露给"旁路扫描"用：这个 Mapper 方法标了没有？
     * 手册里"漏网之鱼怎么查"一节用它做示例。
     */
    public boolean isProtected(String msId) {
        return findAnnotation(msId).isPresent();
    }
}

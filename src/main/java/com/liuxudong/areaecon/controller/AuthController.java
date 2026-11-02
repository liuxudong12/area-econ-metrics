package com.liuxudong.areaecon.controller;

import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.entity.SysUser;
import com.liuxudong.areaecon.mapper.SysUserMapper;
import com.liuxudong.areaecon.security.DataScopeContext;
import com.liuxudong.areaecon.security.DataScopeReport;
import com.liuxudong.areaecon.security.LoginUser;
import com.liuxudong.areaecon.security.UserContextInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 身份相关的辅助接口。
 *
 * ⚠️ 必须先说清楚：**本项目没有登录功能**。
 * 这三个接口是"实验用的说明牌"，不是产品功能。
 * 生产环境的对应位置应该是登录页 + token 签发 + 用户管理后台。
 *
 * 之所以把它们做出来，是因为数据权限的所有实验都要回答一个问题：
 * "现在这个用户应该看到多少行？"—— 没有 /users 接口，你连用户 id 都要去翻数据库。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SysUserMapper sysUserMapper;
    private final DataScopeReport dataScopeReport;
    private final com.liuxudong.areaecon.security.DataScopeInnerInterceptor dataScopeInterceptor;

    public AuthController(SysUserMapper sysUserMapper, DataScopeReport dataScopeReport,
                          com.liuxudong.areaecon.security.DataScopeInnerInterceptor dataScopeInterceptor) {
        this.sysUserMapper = sysUserMapper;
        this.dataScopeReport = dataScopeReport;
        this.dataScopeInterceptor = dataScopeInterceptor;
    }

    /**
     * 我是谁 —— 用来确认"这次的请求头真的被解析了"。
     *
     * <pre>
     *   curl -H "X-User-Id: 2" http://localhost:18080/api/auth/me
     *   → {"code":0,"data":{"id":2,"username":"changsha","dataScope":"REGION",
     *                       "regionCode":"4301","scopeKey":"R4301", ...}}
     * </pre>
     *
     * 【它为什么有用】
     * 排查"数据不对"时，第一个要确认的不是 SQL，而是**身份对不对**。
     * 十个里有八个的情况是：你以为自己在用长沙的账号，其实请求头没带上，
     * 或者带成了 1（全量管理员）。有这个接口，30 秒能排除掉这类问题。
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        LoginUser u = DataScopeContext.get();
        if (u == null) {
            // 理论上走不到：UserContextInterceptor 已经把"没身份"挡成 401 了。
            // 但拿不到用户时**不能返回 null 假装成功** —— 那会让调用方以为"身份为空"是正常的。
            return Result.fail(401, "当前请求没有身份信息");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("displayName", u.displayName());
        m.put("roleCode", u.roleCode());
        m.put("dataScope", u.dataScope());
        m.put("regionCode", u.regionCode());
        // scopeKey 是缓存 key 里用的权限指纹，暴露出来方便和 redis-cli 里看到的 key 对照
        m.put("scopeKey", u.scopeKey());
        m.put("isAllScope", u.isAllScope());
        return Result.ok(m);
    }

    /**
     * 系统里有哪些用户 —— 实验用的"花名册"。
     *
     * 【为什么这个接口不要求登录】
     * 因为它是"说明书"：不知道有哪些 id 就没法开始做实验（鸡生蛋）。
     * 真实项目里这属于**严重的信息泄露**（把账号列表白送给未登录的人），
     * 所以 WebConfig 里把它单独排除在拦截器之外，并在注释里标了"上线要删"。
     *
     * 这种"为了方便而临时放宽"的接口，必须满足两个条件才敢留：
     *   ① 全项目只有这一处，且加了醒目标记；
     *   ② 交付前一定会被扫到（所以放在 AuthController 这种显眼的地方，
     *      而不是藏在某个业务 Controller 的角落里）。
     */
    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        List<SysUser> list = sysUserMapper.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (SysUser u : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("roleCode", u.getRoleCode());
            m.put("dataScope", u.getDataScope());
            m.put("regionCode", u.getRegionCode());
            m.put("header", UserContextInterceptor.HEADER_USER_ID + ": " + u.getId());
            result.add(m);
        }
        return Result.ok(result);
    }

    /**
     * 权限标注清单 —— "哪些 SQL 受数据权限约束"。
     *
     * 输出里三种 verdict：
     *   PROTECTED  已标 @DataScope
     *   SUSPECT    看起来是查询却没标 —— **需要人工确认的那部分**
     *   WRITE      写操作（本项目按"查询过滤"做权限，写操作没做归属校验）
     *
     * 【用它的正确姿势】
     * 每次新增 Mapper 方法后跑一遍，SUSPECT 列表里每一条都要能说出"为什么不用标"。
     * 说不出来的，就是漏洞。
     */
    @GetMapping("/data-scope")
    public Result<List<DataScopeReport.Item>> dataScope() {
        return Result.ok(dataScopeReport.scan());
    }

    /**
     * 改写开销 —— 回答"每个请求都解析一遍 SQL，不慢吗"。
     *
     * <pre>
     *   GET /api/auth/data-scope-stats
     *   → "改写次数=214 累计耗时=38.6ms 平均每条=0.180ms 开关=开启"
     * </pre>
     *
     * 这个数字要和"一次查询本身多快"放在一起看才有意义：
     * 一次统计查询查库要 5~20ms，改写 0.2ms —— **占比 1%~4%**。
     * 换来的是"数据库只扫该扫的行"，通常净赚十倍以上。
     * 手册里的实验 E8 就是这么算的。
     */
    @GetMapping("/data-scope-stats")
    public Result<String> dataScopeStats() {
        return Result.ok(dataScopeInterceptor.stats());
    }
}

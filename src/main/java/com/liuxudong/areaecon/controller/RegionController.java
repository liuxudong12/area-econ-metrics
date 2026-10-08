package com.liuxudong.areaecon.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.liuxudong.areaecon.common.Result;
import com.liuxudong.areaecon.entity.Region;
import com.liuxudong.areaecon.service.RegionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 区域查询接口 —— 本项目**第一个真正从数据库读数据**的接口。
 *
 * 【和 Day1 的 PingController 有什么区别？】
 * /ping 返回的是一个写死的字符串，没有任何技术含量。
 * 这里开始，数据从 MySQL 取出来，经过 Service、Mapper，再变成 JSON 返回给你。
 * 这一条链路（Controller → Service → Mapper → DB）就是后端开发的主干道，
 * 后面所有功能都是在这条路上换不同的"货物"。
 *
 * 【@RequestMapping("/api/region") 是什么】
 * 给这个类下所有接口加一个统一前缀。类里写 @GetMapping("/list")，
 * 最终地址就是 /api/region/list。
 * 好处：接口多了之后不会撞名，一眼能看出这个接口属于哪个模块。
 *
 * 【构造器注入 —— 注意这里没有 @Autowired】
 * 只写一个构造器、由 Spring 传入依赖，叫"构造器注入"。这是目前官方推荐的方式：
 *   ① 依赖是 final 的，不可能被中途改掉；
 *   ② 依赖写死在构造器里，一眼看得出这个类依赖谁；
 *   ③ 方便写单元测试（可以直接 new 一个假的 Service 传进去）。
 * 只有一个构造器时，@Autowired 可以省掉。
 *
 * 【统一返回 Result 的原因】
 * 前端不用为每个接口写不同的解析逻辑，永远读 code / message / data 三个字段。
 */
@RestController
@RequestMapping("/api/region")
public class RegionController {

    private final RegionService regionService;

    public RegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    /**
     * 查区域列表。
     *
     * GET /api/region/list          → 查全部 7 个区域
     * GET /api/region/list?level=2  → 只查市级
     *
     * @RequestParam(required = false) 表示这个参数可以不传。
     * 不写它的话，参数就是必填的，不传会直接报 400。
     */
    @GetMapping("/list")
    public Result<List<Region>> list(@RequestParam(required = false) Integer level) {
        List<Region> list = regionService.listByLevel(level);
        return Result.ok(list);
    }

    /**
     * 分页查询区域。
     *
     * GET /api/region/page?pageNum=1&pageSize=3
     * GET /api/region/page?pageNum=1&pageSize=3&keyword=娄底
     *
     * defaultValue 表示不传时的默认值。
     * 返回的 IPage 对象里包含：records（本页数据）、total（总条数）、
     * current（当前页）、size（每页条数）、pages（总页数）。
     *
     * 【面试常问】分页的 total 是怎么来的？
     * 分页插件会把你的查询改写成 COUNT(*) 先跑一次拿总数，再拼 LIMIT 跑一次拿数据。
     * 所以一次分页 = 两条 SQL。数据量大时 count 本身也可能变慢。
     */
    @GetMapping("/page")
    public Result<IPage<Region>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String keyword) {
        IPage<Region> page = regionService.pageQuery(pageNum, pageSize, keyword);
        return Result.ok(page);
    }

    /**
     * 按区域编码查单条。
     *
     * GET /api/region/431300  → 返回娄底市
     *
     * @PathVariable 表示这个值来自路径的一部分，不是查询参数。
     * 注意：路径变量写的是 {regionCode}，方法参数名必须一模一样。
     *
     * 【一个细节】类上还有 /list 和 /page 两个固定路径。
     * Spring 匹配时"精确路径优先于路径变量"，所以 /api/region/list 不会被当成
     * regionCode = "list" 处理。这个优先级规则面试也可能被问到。
     */
    @GetMapping("/{regionCode}")
    public Result<Region> detail(@PathVariable String regionCode) {
        Region region = regionService.getByCode(regionCode);
        if (region == null) {
            return Result.fail("区域不存在：" + regionCode);
        }
        return Result.ok(region);
    }
}

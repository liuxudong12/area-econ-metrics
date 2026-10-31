package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户（sys_user）—— Day 13 数据权限的"人"这一侧。
 *
 * 【这个项目没有登录功能，为什么还要用户表】
 * 因为数据权限的本质是"回答一个问题：**现在这次请求是谁发的**"。
 * 没有"谁"，就谈不上"他能看什么"。
 *
 * 完整做法是 JWT / Session + 拦截器解析出当前用户。
 * 本项目为了把注意力集中在"权限过滤"这件事上，登录用了一个极简替代：
 * 请求头 {@code X-User-Id: 2} 就代表"我是 2 号用户"。
 *
 * ⚠️ 这个替代方案**绝对不能上生产** —— 请求头是客户端随便改的，
 * 改一个数字就变成了别人。真实项目里"当前用户"必须来自**服务端可验证的凭证**
 * （JWT 签名、Session、OAuth token），绝不能来自客户端可自由填写的字段。
 * 手册里"这套东西离生产还差什么"一节会专门列清楚。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名 */
    private String username;

    /** 姓名（展示与日志用） */
    private String displayName;

    /** 角色编码。本项目**不用它做权限判定**，只用于日志和前端展示 */
    private String roleCode;

    /** 数据范围：ALL / REGION。存字符串而不是存数字，是为了 redis-cli / SQL 里能一眼看懂 */
    private String dataScope;

    /**
     * 辖区编码前缀：43（全省）/ 4301（长沙市）/ 430102（单个区县）。
     * dataScope = ALL 时这个字段被忽略（可以是 null）。
     */
    private String regionCode;

    /** 1 启用 / 0 停用 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

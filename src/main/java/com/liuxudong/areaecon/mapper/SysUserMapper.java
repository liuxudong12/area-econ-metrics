package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 *
 * 【为什么这个 Mapper 一行方法都没有】
 * 因为 BaseMapper 已经提供了 {@code selectById} —— 我们只需要"按 id 查一个人"，
 * 这是最标准的单表主键查询，框架生成的 SQL 就是我们要的：
 * <pre>
 *   SELECT id, username, ... FROM sys_user WHERE id = ? AND deleted = 0
 * </pre>
 * 注意那个 {@code AND deleted = 0} 是 @TableLogic 自动加的 ——
 * 这也是本类**故意不写手写 SQL** 的原因之一：手写 SQL 就得自己记得加。
 *
 * 【一个反例：为什么这里不能用 @DataScope 注解】
 * 用户表本身**不受数据权限约束**。
 * 如果给 {@code selectById} 也套上"只能看自己辖区的用户"，那么
 * 张家界的管理员查自己（id=4，region 4308）时，SQL 会变成
 * {@code WHERE id = 4 AND region_code LIKE '4308%'} —— 看起来没问题，
 * 但只要辖区配置换成空值，他就**查不到自己**，然后连登录都登不进去。
 * 数据权限的作用对象是"业务数据"，不是"权限系统自己"。
 * 这是权限设计里的一条硬规矩：**授权系统不能被自己的规则管辖。**
 * （否则一旦配错，所有人都进不来，而且没人能进去修 —— 死锁。）
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}

package com.liuxudong.areaecon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liuxudong.areaecon.entity.AsyncTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 异步任务 Mapper。
 *
 * 只有最基本的增删改查，所以一行 SQL 都不用写 —— BaseMapper 已经全给了。
 * 这是 MyBatis-Plus 的常规用法：**单表走 BaseMapper，多表/复杂查询才手写 XML**。
 *
 * 表上的唯一索引 uk_biz_key 会在这里体现出来：
 * 插入重复 biz_key 时，insert() 会抛 org.springframework.dao.DuplicateKeyException。
 * 注意它是 Spring 的异常（不是 MySQL 的 SQLIntegrityConstraintViolationException），
 * 因为 MyBatis-Spring 做了异常转译 —— 好处是你换数据库时不用改 catch 的异常类型。
 */
@Mapper
public interface AsyncTaskMapper extends BaseMapper<AsyncTask> {
}

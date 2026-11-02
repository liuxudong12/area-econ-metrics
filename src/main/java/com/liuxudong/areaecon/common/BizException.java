package com.liuxudong.areaecon.common;

import java.io.Serializable;

/**
 * 业务异常 —— 用来表达"业务规则不满足"，而不是"程序出 bug 了"。
 *
 * 【为什么需要自己定义一个异常类？直接 throw new RuntimeException("xxx") 不行吗？】
 * 区别在于"谁能处理"：
 *
 *   RuntimeException     —— 兜底，含义是"出 bug 了/系统故障"，应当记 error 日志 + 告警
 *   BizException        —— 含义是"用户操作不合法"，属于预期内，记 warn 就够，直接把话告诉用户
 *
 * 有了这个区分，全局异常处理器就能分开处理：
 *   catch(BizException)  → 原样把 message 返回给前端（可以给用户看）
 *   catch(Exception)     → 返回"系统内部错误"，不能把堆栈信息暴露出去（可能含表名、SQL、路径）
 *
 * 【为什么继承 RuntimeException 而不是 Exception？】
 * 因为 Spring 的事务默认**只对 RuntimeException 和 Error 回滚**。
 * 如果继承 Exception（受检异常），事务不会回滚，或者你得在 @Transactional(rollbackFor = Exception.class)
 * 里额外声明。继承 RuntimeException 就少踩一个坑。
 *
 * 【为什么不用受检异常（checked）？】
 * 受检异常会污染整条调用链的方法签名（每层都得 throws），而"编码重复""区域不存在"
 * 这类业务失败在每层都没法处理，只能一路往上抛给全局处理器。所以业务异常一律用非受检异常。
 */
public class BizException extends RuntimeException implements Serializable {

    /**
     * 错误码。
     *
     * 之前只有一句话，所有业务异常统一返回 500。加了数据权限之后不够用了 ——
     * 因为现在有两类"不是用户的错、也不是系统的错"的情况必须区分开：
     *   401 没带身份      → 用户该去登录
     *   403 身份不够/数据越权 → 用户该去找管理员申请权限
     * 前端拿到 401 会跳登录页，拿到 403 只提示"无权限"，处理方式完全不同。
     * **把所有失败都压成 500，等于让前端失去判断能力。**
     *
     * 默认值仍然是 500，所以前 12 天所有 {@code new BizException("...")} 的调用
     * 一个字都不用改 —— 这是加字段时该有的兼容性。
     */
    private final int code;

    public BizException(String message) {
        this(Result.CODE_FAIL, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = Result.CODE_FAIL;
    }

    public int getCode() {
        return code;
    }
}

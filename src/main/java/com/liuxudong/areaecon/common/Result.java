package com.liuxudong.areaecon.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结构。
 *
 * 为什么要有它？——如果每个接口都直接返回数据，前端拿到的格式会千奇百怪：
 * 有的返回数组，有的返回对象，出错时又返回一段错误文字。前端没法统一处理。
 *
 * 所以企业项目里几乎都会包一层：所有接口都返回 { code, message, data } 这个固定形状。
 *   code = 0     表示成功
 *   code != 0    表示失败，message 里写失败原因
 *   data         装真正的业务数据
 *
 * 面试常问：「你们的接口返回格式怎么设计的？为什么成功用 0 不用 200？」
 * 答：这是约定，0 是"业务成功码"，和 HTTP 状态码 200 是两回事——
 *     HTTP 200 只代表"请求到达了服务器"，业务是否成功要看 code。
 */
@Data
public class Result<T> implements Serializable {

    /** 成功码 */
    public static final int CODE_SUCCESS = 0;
    /** 失败码 */
    public static final int CODE_FAIL = 500;

    private Integer code;
    private String message;
    private T data;

    /** 成功，带数据 */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(CODE_SUCCESS);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /** 成功，不带数据 */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 失败，只给原因 */
    public static <T> Result<T> fail(String message) {
        Result<T> r = new Result<>();
        r.setCode(CODE_FAIL);
        r.setMessage(message);
        return r;
    }

    /** 失败，自定义码 */
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}

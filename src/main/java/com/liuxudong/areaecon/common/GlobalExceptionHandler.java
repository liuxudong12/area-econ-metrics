package com.liuxudong.areaecon.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器 —— 让"所有异常"都自动变成规范的 {code, message, data}。
 *
 * 【没有它会发生什么？】
 * 你在 Service 里 throw 一个 BizException，Spring 默认会返回 HTTP 500
 * 加上一大段 Java 堆栈（White Label Error Page 或 JSON 堆栈）。
 * 问题有三个：
 *   ① 前端拿到的格式和正常响应完全不一样，无法统一解析；
 *   ② 堆栈里会暴露类名、SQL、表名、文件路径 —— 给攻击者送情报；
 *   ③ 用户看到"500 Internal Server Error"，不知道自己做错了什么。
 *
 * 【@RestControllerAdvice 是怎么生效的】
 * 它 = @ControllerAdvice + @ResponseBody。
 * @ControllerAdvice 相当于给**所有** Controller 套了一层 try-catch：
 * 任何 Controller 抛出的异常，都会先来到这里，由匹配的 @ExceptionHandler 方法处理。
 * 所以业务代码里可以放心地 throw，不用每层都写 try-catch。
 *
 * 【匹配规则：就近原则】
 * 抛出的异常会找"最匹配"的处理器。比如抛 BizException，
 * 它既能被 handleBiz 接住（精确匹配），也能被 handleException 接住（所有异常的父亲），
 * Spring 会选**最具体**的那个 —— 也就是 handleBiz。
 *
 * 面试常问："你们项目的异常怎么处理的？"
 * 标准答法：@RestControllerAdvice + 自定义 BizException + 按异常类型分流，
 * 业务异常把 message 返给用户，系统异常记 error 日志但只返回统一话术。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常 —— 可以直接把原因告诉用户。
     * 例如："区域编码已存在：431300"
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        // 业务异常是"预期内"的，用 warn 就够了，不用 error（否则日志里全是噪音、告警会被淹没）
        log.warn("[业务异常] {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * 参数校验失败 —— 对应 @RequestBody 上的 @Valid。
     * 例如请求体里 regionName 为空，会抛 MethodArgumentNotValidException。
     *
     * 一个请求可能有多个字段同时不合法，所以这里把所有错误拼起来一起返回，
     * 让前端一次性提示完，而不是"改一个错、再报下一个"。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("[参数校验失败] {}", message);
        return Result.fail(400, message);
    }

    /**
     * 参数绑定失败 —— 对应表单/查询参数（@ModelAttribute、@RequestParam 对象绑定）的校验。
     * 和上面那条的区别只是"参数从哪来"，处理方式完全一样。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        log.warn("[参数绑定失败] {}", message);
        return Result.fail(400, message);
    }

    /**
     * 兜底处理器 —— 所有上面没接住的异常，最后都落在这里。
     *
     * ⚠️ 两个要点：
     *   ① 一定要 log.error("...", e) 把**完整堆栈**记下来。因为返回给用户的是一句空话，
     *      真正的排查线索只存在于日志里。忘了打，线上就瞎了。
     *   ② 绝对不能把 e.getMessage() 直接返回给前端 —— 那可能包含 SQL、表名、路径。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[系统异常] 未预期的错误", e);
        return Result.fail("系统内部错误，请稍后重试或联系管理员");
    }

    /**
     * 把字段错误格式化成 "字段名: 提示语"，例如 "regionCode: 区域编码不能为空"。
     * 带上字段名，前端才知道该给哪个输入框标红。
     */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}

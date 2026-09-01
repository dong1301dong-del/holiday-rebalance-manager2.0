package com.tiaoxiu.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>统一把各类异常转换成前端约定的 {@link Result} 结构，保证任何异常情况下
 * 前端拿到的都是 {@code {code, message, data}} 而非 Spring 默认的错误页 / HTML，
 * 便于 axios 拦截器统一解析。
 *
 * <p>处理优先级：业务异常 → 参数非法异常 → 兜底未知异常。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常：直接沿用异常自带的错误码与提示文案。
     *
     * @param e 业务或服务层抛出的 {@link BizException}
     * @return code 为非 0 的失败响应
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数非法异常：归入通用业务失败（错误码 1）。
     *
     * @param e 参数校验不通过时抛出的 {@link IllegalArgumentException}
     * @return code = 1 的失败响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegal(IllegalArgumentException e) {
        return Result.fail(1, e.getMessage());
    }

    /**
     * 兜底处理所有未预期异常：HTTP 状态码置为 500，错误码固定 500。
     *
     * <p>这里保留原始 message 便于排障，正式环境若担心信息泄露可改为只返回通用文案 + 打印日志。
     *
     * @param e 任何未被上面两个处理器拦截的异常
     * @return code = 500 的失败响应，HTTP 状态 500
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        return Result.fail(500, "服务器内部错误：" + e.getMessage());
    }
}

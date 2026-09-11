package com.tiaoxiu.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

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

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     * 处理数据库唯一约束冲突：通常由并发插入重复业务数据触发。
     *
     * <p>不直接把底层 SQL 异常抛给用户，统一提示「数据可能重复」，避免泄露表结构。
     *
     * @param e 唯一键冲突抛出的 {@link DataIntegrityViolationException}
     * @return code = 1 的失败响应
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("数据库唯一约束兜底拦截到重复数据：{}", e.getMostSpecificCause().getMessage());
        return Result.fail(1, "该数据可能已存在，也可能由他人刚刚提交，请刷新后重试");
    }

    /**
     * 兜底处理所有未预期异常：HTTP 状态码置为 500，错误码固定 500。
     *
     * <p>出于安全考虑不再回显原始异常 message（避免泄露内部实现细节），改为生成可追溯的
     * {@code traceId} 并打完整 ERROR 日志，提示文案中仅带上 traceId 供排障时定位。
     *
     * @param e 任何未被上面处理器拦截的异常
     * @return code = 500 的失败响应，HTTP 状态 500
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.error("未处理异常 traceId={}", traceId, e);
        return Result.fail(500, "服务器内部错误，请稍后重试（错误编号 " + traceId + "）");
    }
}

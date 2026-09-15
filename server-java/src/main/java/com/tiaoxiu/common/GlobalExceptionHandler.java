package com.tiaoxiu.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

/**
 * 全局异常处理。
 *
 * <p>统一把各类异常转换成前端约定的 {@link Result} 结构，保证任何异常情况下
 * 前端拿到的都是 {@code {code, message, data}} 而非 Spring 默认的错误页 / HTML，
 * 便于 axios 拦截器统一解析。
 *
 * <p>处理优先级：业务异常 → 参数非法异常 → 唯一约束冲突 → 客户端错误（404/405/400）→ 兜底未知异常。
 * 客户端错误必须在兜底分支之前显式接住，否则会被误报成 500。
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
     * 处理「接口不存在」：请求路径没有任何控制器或静态资源可匹配。
     *
     * <p>Spring 6.1 起会把这类请求抛成 {@link NoResourceFoundException}，若不加处理会落入下面的
     * 兜底分支被误报成 500「服务器内部错误」，既误导用户也会污染错误日志。故此处明确返回 404。
     *
     * @param e 未匹配到处理器时抛出的 {@link NoResourceFoundException}
     * @return code = 404 的失败响应，HTTP 状态 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("请求的接口不存在：{}", e.getResourcePath());
        return Result.fail(404, "请求的接口不存在");
    }

    /**
     * 处理「请求方法不被支持」：路径存在但 HTTP 方法不匹配（如用 GET 调 POST 接口）。
     *
     * <p>同样属于客户端错误，不应返回 500；明确给出 405 与允许的方法清单，便于前端定位。
     *
     * @param e 方法不匹配时抛出的 {@link HttpRequestMethodNotSupportedException}
     * @return code = 405 的失败响应，HTTP 状态 405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不被支持：{}，该接口仅允许 {}", e.getMethod(), e.getSupportedHttpMethods());
        return Result.fail(405, "请求方式不支持，该接口仅允许 " + e.getSupportedHttpMethods());
    }

    /**
     * 处理「请求体无法解析」：通常是前端提交的 JSON 格式错误或缺少请求体。
     *
     * @param e 请求体解析失败时抛出的 {@link HttpMessageNotReadableException}
     * @return code = 400 的失败响应，HTTP 状态 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.fail(400, "请求数据格式错误，请检查提交内容");
    }

    /**
     * 处理「缺少必填查询参数」。
     *
     * @param e 缺少参数时抛出的 {@link MissingServletRequestParameterException}
     * @return code = 400 的失败响应，HTTP 状态 400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数：{}", e.getParameterName());
        return Result.fail(400, "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 处理「参数类型不匹配」：如把非数字传给整型参数。
     *
     * @param e 参数类型转换失败时抛出的 {@link MethodArgumentTypeMismatchException}
     * @return code = 400 的失败响应，HTTP 状态 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配：{} = {}", e.getName(), e.getValue());
        return Result.fail(400, "参数格式不正确：" + e.getName());
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

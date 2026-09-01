package com.tiaoxiu.common;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>系统中所有「可预期的业务校验失败」都通过抛出本异常来中断流程，
 * 由 {@link GlobalExceptionHandler} 统一捕获并转换为 {@code code != 0} 的 {@link Result} 响应，
 * 从而避免在 Controller / Service 中层层返回错误码。
 *
 * <p>约定：{@code code = 0} 表示成功，非 0 表示失败；本类的默认错误码为 1（通用业务失败）。
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码，非 0 即失败；会原样透传给前端 Result.code */
    private final int code;

    /**
     * 构造一个指定错误码的业务异常。
     *
     * @param code    业务错误码（非 0），前端可据此做差异化提示，如 401 需重新登录
     * @param message 面向用户的可读错误描述
     */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造一个通用业务异常，错误码固定为 1。
     *
     * @param message 面向用户的可读错误描述
     */
    public BizException(String message) {
        super(message);
        // 未显式指定错误码时统一按「通用业务失败」处理
        this.code = 1;
    }
}

package com.tiaoxiu.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一接口响应结构。
 *
 * <p>所有 RESTful 接口（除文件下载等二进制流外）都返回本结构，
 * 前端 axios 响应拦截器统一按 {@code code} 判断成败：0 为成功，非 0 为失败并提示 {@code message}。
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    /** 响应码：0 成功，非 0 失败（业务自定义错误码见各 Service 抛出的 BizException） */
    private int code;

    /** 提示信息，失败时为面向用户的错误描述 */
    private String message;

    /** 业务数据载荷，失败时通常为 null */
    private T data;

    /**
     * 构造成功响应并携带数据。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return code = 0 的成功响应
     */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "ok";
        r.data = data;
        return r;
    }

    /**
     * 构造成功响应但不携带数据（用于新增 / 修改 / 删除等无返回值的操作）。
     *
     * @param <T> 数据类型
     * @return code = 0 的成功响应
     */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 构造失败响应并指定错误码。
     *
     * @param code    业务错误码（非 0）
     * @param message 错误描述
     * @param <T>     数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    /**
     * 构造失败响应，错误码取默认值 1（通用业务失败）。
     *
     * @param message 错误描述
     * @param <T>     数据类型
     * @return code = 1 的失败响应
     */
    public static <T> Result<T> fail(String message) {
        return fail(1, message);
    }
}

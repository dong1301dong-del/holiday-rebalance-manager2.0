package com.tiaoxiu.dto;

import lombok.Data;

/**
 * 系统配置相关的出入参定义。
 */
public class ConfigDto {

    /** 新增或修改系统配置的请求参数（存在则更新、不存在则新增） */
    @Data
    public static class UpsertRequest {
        /** 配置键，全局唯一，如 overtime.dailyCap */
        private String key;
        /** 配置值，统一以字符串传输 */
        private String value;
        /** 配置说明，展示在系统配置页面 */
        private String description;
    }
}

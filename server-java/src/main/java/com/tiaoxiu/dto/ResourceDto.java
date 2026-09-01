package com.tiaoxiu.dto;

import lombok.Data;

/**
 * 资源（菜单 / 按钮 / 接口权限）相关的出入参定义。
 */
public class ResourceDto {

    /** 新增资源的请求参数 */
    @Data
    public static class CreateRequest {
        /** 父资源 ID；顶层资源传 null */
        private Long parentId;
        /** 资源类型：MENU / BUTTON / API */
        private String type;        // MENU / BUTTON / API
        /** 权限编码，全局唯一，如 overtime:add */
        private String code;        // 权限编码，全局唯一
        /** 资源名称 */
        private String name;
        /** 前端路由路径 */
        private String path;
        /** 前端组件文件路径 */
        private String component;
        /** 图标标识 */
        private String icon;
        /** 排序号 */
        private Integer sort;
        /** 接口级权限标识，通常与 code 相同 */
        private String permission;
    }

    /**
     * 修改资源的请求参数。
     *
     * <p>注意：不含 {@code code} 字段——权限编码一旦投入使用就会被前后端引用，
     * 因此设计为不可修改，避免改动后大面积失效。
     */
    @Data
    public static class UpdateRequest {
        /** 父资源 ID */
        private Long parentId;
        /** 资源类型 */
        private String type;
        /** 资源名称 */
        private String name;
        /** 前端路由路径 */
        private String path;
        /** 前端组件文件路径 */
        private String component;
        /** 图标标识 */
        private String icon;
        /** 排序号 */
        private Integer sort;
        /** 接口级权限标识 */
        private String permission;
        /** 状态：ACTIVE 启用 / DISABLED 停用 */
        private String status;
    }
}

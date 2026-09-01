package com.tiaoxiu.dto;

import lombok.Data;
import java.util.List;

/**
 * 角色与权限相关的出入参定义。
 */
public class RoleDto {

    /** 新增角色的请求参数 */
    @Data
    public static class CreateRequest {
        /** 角色编码，全局唯一，如 DEPT_MANAGER */
        private String code;
        /** 角色名称 */
        private String name;
        /** 角色描述 */
        private String description;
        /** 状态：ACTIVE 正常 / DISABLED 停用 */
        private String status;
    }

    /**
     * 修改角色的请求参数。
     *
     * <p>不含 {@code code}：角色编码会被后端鉴权逻辑引用，创建后不允许修改。
     */
    @Data
    public static class UpdateRequest {
        /** 角色名称 */
        private String name;
        /** 角色描述 */
        private String description;
        /** 状态：ACTIVE / DISABLED */
        private String status;
    }

    /** 给角色分配资源的请求参数 */
    @Data
    public static class AssignResourcesRequest {
        /** 资源 ID 列表，整体覆盖该角色原有的权限绑定 */
        private List<Long> resourceIds;
    }
}

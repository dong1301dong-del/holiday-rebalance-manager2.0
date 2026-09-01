package com.tiaoxiu.dto;

import lombok.Data;
import java.util.List;

/**
 * 认证与登录相关的出入参定义。
 *
 * <p>覆盖登录请求、修改密码请求、登录成功后的完整响应（令牌 + 用户信息 + 动态菜单 + 权限编码）。
 */
public class AuthDto {

    /** 登录请求参数 */
    @Data
    public static class LoginRequest {
        /** 登录账号 */
        private String username;
        /** 明文密码，由前端表单提交，服务端用 BCrypt 比对 */
        private String password;
    }

    /** 修改密码请求参数 */
    @Data
    public static class ChangePasswordRequest {
        /** 原密码，用于身份二次确认 */
        private String oldPassword;
        /** 新密码，需满足强密码规则 */
        private String newPassword;
    }

    /** 当前登录用户的基本信息 */
    @Data
    public static class UserInfo {
        /** 用户 ID */
        private Long id;
        /** 登录账号 */
        private String username;
        /** 姓名 */
        private String name;
        /** 所属部门 */
        private String department;
        /** 职位 */
        private String position;
        /** 角色编码列表，如 [ADMIN, CLERK] */
        private List<String> roles;
    }

    /**
     * 动态菜单节点。
     *
     * <p>由后端根据用户拥有的资源权限组装成树后返回，前端据此渲染侧边栏，
     * 避免前端硬编码菜单导致越权可见。
     */
    @Data
    public static class MenuNode {
        /** 资源 ID */
        private Long id;
        /** 父节点 ID，顶层为 null */
        private Long parentId;
        /** 菜单名称 */
        private String name;
        /** 前端路由路径 */
        private String path;
        /** 前端组件文件路径 */
        private String component;
        /** 图标标识 */
        private String icon;
        /** 排序号 */
        private Integer sort;
        /** 权限编码，如 overtime:view */
        private String code;
        /** 子菜单，叶子节点为空列表 */
        private List<MenuNode> children;
    }

    /** 登录成功响应 */
    @Data
    public static class LoginResponse {
        /** JWT 令牌，后续请求放在 Authorization: Bearer 头中 */
        private String token;
        /** 是否必须修改初始密码；为 true 时前端应强制跳转到改密页 */
        private Boolean mustChangePwd;
        /** 当前用户信息 */
        private UserInfo user;
        /** 当前用户可见的菜单树 */
        private List<MenuNode> menus;
        /** 当前用户拥有的全部权限编码，前端据此控制按钮显隐 */
        private List<String> permissions;
    }
}

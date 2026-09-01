package com.tiaoxiu.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理相关的出入参定义。
 */
public class UserDto {

    /** 新增用户的请求参数 */
    @Data
    public static class CreateRequest {
        /** 登录账号，全局唯一 */
        private String username;
        /** 姓名 */
        private String name;
        /** 初始密码；不传则使用系统默认密码，并强制首次登录修改 */
        private String password;      // 可选，缺省用默认密码
        /** 所属部门 */
        private String department;
        /** 职位 */
        private String position;
        /** 入职日期 */
        private LocalDate hireDate;
        /** 邮箱 */
        private String email;
        /** 手机号 */
        private String phone;
        /** 待授予的角色编码列表 */
        private List<String> roleCodes;
    }

    /** 修改用户的请求参数 */
    @Data
    public static class UpdateRequest {
        /** 登录账号，允许管理员编辑 */
        private String username;      // 管理员可编辑账号
        /** 姓名 */
        private String name;
        /** 所属部门 */
        private String department;
        /** 职位 */
        private String position;
        /** 入职日期 */
        private LocalDate hireDate;
        /** 邮箱 */
        private String email;
        /** 手机号 */
        private String phone;
        /** 状态：ACTIVE 正常 / FROZEN 冻结 */
        private String status;        // ACTIVE / FROZEN
        /** 角色编码列表，传入即整体覆盖该用户的原有角色 */
        private List<String> roleCodes; // 编辑时同步调整角色
    }

    /** 分配角色的请求参数 */
    @Data
    public static class AssignRolesRequest {
        /** 角色编码列表，整体覆盖原有角色 */
        private List<String> roleCodes;
    }

    /** 管理员重置密码的请求参数 */
    @Data
    public static class ResetPasswordRequest {
        /** 新密码，需满足强密码规则；重置后会强制该用户下次登录改密 */
        private String newPassword;
    }

    /** 用户列表/详情对外视图：补充角色列表与实时调休余额（实体本身不含这两列）。 */
    @Data
    public static class UserResponse {
        private Long id;
        private String username;
        private String name;
        private String department;
        private String position;
        private String status;
        private Boolean mustChangePwd;
        private List<String> roles;
        private BigDecimal balance;
        private LocalDate hireDate;
        private String email;
        private String phone;
        private LocalDateTime createdAt;
    }
}

package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统日志（审计日志）：记录所有关键操作，包括登录、增删改、导入导出、高风险日期变更等。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("系统日志表")
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created", columnList = "created_at"),
        @Index(name = "idx_audit_module", columnList = "module")
})
public class AuditLog {

    /** 日志级别 */
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";

    /** 操作结果 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";

    /** 业务模块 */
    public static final String MODULE_AUTH = "认证";
    public static final String MODULE_USER = "用户管理";
    public static final String MODULE_OVERTIME = "加班管理";
    public static final String MODULE_LEAVE = "调休使用记录";
    public static final String MODULE_HOLIDAY = "节假日日历";
    public static final String MODULE_REPORT = "统计报表";
    public static final String MODULE_SYSTEM = "系统";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    /** 操作人 id（未登录可为 null） */
    @Column(name = "user_id")
    @org.hibernate.annotations.Comment("用户ID")
    private Long userId;

    /** 操作人账号（冗余，便于展示与检索） */
    @Column(length = 40)
    @org.hibernate.annotations.Comment("登录账号")
    private String username;

    /** 操作人姓名 */
    @Column(length = 60)
    @org.hibernate.annotations.Comment("操作人姓名")
    private String userRealName;

    /** 业务模块 */
    @Column(name = "module", length = 40)
    @org.hibernate.annotations.Comment("所属模块")
    private String module;

    /** 操作类型：新增/编辑/删除/作废/导入/导出/登录/刷新... */
    @Column(length = 40)
    @org.hibernate.annotations.Comment("操作类型")
    private String action;

    /** 日志级别 INFO/WARN/ERROR */
    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("日志级别：INFO、WARN、ERROR")
    private String level = LEVEL_INFO;

    /** 操作对象描述，如 2026-01-01 / 张三 */
    @Column(length = 120)
    @org.hibernate.annotations.Comment("操作目标")
    private String target;

    /** 操作内容摘要 */
    @Column(columnDefinition = "TEXT")
    @org.hibernate.annotations.Comment("操作详情")
    private String detail;

    /** 变更前值（高风险操作留痕） */
    @Column(columnDefinition = "TEXT")
    @org.hibernate.annotations.Comment("变更前内容")
    private String beforeValue;

    /** 变更后值 */
    @Column(columnDefinition = "TEXT")
    @org.hibernate.annotations.Comment("变更后内容")
    private String afterValue;

    /** 操作结果 SUCCESS/FAIL */
    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("操作结果：SUCCESS、FAIL")
    private String result = RESULT_SUCCESS;

    /** 请求 IP */
    @Column(length = 60)
    @org.hibernate.annotations.Comment("操作IP")
    private String ip;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    /**
     * 持久化前回调：填充日志时间。
     *
     * <p>审计日志只追加不修改，因此没有 @PreUpdate，避免出现「日志被事后篡改」的可能。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

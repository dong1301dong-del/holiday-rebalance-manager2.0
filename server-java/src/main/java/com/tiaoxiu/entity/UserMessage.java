package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 站内消息（user_messages）。
 * <p>
 * 当前用于「调休余额透支」提醒：targetRoles 指定接收角色（如 ADMIN,CLERK）。
 * 当对应用户余额恢复为 >= 0 时，服务端自动把未处理的同类消息置 resolved=true，
 * 查询接口只返回 resolved=false 的消息（即「消息自动消失」）。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("余额透支预警消息表")
@Table(name = "user_messages", indexes = {
        @Index(name = "idx_um_user", columnList = "user_id"),
        @Index(name = "idx_um_type", columnList = "type"),
        @Index(name = "idx_um_resolved", columnList = "resolved")
})
public class UserMessage {

    /** 调休余额透支提醒 */
    public static final String TYPE_OVERDRAFT = "OVERDRAFT";

    /** 透支提醒的接收角色 */
    public static final String ROLES_ADMIN_CLERK = "ADMIN,CLERK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(nullable = false, length = 30)
    @org.hibernate.annotations.Comment("类型")
    private String type = TYPE_OVERDRAFT;

    /** 被提醒的员工（透支人） */
    @Column(name = "user_id")
    @org.hibernate.annotations.Comment("用户ID")
    private Long userId;

    /** 消息内容，如「【张三】当前调休余额不足，请注意！」 */
    @Column(columnDefinition = "TEXT")
    @org.hibernate.annotations.Comment("消息内容")
    private String content;

    /** 接收角色，逗号分隔 */
    @Column(name = "target_roles", length = 60)
    @org.hibernate.annotations.Comment("接收角色编码，逗号分隔（如 ADMIN,CLERK）")
    private String targetRoles;

    /** 是否已读 */
    @Column(name = "is_read", nullable = false)
    @org.hibernate.annotations.Comment("是否已读：true 已读")
    private Boolean read = false;

    /** 是否已处理（余额恢复后自动置 true） */
    @Column(nullable = false)
    @org.hibernate.annotations.Comment("是否已处理：true 表示对应员工余额已恢复，消息自动消失")
    private Boolean resolved = false;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    @org.hibernate.annotations.Comment("处理时间（余额恢复时自动更新）")
    private LocalDateTime resolvedAt;

    /**
     * 持久化前回调：填充创建时间，并为 type / read / resolved 兜底默认值。
     *
     * <p>status 类的布尔字段统一在这里兜底，避免调用方漏传导致消息一直显示为「未处理」而无法自动消失。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (type == null) type = TYPE_OVERDRAFT;
        if (read == null) read = false;
        if (resolved == null) resolved = false;
    }
}

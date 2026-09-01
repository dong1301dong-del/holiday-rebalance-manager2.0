package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 角色定义：对应表 {@code roles}。
 *
 * <p>业务含义：权限模型中的「授权主体」。用户通过 {@link UserRole} 关联角色，
 * 角色通过 {@link RoleResource} 关联资源，形成「用户 → 角色 → 资源」的 RBAC 授权链。
 *
 * <p>系统内置三个角色：ADMIN（管理员，全部权限）、CLERK（录入员，业务维护权限）、
 * EMPLOYEE（普通员工，仅查看本人数据）。内置角色（{@link #builtin} = true）不允许在页面上删除。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("角色定义表")
@Table(name = "roles")
public class Role {
    public static final String CODE_ADMIN = "ADMIN";
    public static final String CODE_CLERK = "CLERK";
    public static final String CODE_EMPLOYEE = "EMPLOYEE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    @org.hibernate.annotations.Comment("编码")
    private String code;

    @Column(nullable = false, length = 60)
    @org.hibernate.annotations.Comment("姓名")
    private String name;

    @Column(length = 200)
    @org.hibernate.annotations.Comment("描述说明")
    private String description;

    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("状态：ACTIVE 正常、FROZEN 冻结、DELETED 删除")
    private String status = "ACTIVE";

    /** 内置角色（ADMIN/CLERK/EMPLOYEE）不可删除 */
    @Column(nullable = false)
    @org.hibernate.annotations.Comment("是否内置")
    private Boolean builtin = false;

    @org.hibernate.annotations.Comment("排序号")
    private Integer sort = 0;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    @org.hibernate.annotations.Comment("更新时间")
    private LocalDateTime updatedAt;

    /**
     * 持久化前回调：自动填充创建时间与更新时间。
     *
     * <p>对 createdAt 做非空判断是兼容内置角色初始化时指定创建时间的场景。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** 更新前回调：刷新更新时间。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

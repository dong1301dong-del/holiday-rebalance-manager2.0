package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户实体：对应表 {@code users}，系统的账号主体。
 *
 * <p>业务含义：一个登录账号对应一名员工，是加班记录、调休使用记录、角色分配的归属对象。
 * 除基本档案信息外，还承载账号安全相关字段：连续登录失败次数、锁定截止时间、
 * 令牌版本号（{@link #tokenVersion}，用于单设备登录与改密后踢下线）。
 *
 * <p>删除采用逻辑删除（状态置为 {@link #STATUS_DELETED}），不物理删除，以保留历史业务数据的关联完整性。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("用户信息表")
@Table(name = "users")
public class User {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    @org.hibernate.annotations.Comment("登录账号")
    private String username;

    @Column(nullable = false)
    @org.hibernate.annotations.Comment("登录密码（加密存储）")
    private String password;

    @Column(nullable = false, length = 60)
    @org.hibernate.annotations.Comment("姓名")
    private String name;

    @Column(length = 60)
    @org.hibernate.annotations.Comment("所属部门")
    private String department;

    @Column(length = 60)
    @org.hibernate.annotations.Comment("职位")
    private String position;

    @org.hibernate.annotations.Comment("入职日期")
    private LocalDate hireDate;

    @Column(length = 120)
    @org.hibernate.annotations.Comment("邮箱")
    private String email;

    @Column(length = 40)
    @org.hibernate.annotations.Comment("手机号")
    private String phone;

    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("状态：ACTIVE 正常、FROZEN 冻结、DELETED 删除")
    private String status = STATUS_ACTIVE;

    @Column(nullable = false)
    @org.hibernate.annotations.Comment("是否必须修改初始密码")
    private Boolean mustChangePwd = false;

    @Column(nullable = false)
    @org.hibernate.annotations.Comment("是否系统内置账号（内置管理员不可删除/冻结/改密）")
    private Boolean builtin = false;

    /** 单设备登录：每次登录/重置密码自增，旧 token 因 ver 不匹配而失效 */
    @Column(nullable = false)
    @org.hibernate.annotations.Comment("Token版本号，用于单设备登录控制")
    private Long tokenVersion = 1L;

    @Column(nullable = false)
    @org.hibernate.annotations.Comment("连续登录失败次数")
    private Integer loginFailCount = 0;

    @org.hibernate.annotations.Comment("登录锁定截止时间")
    private LocalDateTime loginLockedUntil;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    @org.hibernate.annotations.Comment("更新时间")
    private LocalDateTime updatedAt;

    /**
     * 持久化前回调：自动填充创建时间与更新时间。
     *
     * <p>对 createdAt 做非空判断是为了兼容「导入历史数据时指定创建时间」的场景，
     * 传入的值会被保留而不是被覆盖。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** 更新前回调：刷新更新时间，用于数据变更留痕与增量同步判断。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

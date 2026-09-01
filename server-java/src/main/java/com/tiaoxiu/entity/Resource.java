package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统资源（菜单 / 按钮 / 接口权限）：对应表 {@code resources}。
 *
 * <p>业务含义：权限模型中的「被授权对象」，通过 {@code parentId} 组织成一棵资源树。
 * 前端根据当前用户拥有的资源动态渲染菜单；后端按 {@code code}（如 {@code overtime:add}）做接口级鉴权。
 *
 * <p>类型说明：{@code MENU} 为可见菜单项，{@code BUTTON} 为页面内操作按钮，{@code API} 为纯后端接口。
 * 注意：角色权限页等管理员页面也被定义为 {@code BUTTON} 类型，这样它们不会出现在普通用户的动态菜单里，
 * 但仍保留路由与权限编码可供访问。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("系统资源表（菜单与按钮权限）")
@Table(name = "resources")
public class Resource {
    /** 资源类型：菜单 / 按钮 / 接口 */
    public static final String TYPE_MENU = "MENU";
    public static final String TYPE_BUTTON = "BUTTON";
    public static final String TYPE_API = "API";

    /** 资源状态 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    /** 父节点 id，顶层为 null */
    @org.hibernate.annotations.Comment("父菜单ID")
    private Long parentId;

    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("类型")
    private String type = TYPE_MENU;

    /** 权限编码，全局唯一；如 overtime:add / overtime:view */
    @Column(nullable = false, unique = true, length = 80)
    @org.hibernate.annotations.Comment("编码")
    private String code;

    @Column(nullable = false, length = 60)
    @org.hibernate.annotations.Comment("姓名")
    private String name;

    /** 菜单路由 path（前端路由） */
    @Column(length = 120)
    @org.hibernate.annotations.Comment("路由路径")
    private String path;

    /** 菜单对应前端组件 */
    @Column(length = 120)
    @org.hibernate.annotations.Comment("前端组件路径")
    private String component;

    /** 菜单图标 */
    @Column(length = 60)
    @org.hibernate.annotations.Comment("菜单图标")
    private String icon;

    @org.hibernate.annotations.Comment("排序号")
    private Integer sort = 0;

    /** 接口级权限标识（与 code 通常相同，用于后端拦截） */
    @Column(length = 80)
    @org.hibernate.annotations.Comment("permission")
    private String permission;

    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("状态：ACTIVE 正常、FROZEN 冻结、DELETED 删除")
    private String status = "ACTIVE";

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    @org.hibernate.annotations.Comment("更新时间")
    private LocalDateTime updatedAt;

    /**
     * 持久化前回调：自动填充创建时间与更新时间。
     *
     * <p>对 createdAt 做非空判断是兼容数据初始化脚本指定创建时间的场景。
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

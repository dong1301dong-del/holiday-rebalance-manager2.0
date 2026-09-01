package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户与角色的绑定关系：对应表 {@code user_roles}。
 *
 * <p>业务含义：RBAC 授权链中的关联表，表示「某用户被授予某角色」。
 * 支持一个用户拥有多个角色，权限取并集。
 *
 * <p>{@code (user_id, role_id)} 上有唯一约束防止重复授权；
 * 分配角色时采用「先删该用户全部角色、再批量写入」的方式。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("用户与角色绑定关系表")
@Table(name = "user_roles", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "role_id"}))
public class UserRole {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(name = "user_id", nullable = false)
    @org.hibernate.annotations.Comment("用户ID")
    private Long userId;

    @Column(name = "role_id", nullable = false)
    @org.hibernate.annotations.Comment("角色ID")
    private Long roleId;
}

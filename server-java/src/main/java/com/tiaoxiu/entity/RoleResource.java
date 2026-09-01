package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色与资源的权限绑定关系：对应表 {@code role_resources}。
 *
 * <p>业务含义：RBAC 授权链中的关联表，表示「某角色拥有某资源（菜单 / 按钮 / 接口）」。
 * 一个用户的最终权限 = 其所有角色关联资源的并集。
 *
 * <p>{@code (role_id, resource_id)} 上有唯一约束，防止重复授权；
 * 权限变更采用「先删除该角色全部绑定、再批量重新写入」的方式，逻辑简单且避免遗漏。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("角色与资源权限绑定关系表")
@Table(name = "role_resources", uniqueConstraints = @UniqueConstraint(columnNames = {"role_id", "resource_id"}))
public class RoleResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(name = "role_id", nullable = false)
    @org.hibernate.annotations.Comment("角色ID")
    private Long roleId;

    @Column(name = "resource_id", nullable = false)
    @org.hibernate.annotations.Comment("资源ID")
    private Long resourceId;
}

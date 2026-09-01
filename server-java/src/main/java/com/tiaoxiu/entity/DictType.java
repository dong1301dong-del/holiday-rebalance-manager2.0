package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据字典类型：对应表 {@code dict_types}。
 *
 * <p>业务含义：字典的分类定义（如「请假类型」「部门」），一个类型下挂若干 {@link DictData} 选项。
 * 把可枚举的配置项收拢到字典里，避免硬编码到前后端代码中。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("数据字典类型表")
@Table(name = "dict_types")
public class DictType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    @org.hibernate.annotations.Comment("编码")
    private String code;

    @Column(nullable = false, length = 60)
    @org.hibernate.annotations.Comment("字典类型名称")
    private String name;

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
     * <p>对 createdAt 做非空判断是兼容字典初始化时指定创建时间的场景。
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

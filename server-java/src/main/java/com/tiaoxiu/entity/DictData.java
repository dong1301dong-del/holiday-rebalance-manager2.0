package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据字典项：对应表 {@code dict_data}。
 *
 * <p>业务含义：归属于某个 {@link DictType} 的具体可选项（value → label），
 * 如下拉框的每一个选项。按 {@code typeCode} 分组、按 {@code sort} 排序后供前端渲染。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("数据字典项表")
@Table(name = "dict_data")
public class DictData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(name = "type_code", nullable = false, length = 60)
    @org.hibernate.annotations.Comment("类型编码")
    private String typeCode;

    @Column(length = 60)
    @org.hibernate.annotations.Comment("配置值")
    private String value;

    @Column(length = 120)
    @org.hibernate.annotations.Comment("显示标签")
    private String label;

    @org.hibernate.annotations.Comment("排序号")
    private Integer sort = 0;

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
     * <p>对 createdAt 做非空判断是兼容字典数据初始化时指定创建时间的场景。
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

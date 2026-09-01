package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统配置键值项：对应表 {@code system_config}。
 *
 * <p>业务含义：以 key-value 形式存放可在页面上调整的系统参数，如
 * 单日加班时长封顶、上下午时段时长、工作日 / 休息日加班折算比例等。
 * 值统一以字符串存储，使用时由 {@link com.tiaoxiu.service.ConfigService} 按类型转换。
 *
 * <p>注意：{@code key} 是 MySQL 保留字，列名用反引号转义后才能正常建表。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("系统配置键值表")
@Table(name = "system_config")
public class SystemConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(name = "`key`", nullable = false, unique = true, length = 60)
    @org.hibernate.annotations.Comment("配置键")
    private String key;

    @Column(columnDefinition = "TEXT")
    @org.hibernate.annotations.Comment("配置值")
    private String value;

    @Column(length = 200)
    @org.hibernate.annotations.Comment("描述说明")
    private String description;

    @org.hibernate.annotations.Comment("更新时间")
    private LocalDateTime updatedAt;

    /**
     * 新增或更新时刷新更新时间。
     *
     * <p>同时标注 @PreUpdate 与 @PrePersist，用一个方法覆盖两种生命周期事件。
     */
    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}

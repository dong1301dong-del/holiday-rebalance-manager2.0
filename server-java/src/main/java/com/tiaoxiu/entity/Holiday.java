package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 节假日日历：一年中的每一天都有且仅有一条记录。
 * 日类型只有三种：法定节假日 / 法定工作日（含调休补班）/ 休息日。
 * 判定优先级：人工维护 &gt; 系统官方规则 &gt; 周末默认休息日 &gt; 工作日默认。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("节假日日历表")
@Table(name = "holidays")
public class Holiday {

    public static final String TYPE_LEGAL = "LEGAL";       // 法定节假日
    public static final String TYPE_WORKDAY = "WORKDAY";   // 法定工作日（含正常工作日与调休补班日）
    public static final String TYPE_RESTDAY = "RESTDAY";   // 休息日（周末且非补班）

    public static final String MAKEUP_NAME = "调休补班";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    /** 日期（唯一） */
    @Column(nullable = false, unique = true)
    @org.hibernate.annotations.Comment("日期")
    private LocalDate date;

    /** 节假日名称，如「春节」「元旦」；工作日/休息日可为空 */
    @Column(length = 60)
    @org.hibernate.annotations.Comment("节假日名称")
    private String name;

    /** 当前生效类型：LEGAL / WORKDAY / RESTDAY */
    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("类型")
    private String type = TYPE_WORKDAY;

    /**
     * 系统官方（内置规则计算）类型，用于刷新时比对是否被人工改动。
     * 首次由规则初始化时 officialType = type。
     */
    @Column(length = 20)
    @org.hibernate.annotations.Comment("官方原始类型")
    private String officialType;

    /** true=系统生成；false=人工维护（人工优先级最高） */
    @Column(nullable = false)
    @org.hibernate.annotations.Comment("是否系统自动生成")
    private Boolean auto = true;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    @org.hibernate.annotations.Comment("更新时间")
    private LocalDateTime updatedAt;

    /**
     * 构造一条节假日记录。
     *
     * @param date          日期
     * @param name          节假日名称，工作日 / 休息日可传 null
     * @param type          当前生效类型，见 TYPE_* 常量
     * @param officialType  系统按内置规则算出的原始类型，用于识别人工改动
     * @param auto          是否为系统自动生成；传 null 时按 true 处理
     */
    public Holiday(LocalDate date, String name, String type, String officialType, Boolean auto) {
        this.date = date;
        this.name = name;
        this.type = type;
        this.officialType = officialType;
        // auto 允许调用方不传，此时默认视为「系统生成」
        this.auto = auto != null ? auto : Boolean.TRUE;
    }

    /**
     * 判断该日是否被人工改动过：官方规则类型与当前生效类型不一致即视为人工干预。
     *
     * <p>officialType 为 null 表示尚未用规则初始化过，此时无从比对，保守地判定为「未改动」。
     *
     * @return true 表示人工维护覆盖了系统规则结果
     */
    public boolean isManualChanged() {
        return officialType != null && !officialType.equals(type);
    }

    /**
     * 持久化前回调：自动填充创建时间与更新时间。
     *
     * <p>对 createdAt 做非空判断是兼容节假日批量初始化时指定创建时间的场景。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** 更新前回调：刷新更新时间，人工维护或规则刷新时用于留痕。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

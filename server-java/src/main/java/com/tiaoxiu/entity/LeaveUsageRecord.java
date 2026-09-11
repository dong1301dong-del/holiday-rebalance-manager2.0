package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 调休使用记录（leave_usage_records）。
 * <p>
 * 与旧表 leave_requests 的区别：<b>没有审批流程</b>，录入即扣减调休余额。
 * 使用时长 = 结束时间 − 开始时间（小时，两位小数），起止时间以 30 分钟为一跳。
 * 作废仅置 status=VOID 并留痕，<b>不做物理删除</b>。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("调休使用记录表")
@Table(name = "leave_usage_records", indexes = {
        @Index(name = "idx_lur_user", columnList = "user_id"),
        @Index(name = "idx_lur_date", columnList = "use_date"),
        @Index(name = "idx_lur_status", columnList = "status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_lur_active", columnNames = {"active_key"})
})
public class LeaveUsageRecord {

    /** 正常（已录入并扣减余额） */
    public static final String STATUS_NORMAL = "NORMAL";
    /** 已作废（数据保留，余额已恢复） */
    public static final String STATUS_VOID = "VOID";

    /** 余额变动关联的 refType */
    public static final String REF_TYPE = "LEAVE_USAGE";
    /** 作废恢复余额的 refType */
    public static final String REF_TYPE_VOID = "LEAVE_USAGE_VOID";
    /** 编辑补差的 refType */
    public static final String REF_TYPE_ADJUST = "LEAVE_USAGE_ADJUST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(name = "user_id", nullable = false)
    @org.hibernate.annotations.Comment("用户ID")
    private Long userId;

    /** 使用日期 */
    @Column(name = "use_date", nullable = false)
    @org.hibernate.annotations.Comment("日期")
    private LocalDate date;

    /** 开始时间（30 分钟一跳） */
    @Column(name = "start_time")
    @org.hibernate.annotations.Comment("开始时间")
    private LocalTime startTime;

    /** 结束时间（30 分钟一跳） */
    @Column(name = "end_time")
    @org.hibernate.annotations.Comment("结束时间")
    private LocalTime endTime;

    /** 使用时长（小时）= 结束时间 − 开始时间，两位小数 */
    @Column(precision = 6, scale = 2)
    @org.hibernate.annotations.Comment("时长（小时）")
    private BigDecimal hours = BigDecimal.ZERO;

    @Column(length = 200)
    @org.hibernate.annotations.Comment("备注")
    private String remark;

    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("状态：NORMAL 正常、VOID 已作废")
    private String status = STATUS_NORMAL;

    /** 判重唯一键：有效记录=userId|date|startTime|endTime；作废为 NULL */
    @Column(name = "active_key", length = 120)
    @org.hibernate.annotations.Comment("判重唯一键：有效记录=userId|date|startTime|endTime；作废为 NULL")
    private String activeKey;

    @Column(name = "created_by")
    @org.hibernate.annotations.Comment("创建人ID")
    private Long createdBy;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    @org.hibernate.annotations.Comment("更新时间")
    private LocalDateTime updatedAt;

    /** 作废原因 */
    @Column(name = "void_reason", length = 200)
    @org.hibernate.annotations.Comment("作废原因")
    private String voidReason;

    /** 作废操作人 */
    @Column(name = "void_by")
    @org.hibernate.annotations.Comment("作废操作人ID")
    private Long voidBy;

    /** 作废时间 */
    @Column(name = "void_at")
    @org.hibernate.annotations.Comment("作废时间")
    private LocalDateTime voidAt;

    /**
     * 持久化前回调：填充创建/更新时间，并为 status、hours 兜底默认值。
     *
     * <p>对 createdAt 做非空判断是兼容导入历史数据；status / hours 的兜底是为了避免
     * 调用方漏传导致后续余额计算出现空指针或错误的扣减。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = STATUS_NORMAL;
        if (hours == null) hours = BigDecimal.ZERO;
        this.refreshActiveKey();
    }

    /** 更新前回调：刷新更新时间，编辑与作废时用于留痕。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
        this.refreshActiveKey();
    }

    /**
     * 持久化前计算判重唯一键：作废或关键字段缺失时置 NULL，否则拼接 userId|date|startTime|endTime。
     */
    private void refreshActiveKey() {
        if (STATUS_VOID.equals(this.status) || this.userId == null || this.date == null) {
            this.activeKey = null;
            return;
        }
        this.activeKey = this.userId + "|" + String.valueOf(this.date)
                + "|" + LeaveUsageRecord.timeKey(this.startTime) + "|" + LeaveUsageRecord.timeKey(this.endTime);
    }

    /** 时间转 HH:mm；空时间返回空串，参与判重键拼接。 */
    private static String timeKey(LocalTime time) {
        return time == null ? "" : time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}

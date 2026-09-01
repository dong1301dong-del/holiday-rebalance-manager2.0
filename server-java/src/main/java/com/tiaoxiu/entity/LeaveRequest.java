package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 调休申请实体：对应表 {@code leave_requests}。
 *
 * <p><b>已废弃：</b>本实体及其表不再参与新业务流程，仅为兼容历史数据而保留，
 * 请勿在新代码中读写。当前调休使用统一走 {@link LeaveUsageRecord}（录入即扣减，无审批流）。
 *
 * <p>历史业务含义：一次调休申请从 PENDING 经审批流转为 APPROVED / REJECTED / CANCELLED，
 * 按「开始日期+时段」到「结束日期+时段」计算申请天数与小时数。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("调休申请历史表（已废弃，保留旧数据）")
@Table(name = "leave_requests")
public class LeaveRequest {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String PERIOD_AM = "AM";
    public static final String PERIOD_PM = "PM";
    public static final String PERIOD_FULL = "FULL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    @org.hibernate.annotations.Comment("申请单号")
    private String requestNo;

    @Column(name = "user_id", nullable = false)
    @org.hibernate.annotations.Comment("申请人ID")
    private Long userId;

    @Column(nullable = false)
    @org.hibernate.annotations.Comment("开始日期")
    private LocalDate startDate;

    @Column(nullable = false, length = 10)
    @org.hibernate.annotations.Comment("开始时段：AM 上午、PM 下午、FULL 全天")
    private String startPeriod;

    @Column(nullable = false)
    @org.hibernate.annotations.Comment("结束日期")
    private LocalDate endDate;

    @Column(nullable = false, length = 10)
    @org.hibernate.annotations.Comment("结束时段：AM 上午、PM 下午、FULL 全天")
    private String endPeriod;

    @Column(precision = 6, scale = 2)
    @org.hibernate.annotations.Comment("申请天数")
    private BigDecimal days = BigDecimal.ZERO;

    @Column(precision = 6, scale = 2)
    @org.hibernate.annotations.Comment("申请时长（小时）")
    private BigDecimal hours = BigDecimal.ZERO;

    @Column(length = 200)
    @org.hibernate.annotations.Comment("申请原因")
    private String reason;

    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("状态：PENDING 待审批、APPROVED 已通过、REJECTED 已拒绝、CANCELLED 已取消")
    private String status = STATUS_PENDING;

    @Column(name = "approver_id")
    @org.hibernate.annotations.Comment("审批人ID")
    private Long approverId;

    @Column(length = 200)
    @org.hibernate.annotations.Comment("审批意见")
    private String approveComment;

    @org.hibernate.annotations.Comment("审批时间")
    private LocalDateTime approveAt;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    @org.hibernate.annotations.Comment("更新时间")
    private LocalDateTime updatedAt;

    /**
     * 持久化前回调：自动填充创建时间与更新时间。
     *
     * <p>对 createdAt 做非空判断是为了兼容历史数据导入时自带创建时间的场景。
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

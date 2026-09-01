package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调休余额变动流水：对应表 {@code balance_logs}。
 *
 * <p>业务含义：以「记账流水」而非「单一余额字段」的方式管理调休余额。
 * 每一笔加班折算收入、调休支出、作废恢复、初始导入都会追加一条流水，
 * 当前余额 = 该用户所有流水 hours 的累加和，保证余额可追溯、可对账。
 *
 * <p>{@link #balance} 是该笔流水记账后的实时快照，方便直接查询某一时点的余额；
 * 允许为负表示透支（员工用了尚未赚到的调休）。
 */
@Data
@NoArgsConstructor
@Entity
@org.hibernate.annotations.Comment("调休余额变动流水表")
@Table(name = "balance_logs")
public class BalanceLog {
    /** 类型：折调休收入 / 其他调休 / 初始化 / 调休支出 */
    public static final String TYPE_EARN = "EARN";
    public static final String TYPE_ADJUST = "ADJUST";
    public static final String TYPE_INIT = "INIT";
    public static final String TYPE_SPEND = "SPEND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @org.hibernate.annotations.Comment("主键ID")
    private Long id;

    @Column(name = "user_id", nullable = false)
    @org.hibernate.annotations.Comment("用户ID")
    private Long userId;

    @Column(nullable = false, length = 20)
    @org.hibernate.annotations.Comment("类型")
    private String type;

    @Column(nullable = false, precision = 6, scale = 2)
    @org.hibernate.annotations.Comment("时长（小时）")
    private BigDecimal hours = BigDecimal.ZERO;

    @Column(length = 30)
    @org.hibernate.annotations.Comment("余额变动关联类型")
    private String refType;

    @org.hibernate.annotations.Comment("余额变动关联记录ID")
    private Long refId;

    /** 记账后实时余额（允许为负，透支） */
    @Column(nullable = false, precision = 8, scale = 2)
    @org.hibernate.annotations.Comment("记账后实时余额（小时），允许为负表示透支")
    private BigDecimal balance = BigDecimal.ZERO;

    /** 是否为透支（余额<0） */
    @Column(nullable = false)
    @org.hibernate.annotations.Comment("是否透支：true 表示余额<0")
    private Boolean overdraft = false;

    @Column(length = 200)
    @org.hibernate.annotations.Comment("变动备注")
    private String note;

    @Column(updatable = false)
    @org.hibernate.annotations.Comment("创建时间")
    private LocalDateTime createdAt;

    /**
     * 持久化前回调：填充记账时间。
     *
     * <p>流水表只允许新增、不允许修改，因此这里没有 @PreUpdate；
     * 纠错必须靠追加一笔反向流水实现，以便完整保留审计轨迹。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 加班转调休录入记录。
 * <p>
 * 加班时长 = 结束时间 − 开始时间（小时，两位小数），不做时段扣除、不封顶。
 * 转调休时长 = 加班时长 × 转调休系数（系数由节假日日历自动判定）。
 * <p>
 * 录入模式：
 * <ul>
 *   <li>{@link #MODE_OVERTIME} 加班转休：按开始/结束时间算加班时长，再乘系数得转休时长；</li>
 *   <li>{@link #MODE_MANUAL} 其他转休：不填起止时间，直接填写转休时长（如折算补休、历史结转等）。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@Entity
@Comment("加班转调休录入记录表")
@Table(name = "overtime_records")
public class OvertimeRecord {

    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 作废：数据保留，余额已冲减 */
    public static final String STATUS_VOID = "VOID";

    /** 法定工作日 → 系数 0.5 */
    public static final String DAY_WORKDAY = "WORKDAY";
    /** 休息日 → 系数 1 */
    public static final String DAY_RESTDAY = "RESTDAY";
    /** 法定节假日 → 系数 1 */
    public static final String DAY_LEGAL = "LEGAL";

    /** 录入模式：加班转休（按起止时间自动计算加班时长与转休时长） */
    public static final String MODE_OVERTIME = "OVERTIME";
    /** 录入模式：其他转休（直接填写转休时长，不计算加班时长与系数） */
    public static final String MODE_MANUAL = "MANUAL";

    /** 录入来源：手工录入 */
    public static final String SOURCE_MANUAL = "manual";
    /** 录入来源：Excel 导入 */
    public static final String SOURCE_IMPORT = "import";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键 ID")
    private Long id;

    @Comment("员工 ID，关联 user.id")
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Comment("加班/转休归属日期")
    @Column(nullable = false)
    private LocalDate date;

    @Comment("录入模式：OVERTIME 加班转休；MANUAL 其他转休")
    @Column(name = "record_mode", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'OVERTIME'")
    private String recordMode = MODE_OVERTIME;

    @Comment("日类型（WORKDAY / RESTDAY / LEGAL），由节假日日历自动判定")
    @Column(name = "day_type", nullable = false, length = 20)
    private String dayType;

    @Comment("加班开始时间（30 分钟一跳，分钟为 00 或 30）；其他转休为空")
    @Column(name = "start_time")
    private LocalTime startTime;

    @Comment("加班结束时间（30 分钟一跳，分钟为 00 或 30）；其他转休为空")
    @Column(name = "end_time")
    private LocalTime endTime;

    @Comment("当日打卡时间（可选，仅供核对加班事实）")
    @Column(name = "clock_in_time")
    private LocalTime clockInTime;

    @Comment("加班时长（小时）= 结束时间 − 开始时间，两位小数；其他转休固定为 0")
    @Column(name = "hours", precision = 6, scale = 2)
    private BigDecimal hours = BigDecimal.ZERO;

    @Comment("转调休系数（法定工作日 0.5；休息日、法定节假日 1）；其他转休为空")
    @Column(precision = 4, scale = 2)
    private BigDecimal ratio = BigDecimal.ONE;

    @Comment("转调休时长（小时）：加班转休 = 加班时长 × 系数；其他转休 = 手工填写值")
    @Column(name = "converted_hours", precision = 6, scale = 2)
    private BigDecimal convertedHours = BigDecimal.ZERO;

    @Comment("命中节假日名称")
    @Column(length = 60)
    private String holidayName;

    @Comment("备注")
    @Column(length = 200)
    private String remark;

    @Comment("录入来源：manual 手工录入；import Excel 导入")
    @Column(length = 20)
    private String source = SOURCE_MANUAL;

    @Comment("状态：CONFIRMED 已确认；VOID 已作废")
    @Column(nullable = false, length = 20)
    private String status = STATUS_CONFIRMED;

    @Comment("创建人 ID")
    @Column(name = "created_by")
    private Long createdBy;

    @Comment("创建时间")
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Comment("更新时间")
    private LocalDateTime updatedAt;

    /**
     * 持久化前回调：自动填充创建时间与更新时间。
     *
     * <p>对 createdAt 做非空判断是为了兼容 Excel 导入时沿用文件里的录入时间。
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** 更新前回调：刷新更新时间，编辑加班记录时用于留痕。 */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

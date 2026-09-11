package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.entity.LeaveUsageRecord;
import com.tiaoxiu.entity.OvertimeRecord;
import com.tiaoxiu.repository.LeaveUsageRepository;
import com.tiaoxiu.repository.OvertimeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 加班 / 调休记录的时段冲突判定。
 *
 * <p>用于阻止同一员工在同一天录入互相重叠的加班转休或其它转休记录，
 * 以及同一天重复录入「其他转休」记录。判断基于排除作废后的有效记录。
 */
@Service
public class RecordConflictGuard {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final OvertimeRepository overtimeRepository;
    private final LeaveUsageRepository leaveUsageRepository;

    /**
     * 构造器注入。
     *
     * @param overtimeRepository 加班转调休记录仓储
     * @param leaveUsageRepository 调休使用记录仓储
     */
    public RecordConflictGuard(OvertimeRepository overtimeRepository, LeaveUsageRepository leaveUsageRepository) {
        this.overtimeRepository = overtimeRepository;
        this.leaveUsageRepository = leaveUsageRepository;
    }

    /**
     * 断言指定时段在当天没有与其它有效记录重叠。
     *
     * <p>任一关键参数为空时直接放行（交由上层校验）。
     * excludeOvertimeId / excludeLeaveId 用于编辑场景排除记录自身。
     *
     * @param userId            员工 ID
     * @param date              日期
     * @param start             起始时间
     * @param end               结束时间
     * @param excludeOvertimeId 排除的加班记录 ID（编辑自身时传入），可空
     * @param excludeLeaveId    排除的调休记录 ID（编辑自身时传入），可空
     * @throws BizException 存在重叠记录时抛出
     */
    public void assertIntervalFree(Long userId, LocalDate date, LocalTime start, LocalTime end,
                                   Long excludeOvertimeId, Long excludeLeaveId) {
        if (userId == null || date == null || start == null || end == null) {
            return;
        }
        List<String> conflicts = new ArrayList<>();
        for (OvertimeRecord r : overtimeRepository.findEffectiveByUserIdAndDate(userId, date)) {
            if (!"OVERTIME".equals(r.getRecordMode())
                    || excludeOvertimeId != null && excludeOvertimeId.equals(r.getId())
                    || !overlaps(start, end, r.getStartTime(), r.getEndTime())) {
                continue;
            }
            conflicts.add("加班转休 " + range(r.getStartTime(), r.getEndTime()));
        }
        for (LeaveUsageRecord r : leaveUsageRepository.findEffectiveByUserIdAndDate(userId, date)) {
            if (excludeLeaveId != null && excludeLeaveId.equals(r.getId())
                    || !overlaps(start, end, r.getStartTime(), r.getEndTime())) {
                continue;
            }
            conflicts.add("调休使用 " + range(r.getStartTime(), r.getEndTime()));
        }
        if (!conflicts.isEmpty()) {
            throw new BizException("该员工在 " + date + " 已存在时间重叠的记录（"
                    + String.join("、", conflicts)
                    + "）。该数据可能已存在，也可能由其他人刚刚提交，请刷新后重试");
        }
    }

    /**
     * 断言当天没有已存在的「其他转休」记录（同一天不允许重复录入）。
     *
     * @param userId   员工 ID
     * @param date     日期
     * @param excludeId 排除的记录 ID（编辑自身时传入），可空
     * @throws BizException 已存在「其他转休」记录时抛出
     */
    public void assertManualOnce(Long userId, LocalDate date, Long excludeId) {
        if (userId == null || date == null) {
            return;
        }
        for (OvertimeRecord r : overtimeRepository.findEffectiveByUserIdAndDate(userId, date)) {
            if (!"MANUAL".equals(r.getRecordMode()) || excludeId != null && excludeId.equals(r.getId())) {
                continue;
            }
            throw new BizException("该员工在 " + date + " 已存在一条「其他转休」记录，同一天不能重复录入。"
                    + "该数据可能已存在，也可能由其他人刚刚提交，请刷新后重试");
        }
    }

    /**
     * 判断两个时段是否半开区间重叠：A.start &lt; B.end 且 B.start &lt; A.end。
     *
     * @param aStart 时段 A 起始
     * @param aEnd   时段 A 结束
     * @param bStart 时段 B 起始
     * @param bEnd   时段 B 结束
     * @return 任一时间为空返回 false；重叠返回 true
     */
    public static boolean overlaps(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /** 把起止时间格式化为 HH:mm 区间文本，空时间用 --:-- 占位。 */
    private static String range(LocalTime start, LocalTime end) {
        return (start == null ? "--:--" : start.format(TIME_FMT))
                + "–"
                + (end == null ? "--:--" : end.format(TIME_FMT));
    }
}

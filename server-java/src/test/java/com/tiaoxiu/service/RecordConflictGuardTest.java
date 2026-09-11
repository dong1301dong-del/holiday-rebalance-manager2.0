package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.entity.LeaveUsageRecord;
import com.tiaoxiu.entity.OvertimeRecord;
import com.tiaoxiu.repository.LeaveUsageRepository;
import com.tiaoxiu.repository.OvertimeRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * RecordConflictGuard 单元测试。
 *
 * <p>验证判重守卫：只按「员工 + 日期」取数；与已有记录按半开区间
 * {@code s1 < e2 && s2 < e1} 比对（端点相接不算重叠）；其他转休（无起止时间）不参与
 * 区间判定；跨模块（加班转休 ↔ 调休使用）互相冲突；编辑自身记录时被排除。
 * 覆盖静态方法 {@code overlaps()} 的重叠/不重叠/缺省时间三类情形。
 */
class RecordConflictGuardTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 11);
    private static final Long USER = 7L;

    private final OvertimeRepository overtimeRepository = Mockito.mock(OvertimeRepository.class);
    private final LeaveUsageRepository leaveUsageRepository = Mockito.mock(LeaveUsageRepository.class);
    private final RecordConflictGuard guard = new RecordConflictGuard(overtimeRepository, leaveUsageRepository);

    private static OvertimeRecord existingOvertime(long id, LocalTime start, LocalTime end) {
        OvertimeRecord r = new OvertimeRecord();
        r.setId(Long.valueOf(id));
        r.setUserId(USER);
        r.setDate(DAY);
        r.setRecordMode("OVERTIME");
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus("CONFIRMED");
        return r;
    }

    private static OvertimeRecord existingManual(long id) {
        OvertimeRecord r = new OvertimeRecord();
        r.setId(Long.valueOf(id));
        r.setUserId(USER);
        r.setDate(DAY);
        r.setRecordMode("MANUAL");
        r.setStartTime(null);
        r.setEndTime(null);
        r.setStatus("CONFIRMED");
        return r;
    }

    private static LeaveUsageRecord existingLeave(long id, LocalTime start, LocalTime end) {
        LeaveUsageRecord r = new LeaveUsageRecord();
        r.setId(Long.valueOf(id));
        r.setUserId(USER);
        r.setDate(DAY);
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus("NORMAL");
        return r;
    }

    private void stub(List<OvertimeRecord> overtime, List<LeaveUsageRecord> leave) {
        Mockito.when(overtimeRepository.findEffectiveByUserIdAndDate(ArgumentMatchers.eq(USER), ArgumentMatchers.eq(DAY)))
                .thenReturn(overtime);
        Mockito.when(leaveUsageRepository.findEffectiveByUserIdAndDate(ArgumentMatchers.eq(USER), ArgumentMatchers.eq(DAY)))
                .thenReturn(leave);
    }

    @Test
    @DisplayName("判重只按「被录入的员工 + 日期」取数，不同员工/不同日期不受影响")
    void scopeIsUserAndDate() {
        stub(Collections.emptyList(), Collections.emptyList());
        guard.assertIntervalFree(USER, DAY, LocalTime.of(15, 0), LocalTime.of(16, 0), null, null);
        Mockito.verify(overtimeRepository).findEffectiveByUserIdAndDate(ArgumentMatchers.eq(USER), ArgumentMatchers.eq(DAY));
        Mockito.verify(leaveUsageRepository).findEffectiveByUserIdAndDate(ArgumentMatchers.eq(USER), ArgumentMatchers.eq(DAY));
        Mockito.verifyNoMoreInteractions(overtimeRepository, leaveUsageRepository);
    }

    @Test
    @DisplayName("空 userId / date 时不做任何查询")
    void blankArgsShortCircuit() {
        Assertions.assertDoesNotThrow(() -> guard.assertIntervalFree(null, null, LocalTime.of(15, 0), LocalTime.of(16, 0), null, null));
        Mockito.verifyNoInteractions(overtimeRepository, leaveUsageRepository);
    }

    @Nested
    @DisplayName("assertManualOnce：其他转休同人同日仅一条")
    class ManualOnce {

        @Test
        @DisplayName("已有其他转休，再录一条 → 拒绝")
        void rejectSecondManual() {
            stub(List.of(existingManual(1L)), Collections.emptyList());
            Assertions.assertThrows(BizException.class, () -> guard.assertManualOnce(USER, DAY, null));
        }

        @Test
        @DisplayName("编辑自身那条其他转休 → 放行")
        void allowSelfEdit() {
            stub(List.of(existingManual(1L)), Collections.emptyList());
            Assertions.assertDoesNotThrow(() -> guard.assertManualOnce(USER, DAY, Long.valueOf(1L)));
        }

        @Test
        @DisplayName("当天只有加班转休、没有其他转休 → 放行")
        void allowWhenNoManual() {
            stub(List.of(existingOvertime(1L, LocalTime.of(15, 0), LocalTime.of(17, 0))), Collections.emptyList());
            Assertions.assertDoesNotThrow(() -> guard.assertManualOnce(USER, DAY, null));
        }
    }

    @Nested
    @DisplayName("assertIntervalFree：与已有记录比对")
    class IntervalAgainstStore {

        @Test
        @DisplayName("同一天已有 15:00—17:00 加班转休，再录 15:00—16:00 → 拒绝")
        void rejectContained() {
            stub(List.of(existingOvertime(1L, LocalTime.of(15, 0), LocalTime.of(17, 0))), Collections.emptyList());
            BizException ex = Assertions.assertThrows(BizException.class,
                    () -> guard.assertIntervalFree(USER, DAY, LocalTime.of(15, 0), LocalTime.of(16, 0), null, null));
            Assertions.assertTrue(ex.getMessage().contains("请刷新后重试"),
                    "提示应引导用户刷新重试：" + ex.getMessage());
        }

        @Test
        @DisplayName("同一天已有 15:00—17:00 加班转休，再录 14:00—16:00（交叉）→ 拒绝")
        void rejectCrossing() {
            stub(List.of(existingOvertime(1L, LocalTime.of(15, 0), LocalTime.of(17, 0))), Collections.emptyList());
            Assertions.assertThrows(BizException.class,
                    () -> guard.assertIntervalFree(USER, DAY, LocalTime.of(14, 0), LocalTime.of(16, 0), null, null));
        }

        @Test
        @DisplayName("同一天已有 15:00—17:00 加班转休，再录 14:00—18:00（包含）→ 拒绝")
        void rejectContaining() {
            stub(List.of(existingOvertime(1L, LocalTime.of(15, 0), LocalTime.of(17, 0))), Collections.emptyList());
            Assertions.assertThrows(BizException.class,
                    () -> guard.assertIntervalFree(USER, DAY, LocalTime.of(14, 0), LocalTime.of(18, 0), null, null));
        }

        @Test
        @DisplayName("E3：已有 15:00—17:00，再录 17:00—19:00（端点相接）→ 放行")
        void allowTouching() {
            stub(List.of(existingOvertime(1L, LocalTime.of(15, 0), LocalTime.of(17, 0))), Collections.emptyList());
            Assertions.assertDoesNotThrow(
                    () -> guard.assertIntervalFree(USER, DAY, LocalTime.of(17, 0), LocalTime.of(19, 0), null, null));
        }

        @Test
        @DisplayName("E2：跨模块 —— 已有「调休使用」15:00—17:00，再录「加班转休」15:00—16:00 → 拒绝")
        void rejectCrossModule() {
            stub(Collections.emptyList(), List.of(existingLeave(9L, LocalTime.of(15, 0), LocalTime.of(17, 0))));
            BizException ex = Assertions.assertThrows(BizException.class,
                    () -> guard.assertIntervalFree(USER, DAY, LocalTime.of(15, 0), LocalTime.of(16, 0), null, null));
            Assertions.assertTrue(ex.getMessage().contains("调休使用"),
                    "提示应指出冲突来自调休使用：" + ex.getMessage());
        }

        @Test
        @DisplayName("自身记录被排除：编辑 15:00—17:00 这条记录本身时不应自我冲突")
        void excludeSelf() {
            stub(List.of(existingOvertime(1L, LocalTime.of(15, 0), LocalTime.of(17, 0))), Collections.emptyList());
            Assertions.assertDoesNotThrow(
                    () -> guard.assertIntervalFree(USER, DAY, LocalTime.of(15, 0), LocalTime.of(17, 0), Long.valueOf(1L), null));
        }

        @Test
        @DisplayName("E1①：其他转休没有起止时间，不参与区间判定")
        void manualIgnoredByIntervalCheck() {
            stub(List.of(existingManual(1L)), Collections.emptyList());
            Assertions.assertDoesNotThrow(
                    () -> guard.assertIntervalFree(USER, DAY, LocalTime.of(15, 0), LocalTime.of(16, 0), null, null));
        }

        @Test
        @DisplayName("起止时间为空（其他转休录入）时直接放行，不查库")
        void nullTimesShortCircuit() {
            Assertions.assertDoesNotThrow(
                    () -> guard.assertIntervalFree(USER, DAY, null, null, null, null));
        }
    }

    @Nested
    @DisplayName("半开区间判定式 overlaps()")
    class OverlapFormula {

        @Test
        @DisplayName("被包含 / 交叉 / 包含 / 完全相同 —— 四种都判为重叠")
        void overlappingCases() {
            LocalTime s = LocalTime.of(15, 0);
            LocalTime e = LocalTime.of(17, 0);
            Assertions.assertAll(new Executable[]{
                    () -> Assertions.assertTrue(RecordConflictGuard.overlaps(s, e, LocalTime.of(15, 0), LocalTime.of(16, 0)), "新记录被已有包含"),
                    () -> Assertions.assertTrue(RecordConflictGuard.overlaps(s, e, LocalTime.of(14, 0), LocalTime.of(16, 0)), "新记录与已有交叉"),
                    () -> Assertions.assertTrue(RecordConflictGuard.overlaps(s, e, LocalTime.of(14, 0), LocalTime.of(18, 0)), "新记录包含已有"),
                    () -> Assertions.assertTrue(RecordConflictGuard.overlaps(s, e, LocalTime.of(15, 0), LocalTime.of(17, 0)), "完全相同")
            });
        }

        @Test
        @DisplayName("E3：端点相接不算重叠，完全错开也不算")
        void nonOverlappingCases() {
            LocalTime s = LocalTime.of(15, 0);
            LocalTime e = LocalTime.of(17, 0);
            Assertions.assertAll(new Executable[]{
                    () -> Assertions.assertFalse(RecordConflictGuard.overlaps(s, e, LocalTime.of(17, 0), LocalTime.of(19, 0)), "后端点相接"),
                    () -> Assertions.assertFalse(RecordConflictGuard.overlaps(s, e, LocalTime.of(13, 0), LocalTime.of(15, 0)), "前端点相接"),
                    () -> Assertions.assertFalse(RecordConflictGuard.overlaps(s, e, LocalTime.of(19, 0), LocalTime.of(20, 0)), "完全错开")
            });
        }

        @Test
        @DisplayName("任一时间缺失视为无区间，不判重叠（其他转休场景）")
        void nullTimesNeverOverlap() {
            Assertions.assertAll(new Executable[]{
                    () -> Assertions.assertFalse(RecordConflictGuard.overlaps(null, LocalTime.of(17, 0), LocalTime.of(15, 0), LocalTime.of(16, 0))),
                    () -> Assertions.assertFalse(RecordConflictGuard.overlaps(LocalTime.of(15, 0), null, LocalTime.of(15, 0), LocalTime.of(16, 0))),
                    () -> Assertions.assertFalse(RecordConflictGuard.overlaps(LocalTime.of(15, 0), LocalTime.of(17, 0), null, null))
            });
        }
    }
}

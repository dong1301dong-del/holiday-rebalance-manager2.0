package com.tiaoxiu.entity;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ActiveKey 生成逻辑单元测试。
 *
 * <p>验证 OvertimeRecord / LeaveUsageRecord 在 {@code @PrePersist} / {@code @PreUpdate}
 * 回调里依据「同人同日」维度生成 {@code activeKey}（唯一索引键）；状态为 VOID 时键置
 * null 以放行同日重录；编辑后键随内容（起止时间）变化而刷新。
 */
class ActiveKeyTest {

    private static OvertimeRecord overtime(long userId, LocalDate date, String mode, LocalTime start, LocalTime end, String status) {
        OvertimeRecord r = new OvertimeRecord();
        r.setUserId(Long.valueOf(userId));
        r.setDate(date);
        r.setRecordMode(mode);
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(status);
        return r;
    }

    private static LeaveUsageRecord leave(long userId, LocalDate date, LocalTime start, LocalTime end, String status) {
        LeaveUsageRecord r = new LeaveUsageRecord();
        r.setUserId(Long.valueOf(userId));
        r.setDate(date);
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("加班转调休：userId|date|OVERTIME|HH:mm|HH:mm")
    void overtimeActiveKey() {
        OvertimeRecord r = overtime(7L, LocalDate.of(2026, 1, 21), "OVERTIME", LocalTime.of(18, 30), LocalTime.of(21, 30), "CONFIRMED");
        r.prePersist();
        Assertions.assertEquals("7|2026-01-21|OVERTIME|18:30|21:30", r.getActiveKey());
    }

    @Test
    @DisplayName("其他转休：无起止时间，两段时间为空串 → 等价于「同人同日仅一条」")
    void manualActiveKey() {
        OvertimeRecord r = overtime(13L, LocalDate.of(2026, 1, 1), "MANUAL", null, null, "CONFIRMED");
        r.prePersist();
        Assertions.assertEquals("13|2026-01-01|MANUAL||", r.getActiveKey());
    }

    @Test
    @DisplayName("加班记录作废后键置 null，唯一索引放行，允许同日重新录入")
    void overtimeVoidKeyIsNull() {
        OvertimeRecord r = overtime(7L, LocalDate.of(2026, 1, 21), "OVERTIME", LocalTime.of(18, 30), LocalTime.of(21, 30), "VOID");
        r.prePersist();
        Assertions.assertNull(r.getActiveKey());
    }

    @Test
    @DisplayName("调休使用：userId|date|HH:mm|HH:mm")
    void leaveActiveKey() {
        LeaveUsageRecord r = leave(23L, LocalDate.of(2026, 5, 11), LocalTime.of(14, 0), LocalTime.of(18, 0), "NORMAL");
        r.prePersist();
        Assertions.assertEquals("23|2026-05-11|14:00|18:00", r.getActiveKey());
    }

    @Test
    @DisplayName("调休使用作废后键置 null")
    void leaveVoidKeyIsNull() {
        LeaveUsageRecord r = leave(23L, LocalDate.of(2026, 5, 11), LocalTime.of(14, 0), LocalTime.of(18, 0), "VOID");
        r.prePersist();
        Assertions.assertNull(r.getActiveKey());
    }

    @Test
    @DisplayName("编辑后重新生成的键随内容变化（跨天/改时间不再占用旧键）")
    void keyRefreshedOnUpdate() {
        OvertimeRecord r = overtime(7L, LocalDate.of(2026, 1, 21), "OVERTIME", LocalTime.of(18, 30), LocalTime.of(21, 30), "CONFIRMED");
        r.prePersist();
        String first = r.getActiveKey();
        r.setEndTime(LocalTime.of(22, 0));
        r.preUpdate();
        Assertions.assertEquals("7|2026-01-21|OVERTIME|18:30|22:00", r.getActiveKey());
        Assertions.assertNotEquals(first, r.getActiveKey(), "内容变更后判重键应随之变化");
    }
}

package com.tiaoxiu.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 节假日日历相关出入参。 */
public class HolidayDto {

    /** 月份概览（年份卡片） */
    @Data
    public static class MonthOverview {
        /** 如 2026-01 */
        private String month;
        /** 如 2026年01月 */
        private String label;
        /** 法定工作日天数 */
        private int workdayCount;
        /** 休息日天数 */
        private int restCount;
        /** 人工变更天数 */
        private int manualCount;
    }

    /** 日历网格中的一天 */
    @Data
    public static class DayCell {
        /** yyyy-MM-dd */
        private String date;
        /** 几号 */
        private int day;
        /** 当前生效类型 LEGAL / WORKDAY / RESTDAY */
        private String type;
        /** 类型中文名 */
        private String typeLabel;
        /** 节假日名称（可空） */
        private String name;
        /** 是否属于当前月（补位日期为 false，不可编辑） */
        private boolean inMonth;
        /** 是否人工变更 */
        private boolean manual;
        /** 系统官方类型 */
        private String officialType;
    }

    /** 月份日历网格 */
    @Data
    public static class MonthGrid {
        private String month;
        private String label;
        private List<DayCell> days = new ArrayList<>();
    }

    /** 单条日期类型变更（前端按天收集改动后批量提交，只传发生变化的日期） */
    @Data
    public static class ChangeItem {
        /** 目标日期，格式 yyyy-MM-dd */
        private String date;
        /** 变更后的类型：LEGAL / WORKDAY / RESTDAY */
        private String type;
    }

    /** 保存某月变更的请求 */
    @Data
    public static class SaveMonthRequest {
        /** 如 2026-01 */
        private String month;
        private List<ChangeItem> changes = new ArrayList<>();
    }

    /** 保存某月变更的返回 */
    @Data
    public static class SaveResult {
        private int saved;
        private List<String> warnings = new ArrayList<>();
    }

    /** 刷新请求 */
    @Data
    public static class RefreshRequest {
        /** year | month */
        private String scope;
        /** 年份 2026 或月份 2026-01 */
        private String value;
        /** 是否强制覆盖人工变更 */
        private Boolean force;
    }

    /** 刷新冲突（存在人工变更的日期） */
    @Data
    public static class Conflict {
        private String date;
        private String currentType;
        private String currentTypeLabel;
        private String officialType;
        private String officialTypeLabel;
        private String name;
    }

    /**
     * 刷新结果。
     *
     * <p>{@code refreshed=false} 表示检测到人工变更、未真正写入，此时 {@code conflicts} 非空，
     * 前端应弹出确认框，用户确认后再带 force=true 重新提交。
     */
    @Data
    public static class RefreshResult {
        /** 是否已真正刷新写库 */
        private boolean refreshed;
        /** 与官方规则不一致（即人工变更过）的日期列表 */
        private List<Conflict> conflicts = new ArrayList<>();
        /** 结果提示文案 */
        private String message;
    }

    /** 日期解析结果（加班录入表单用于自动带出类型与系数） */
    @Data
    public static class ResolveResult {
        private String date;
        private String type;
        private String typeLabel;
        /** 折算系数 */
        private BigDecimal ratio;
        private String name;
        private boolean manual;
        /** 与 type 同义，兼容加班录入表单的字段命名 */
        private String dayType;
        /** 与 name 同义，兼容加班录入表单的字段命名 */
        private String holidayName;
    }

    /** 单条维护（兼容旧调用） */
    @Data
    public static class UpsertRequest {
        private String date;
        private String name;
        private String type;
        private Boolean auto;
    }
}

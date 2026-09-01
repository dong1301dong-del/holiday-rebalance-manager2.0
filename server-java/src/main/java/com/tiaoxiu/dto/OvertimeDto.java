package com.tiaoxiu.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 加班转调休录入相关的出入参定义。
 *
 * <p>支持两种录入模式：{@code OVERTIME}（按起止时间算加班时长，再乘系数折算出转休时长）
 * 与 {@code MANUAL}（其他转休，直接填写转休时长）。
 */
public class OvertimeDto {

    /** 单条录入行（批量录入 / 编辑 / 导入共用） */
    @Data
    public static class RecordInput {
        /** 员工 ID */
        private Long userId;
        /** 加班 / 转休归属日期 */
        private LocalDate date;
        /** 录入模式：OVERTIME 加班转休 / MANUAL 其他转休，缺省 OVERTIME */
        private String mode = com.tiaoxiu.entity.OvertimeRecord.MODE_OVERTIME;
        private LocalTime startTime;
        private LocalTime endTime;
        /** 打卡时间，可为空 */
        private LocalTime clockInTime;
        /** 转休时长：其他转休模式下直接填写；加班转休模式下由服务端计算 */
        private BigDecimal convertedHours;
        private String remark;
        /** manual / import，由服务端覆盖，不信任前端传入值 */
        private String source;

        /** 无参构造，供 JSON 反序列化使用 */
        public RecordInput() {
        }

        /**
         * 便捷构造：加班转休模式的简化入参。
         *
         * @param userId    员工 ID
         * @param date      归属日期
         * @param startTime 加班开始时间
         * @param endTime   加班结束时间
         * @param remark    备注
         */
        public RecordInput(Long userId, LocalDate date, LocalTime startTime, LocalTime endTime, String remark) {
            this.userId = userId;
            this.date = date;
            this.startTime = startTime;
            this.endTime = endTime;
            this.remark = remark;
        }

        /**
         * 全参构造：覆盖两种录入模式的全部字段（Excel 导入时使用）。
         *
         * @param userId          员工 ID
         * @param date            归属日期
         * @param mode            录入模式
         * @param startTime       加班开始时间，其他转休模式传 null
         * @param endTime         加班结束时间，其他转休模式传 null
         * @param clockInTime     当日打卡时间，可为空
         * @param convertedHours  转休时长，其他转休模式直接取值；加班转休模式由服务端重算
         * @param remark          备注
         */
        public RecordInput(Long userId, LocalDate date, String mode, LocalTime startTime, LocalTime endTime,
                           LocalTime clockInTime, BigDecimal convertedHours, String remark) {
            this.userId = userId;
            this.date = date;
            this.mode = mode;
            this.startTime = startTime;
            this.endTime = endTime;
            this.clockInTime = clockInTime;
            this.convertedHours = convertedHours;
            this.remark = remark;
        }
    }

    /** 批量录入请求：一次提交多行（同一人可录多条） */
    @Data
    public static class BatchRequest {
        /** 待录入的记录行列表，整体在一个事务内写入 */
        private List<RecordInput> records = new ArrayList<>();
    }

    /** 单条编辑请求 */
    @Data
    public static class UpdateRequest {
        /** 员工 ID */
        private Long userId;
        /** 归属日期 */
        private LocalDate date;
        /** 录入模式：OVERTIME 加班转休 / MANUAL 其他转休；不传则沿用原记录的模式 */
        private String mode;
        private LocalTime startTime;
        private LocalTime endTime;
        /** 打卡时间，可为空 */
        private LocalTime clockInTime;
        /** 转休时长：其他转休模式下直接填写；加班转休模式下由服务端计算 */
        private BigDecimal convertedHours;
        private String remark;
    }

    /** 列表查询条件 */
    @Data
    public static class QueryRequest {
        /** 加班日期起（含） */
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private LocalDate from;
        /** 加班日期止（含） */
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private LocalDate to;
        /** 员工姓名模糊 */
        private String name;
        /** 部门 */
        private String department;
        private Long userId;
        private int page = 1;
        private int size = 20;

        public boolean isEmpty() {
            return from == null && to == null
                    && (name == null || name.isBlank())
                    && (department == null || department.isBlank())
                    && userId == null;
        }
    }

    /** 列表 / 导出返回结构 */
    @Data
    public static class OvertimeResponse {
        private Long id;
        private LocalDate date;
        private Long userId;
        private String userName;
        private String department;
        private String dayType;
        private String dayTypeLabel;
        /** 录入模式：OVERTIME / MANUAL */
        private String mode;
        /** 录入模式中文名：加班转调休 / 其他转调休 */
        private String modeLabel;
        private LocalTime startTime;
        private LocalTime endTime;
        /** 打卡时间 */
        private LocalTime clockInTime;
        /** 加班时长（小时，两位小数） */
        private BigDecimal hours;
        /** 转调休系数 */
        private BigDecimal ratio;
        /** 转调休时长（小时，两位小数）= 加班时长 × 系数 */
        private BigDecimal convertedHours;
        /** 命中的节假日名称，非节假日为空 */
        private String holidayName;
        /** 备注 */
        private String remark;
        /** 状态：CONFIRMED 已确认 / VOID 已作废 */
        private String status;
    }

    /** 日期解析结果：日类型 + 系数 */
    @Data
    public static class DayResolve {
        private LocalDate date;
        private String dayType;
        private String dayTypeLabel;
        private BigDecimal ratio;
        private String holidayName;

        /** 无参构造，供 JSON 反序列化使用 */
        public DayResolve() {
        }

        /**
         * 全参构造。
         *
         * @param date          日期
         * @param dayType       日类型：WORKDAY / RESTDAY / LEGAL
         * @param dayTypeLabel  日类型中文名
         * @param ratio         折算比例
         * @param holidayName   命中的节假日名称，可为 null
         */
        public DayResolve(LocalDate date, String dayType, String dayTypeLabel, BigDecimal ratio, String holidayName) {
            this.date = date;
            this.dayType = dayType;
            this.dayTypeLabel = dayTypeLabel;
            this.ratio = ratio;
            this.holidayName = holidayName;
        }
    }

    /** 批量导入结果：成功条数、失败条数与逐行错误信息 */
    @Data
    public static class ImportResult {
        /** 成功导入的条数 */
        private int success = 0;
        /** 失败的条数 */
        private int failed = 0;
        /** 失败原因列表，含行号，便于用户逐条修正 */
        private List<String> errors = new ArrayList<>();
    }
}

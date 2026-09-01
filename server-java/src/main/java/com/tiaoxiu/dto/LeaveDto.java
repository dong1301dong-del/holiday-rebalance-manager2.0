package com.tiaoxiu.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 调休使用记录 DTO。
 * <p>
 * 旧版的「请假申请 + 审批」模型（CreateRequest/ApproveRequest）已彻底废弃，
 * 改为「录入即扣减、允许透支」的使用记录模型。
 */
public class LeaveDto {

    /** 单条使用记录输入（批量录入 / 单条录入 / 导入共用） */
    @Data
    public static class LeaveUsageItem {
        private Long userId;
        private LocalDate date;
        private LocalTime startTime;
        private LocalTime endTime;
        private String remark;
    }

    /** 批量录入请求 */
    @Data
    public static class BatchCreateRequest {
        private List<LeaveUsageItem> records = new ArrayList<>();
        /** 前端在余额不足弹窗中确认后置 true，允许透支扣减 */
        private Boolean allowOverdraft = false;
    }

    /** 编辑请求（按差额调整余额） */
    @Data
    public static class UpdateRequest {
        /**
         * 归属员工 ID。传入且与原值不同时视为「换人」：
         * 原员工全额退还调休余额、新员工按新时长扣减，两边余额即刻同步。
         * 不传则沿用原归属员工。
         */
        private Long userId;
        private LocalDate date;
        private LocalTime startTime;
        private LocalTime endTime;
        private String remark;
    }

    /** 作废请求 */
    @Data
    public static class VoidRequest {
        private String reason;
    }

    /** 列表查询条件 */
    @Data
    public static class QueryRequest {
        private LocalDate from;
        private LocalDate to;
        /** 姓名模糊 */
        private String name;
        private String department;
        private Long userId;
        private Integer page = 1;
        private Integer size = 20;
        /** 是否包含已作废记录，默认 false */
        private Boolean includeVoid = false;
    }

    /** 列表响应 */
    @Data
    public static class LeaveUsageResponse {
        private Long id;
        private LocalDate date;
        private Long userId;
        private String userName;
        private String department;
        private LocalTime startTime;
        private LocalTime endTime;
        private BigDecimal hours;
        private String remark;
        private String status;
        private String statusLabel;
    }

    /** 余额查询响应 */
    @Data
    public static class BalanceResponse {
        private Long userId;
        private String userName;
        private BigDecimal balance;
        private Boolean overdraft;
    }

    /** 余额预警消息响应 */
    @Data
    public static class MessageResponse {
        private Long id;
        private String type;
        private Long userId;
        private String userName;
        private String department;
        private String content;
        private String targetRoles;
        private Boolean read;
        private Boolean resolved;
        private BigDecimal balance;
        private LocalDateTime createdAt;
    }

    /** 批量录入结果 */
    @Data
    public static class BatchResult {
        /** 成功录入的条数 */
        private int count;
        /** 本次录入后处于透支状态（余额 < 0）的员工姓名，用于前端提示 */
        private List<String> overdraftNames = new ArrayList<>();
    }

    /** 导入结果：失败行不影响成功行入库 */
    @Data
    public static class ImportResult {
        /** 成功导入的条数 */
        private int success;
        /** 失败的条数 */
        private int failed;
        /** 逐行失败原因（含行号），便于用户按提示修正后重导 */
        private List<String> errors = new ArrayList<>();
    }
}

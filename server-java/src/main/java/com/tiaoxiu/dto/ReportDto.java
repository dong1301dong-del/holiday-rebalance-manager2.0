package com.tiaoxiu.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 统计报表相关的出入参定义。
 *
 * <p>覆盖三类场景：工作台概览、全员调休查看（分页列表 + 近 12 个月趋势）、
 * 工作台弹窗统计图（职位分布饼图、部门分布柱状图、透支人员列表）。
 */
public class ReportDto {

    /** 工作台概览统计卡片 */
    @Data
    public static class Dashboard {
        /** 员工总数 */
        private long userCount;
        /** 全员可调休总额（小时） */
        private BigDecimal totalBalance;     // 全员可调休总额
        /** 透支人数（余额 < 0） */
        private int overdraftCount;          // 透支人数
        /** 本月新增调休（加班折算合计） */
        private BigDecimal monthEarned;     // 本月新增（加班折算）
        /** 本月已使用调休（小时） */
        private BigDecimal monthUsed;        // 本月使用（调休）
        /** 逐员工的余额明细 */
        private List<UserBalance> users;
    }

    /** 单个员工的余额信息 */
    @Data
    public static class UserBalance {
        /** 用户 ID */
        private Long id;
        /** 登录账号 */
        private String username;
        /** 姓名 */
        private String name;
        /** 所属部门 */
        private String department;
        /** 当前调休余额（小时），可为负表示透支 */
        private BigDecimal balance;
        /** 是否透支 */
        private Boolean overdraft;
    }

    /** 员工余额详情：档案 + 余额流水明细 */
    @Data
    public static class UserDetail {
        /** 员工余额信息 */
        private UserBalance profile;
        /** BalanceLog 列表：该员工的余额变动流水 */
        private List<?> logs;                // BalanceLog 列表
    }

    // ==================== 全员调休查看 / 统计图 ====================

    /** 全员调休查看：列表一行（已排除管理员 / 录入员） */
    @Data
    public static class AllStaffLeaveResponse {
        private Long userId;
        private String name;
        private String department;
        /** 当前录入调休时长（区间内加班折算合计） */
        private BigDecimal earnedHours;
        /** 已使用调休时长（区间内调休使用合计） */
        private BigDecimal usedHours;
        /** 总计调休余额 = earnedHours − usedHours */
        private BigDecimal balance;
        /** 近 12 个月每月录入调休时长 */
        private List<TrendPoint> trendMonths;
    }

    /** 趋势点：month 为 yyyy-MM，date 为 yyyy-MM-dd（按接口二选一返回） */
    @Data
    public static class TrendPoint {
        private String month;
        private String date;
        private BigDecimal hours;
    }

    /** 工作台弹窗统计图数据 */
    @Data
    public static class DashboardChartsData {
        private List<PieItem> positionPie;
        private List<BarItem> departmentBar;
        private List<UserBalance> overdraftList;
    }

    /** 饼图数据项：职位维度的人数分布 */
    @Data
    public static class PieItem {
        /** 分类名称（如职位名称） */
        private String name;
        /** 该分类的数量 */
        private long value;
    }

    /** 柱状图数据项：部门维度的调休时长分布 */
    @Data
    public static class BarItem {
        /** 分类名称（如部门名称） */
        private String name;
        /** 该分类的数值（小时） */
        private BigDecimal value;
    }

    /** 全员调休查看：查询条件 */
    @Data
    public static class AllStaffLeaveQuery {
        /** 姓名模糊 */
        private String name;
        /** 部门模糊 */
        private String department;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;
        private int page = 1;
        private int size = 20;
    }

    /** 部门趋势：查询条件 */
    @Data
    public static class DepartmentTrendRequest {
        /** 部门名称 */
        private String department;
        /** 起始月份，格式 yyyy-MM */
        private String fromMonth;
        /** 结束月份，格式 yyyy-MM */
        private String toMonth;
    }

    /** 员工趋势：查询条件 */
    @Data
    public static class EmployeeTrendRequest {
        /** 员工 ID */
        private Long userId;
        /** 起始日期（含） */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;
        /** 结束日期（含） */
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;
    }
}

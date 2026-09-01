package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.PageResult;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.ReportDto;
import com.tiaoxiu.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 统计报表：工作台看板、下钻图表、全员调休查看、部门/员工加班趋势。
 * <p>
 * 统计口径统一：员工总数、余额、透支人数均<b>不含</b>管理员与录入员（二者为系统工作者，不占用员工数据）。
 * <p>
 * 统一路由前缀：{@code /api/report}；整体权限要求：所有接口均需登录且需 ADMIN 或 CLERK 角色
 * （{@code /balance} 例外，普通员工可访问但强制查本人）。
 */
@Tag(name = "统计报表", description = "工作台看板、下钻图表、全员调休查看、加班趋势分析")
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private static final String ALL_STAFF_EXPORT_FILENAME = "全员调休查看.xlsx";

    private final ReportService reportService;

    /**
     * 构造器注入。
     *
     * @param reportService 报表服务
     */
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 校验管理权限：仅 ADMIN / CLERK 可查看报表。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员/录入员可操作」
     */
    private void requireManage() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限：仅管理员/录入员可操作");
    }

    /**
     * 工作台看板指标：员工总数、全员可调休余额、透支人数、本月加班折调休、本月已休。
     *
     * <p>请求路径：{@code GET /api/report/dashboard}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 code=0 及看板指标与成员余额列表（均不含管理员 / 录入员）；失败抛出「无权限」
     */
    @Operation(summary = "工作台看板", description = "返回员工总数、全员余额、透支人数及本月加班/调休汇总")
    @GetMapping("/dashboard")
    public Result<ReportDto.Dashboard> dashboard() {
        requireManage();
        return Result.ok(reportService.dashboard());
    }

    /**
     * 工作台统计卡下钻数据：职位分布饼图、各部门余额柱状图、透支人员名单。
     *
     * <p>请求路径：{@code GET /api/report/dashboard/charts}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 code=0 及三组下钻数据；失败抛出「无权限」
     */
    @Operation(summary = "工作台下钻图表", description = "点击统计卡时加载：职位分布 / 部门余额 / 透支名单")
    @GetMapping("/dashboard/charts")
    public Result<ReportDto.DashboardChartsData> dashboardCharts() {
        requireManage();
        return Result.ok(reportService.dashboardCharts());
    }

    /**
     * 查询指定用户余额；不传 userId 时查当前登录者本人。
     *
     * <p>请求路径：{@code GET /api/report/balance?userId=}；所需权限：已登录即可（EMPLOYEE 强制查本人）。
     *
     * @param userId 用户 ID（可空，默认查当前登录者）
     * @return 成功返回 code=0 及余额与是否透支；失败抛出「成员不存在」
     */
    @Operation(summary = "用户余额", description = "管理员/录入员可查任意用户；普通员工强制查本人")
    @GetMapping("/balance")
    public Result<ReportDto.UserBalance> balance(@RequestParam(required = false) Long userId) {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) {
            userId = SecurityUtil.getUserId(); // 员工仅看本人
        }
        if (userId == null) userId = SecurityUtil.getUserId();
        return Result.ok(reportService.profile(userId));
    }

    /**
     * 全员调休查看：分页列表（含近 12 个月加班趋势数据）。
     *
     * <p>请求路径：{@code GET /api/report/all-staff-leave}；所需权限：ADMIN / CLERK。
     *
     * @param q 查询条件（姓名 / 部门 / 日期范围 / 分页）
     * @return 成功返回分页结果（每行含近 12 个月趋势数据）；失败抛出「无权限」
     */
    @Operation(summary = "全员调休查看", description = "分页返回全体员工的录入时长、已用时长、余额与月度加班趋势")
    @GetMapping("/all-staff-leave")
    public PageResult<ReportDto.AllStaffLeaveResponse> allStaffLeave(ReportDto.AllStaffLeaveQuery q) {
        requireManage();
        return reportService.allStaffLeave(q == null ? new ReportDto.AllStaffLeaveQuery() : q);
    }

    /**
     * 全员调休查看导出 Excel（查询结果为空时返回 400）。
     *
     * <p>请求路径：{@code GET /api/report/all-staff-leave/export}；所需权限：ADMIN / CLERK。
     *
     * @param q 查询条件
     * @return 成功返回 xlsx 文件流；无数据时返回 HTTP 400 且 body 为 code≠0 的「当前查询结果无数据可导出」
     */
    @Operation(summary = "全员调休查看导出", description = "按当前查询条件导出 Excel；无数据时提示「当前查询结果无数据可导出」")
    @GetMapping("/all-staff-leave/export")
    public ResponseEntity<?> exportAllStaffLeave(ReportDto.AllStaffLeaveQuery q) {
        requireManage();
        List<ReportDto.AllStaffLeaveResponse> rows =
                reportService.allStaffLeaveForExport(q == null ? new ReportDto.AllStaffLeaveQuery() : q);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Result.fail("当前查询结果无数据可导出"));
        }
        byte[] data = reportService.exportAllStaffLeaveExcel(rows);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; " + encodedFilename(ALL_STAFF_EXPORT_FILENAME));
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }

    /**
     * 部门加班趋势：按 yyyy-MM 汇总该部门全体员工的加班时长。
     *
     * <p>请求路径：{@code POST /api/report/trend/department}；所需权限：ADMIN / CLERK。
     *
     * @param req 部门 + 起始年月 + 结束年月（department 留空表示全公司；请求体可省略）
     * @return 成功返回 code=0 及按月份升序的趋势点列表（空月份补 0）；失败抛出「无权限」
     */
    @Operation(summary = "部门加班趋势", description = "按月份汇总部门加班时长；department 为空时统计全公司")
    @PostMapping("/trend/department")
    public Result<List<ReportDto.TrendPoint>> departmentTrend(
            @RequestBody(required = false) ReportDto.DepartmentTrendRequest req) {
        requireManage();
        if (req == null) req = new ReportDto.DepartmentTrendRequest();
        return Result.ok(reportService.departmentTrend(req.getDepartment(), req.getFromMonth(), req.getToMonth()));
    }

    /**
     * 员工加班趋势：按天汇总该员工的加班时长。
     *
     * <p>请求路径：{@code POST /api/report/trend/employee}；所需权限：ADMIN / CLERK。
     *
     * @param req 员工 ID + 起始日期 + 结束日期（请求体可省略）
     * @return 成功返回 code=0 及按日期升序的趋势点列表（无数据日期补 0）；
     *         失败抛出「无权限」「员工不能为空」或「查询区间不能超过一年」
     */
    @Operation(summary = "员工加班趋势", description = "按天汇总指定员工的加班时长，用于个人趋势折线图")
    @PostMapping("/trend/employee")
    public Result<List<ReportDto.TrendPoint>> employeeTrend(
            @RequestBody(required = false) ReportDto.EmployeeTrendRequest req) {
        requireManage();
        if (req == null) req = new ReportDto.EmployeeTrendRequest();
        return Result.ok(reportService.employeeTrend(req.getUserId(), req.getFrom(), req.getTo()));
    }

    /**
     * 生成符合 RFC 5987 的文件名下载头，解决中文文件名在部分浏览器下乱码的问题。
     *
     * @param filename 原始文件名
     * @return 用于 Content-Disposition 的 filename 片段
     */
    private static String encodedFilename(String filename) {
        try {
            return "filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return "filename=\"" + filename + "\"";
        }
    }
}

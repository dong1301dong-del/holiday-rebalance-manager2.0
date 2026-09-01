package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.HolidayDto;
import com.tiaoxiu.entity.Holiday;
import com.tiaoxiu.service.HolidayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 节假日日历：一年中每一天都有且仅有一条记录，日类型分三种
 * —— 法定节假日（粉）/ 法定工作日（蓝，含调休补班）/ 休息日（绿）。
 * <p>
 * 判定优先级：人工维护 &gt; 系统官方规则 &gt; 周末兜底。
 * 修改国家法定节假日时前端会二次确认，强制变更写系统日志。
 * <p>
 * 统一路由前缀：{@code /api/holidays}；整体权限要求：所有接口均需登录，
 * 查看与日期解析需 ADMIN / CLERK / EMPLOYEE 任一角色，保存 / 刷新 / 单条维护 / 删除需 ADMIN 或 CLERK。
 */
@Tag(name = "节假日日历", description = "年度概览、月份网格、保存变更、按规则刷新、日期类型解析")
@RestController
@RequestMapping("/api/holidays")
public class HolidayController {

    private final HolidayService holidayService;

    /**
     * 构造器注入。
     *
     * @param holidayService 节假日服务
     */
    public HolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    /**
     * 查看类权限：管理员 / 录入员 / 普通员工。
     *
     * @throws BizException 角色不符时抛出「无权限」
     */
    private void requireView() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK", "EMPLOYEE")) throw new BizException("无权限");
    }

    /**
     * 变更类权限：仅管理员 / 录入员。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员/录入员可维护节假日日历」
     */
    private void requireManage() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限：仅管理员/录入员可维护节假日日历");
    }

    /**
     * 年度概览：12 个月份统计（首页月份卡片）。
     *
     * <p>请求路径：{@code GET /api/holidays/year?year=2026}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @param year 年份（可空，默认当前年）
     * @return 成功返回 code=0 及 12 个月各自的三类日期数量与人工变更天数；失败抛出「无权限」
     */
    @Operation(summary = "年度概览", description = "返回指定年份 12 个月份卡片统计（法定节假日/法定工作日/休息日数量）")
    @GetMapping("/year")
    public Result<List<HolidayDto.MonthOverview>> year(
            @Parameter(description = "年份，如 2026") @RequestParam(required = false) Integer year) {
        requireView();
        int y = year != null ? year : LocalDate.now().getYear();
        return Result.ok(holidayService.yearOverview(y));
    }

    /**
     * 月份日历网格（周一为周起始，含上下月补位但不可编辑）。
     *
     * <p>请求路径：{@code GET /api/holidays/month?month=2026-01}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @param month 月份（yyyy-MM）
     * @return 成功返回 code=0 及当月完整日历网格；失败抛出「无权限」或「月份格式不正确，应为 yyyy-MM」
     */
    @Operation(summary = "月份日历网格", description = "返回当月日历网格，含上下月补位日期（补位日期不可编辑）")
    @GetMapping("/month")
    public Result<HolidayDto.MonthGrid> month(
            @Parameter(description = "月份 yyyy-MM", example = "2026-01") @RequestParam String month) {
        requireView();
        return Result.ok(holidayService.monthGrid(month));
    }

    /**
     * 保存某月的人工变更。
     *
     * <p>请求路径：{@code POST /api/holidays/month}；所需权限：ADMIN / CLERK。
     *
     * @param req 变更列表与年份月份
     * @return 成功返回 code=0 及保存条数与被判为高风险的日期警告文案；
     *         失败抛出「无权限」、日期格式或日期类型校验错误
     */
    @Operation(summary = "保存月份变更", description = "保存人工维护的日类型变更；改动国家法定节假日会写 WARN 级系统日志")
    @PostMapping("/month")
    public Result<HolidayDto.SaveResult> saveMonth(@RequestBody HolidayDto.SaveMonthRequest req) {
        requireManage();
        return Result.ok(holidayService.saveMonth(req));
    }

    /**
     * 按内置规则刷新官方数据。
     *
     * <p>请求路径：{@code POST /api/holidays/refresh}；所需权限：ADMIN / CLERK。
     *
     * @param req 刷新请求（scope=year/month，force=true 覆盖人工变更）
     * @return 成功返回 code=0 及刷新结果；存在人工变更且未强制时 refreshed=false 并返回冲突日期列表供二次确认
     */
    @Operation(summary = "刷新节假日", description = "按内置规则刷新；存在人工变更时返回冲突列表，force=true 可强制覆盖")
    @PostMapping("/refresh")
    public Result<HolidayDto.RefreshResult> refresh(@RequestBody HolidayDto.RefreshRequest req) {
        requireManage();
        return Result.ok(holidayService.refresh(req));
    }

    /**
     * 解析某天的类型与折算系数（加班录入表单调用）。
     *
     * <p>请求路径：{@code GET /api/holidays/resolve?date=2026-01-04}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @param date 日期（yyyy-MM-dd）
     * @return 成功返回 code=0 及日类型、折算系数、节假日名称与是否人工变更；失败抛出日期格式错误
     */
    @Operation(summary = "日期类型解析", description = "返回该日期的类型、转调休系数与命中的节假日名称")
    @GetMapping("/resolve")
    public Result<HolidayDto.ResolveResult> resolve(
            @Parameter(description = "日期 yyyy-MM-dd", example = "2026-01-04") @RequestParam String date) {
        requireView();
        LocalDate d;
        try {
            d = LocalDate.parse(date.trim());
        } catch (Exception e) {
            throw new BizException("日期格式不正确，应为 yyyy-MM-dd");
        }
        return Result.ok(holidayService.resolve(d));
    }

    /**
     * 查询全部节假日记录（兼容旧调用）。
     *
     * <p>请求路径：{@code GET /api/holidays}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @return 成功返回 code=0 及全部节假日记录（按日期升序）；失败抛出「无权限」
     */
    @GetMapping
    public Result<List<Holiday>> list() {
        requireView();
        return Result.ok(holidayService.list());
    }

    /**
     * 单条维护某天的节假日（兼容旧调用）。
     *
     * <p>请求路径：{@code POST /api/holidays}；所需权限：ADMIN / CLERK。
     *
     * @param req 维护请求（日期、名称、类型）
     * @return 成功返回 code=0 及保存后的记录；失败抛出「无权限」、日期为空或类型非法
     */
    @PostMapping
    public Result<Holiday> upsert(@RequestBody HolidayDto.UpsertRequest req) {
        requireManage();
        return Result.ok(holidayService.upsert(req));
    }

    /**
     * 删除某天的节假日记录（兼容旧调用，高风险操作）。
     *
     * <p>请求路径：{@code DELETE /api/holidays/{date}}；所需权限：ADMIN / CLERK。
     *
     * @param date 日期（yyyy-MM-dd）
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「日期格式不正确，应为 yyyy-MM-dd」
     */
    @DeleteMapping("/{date}")
    public Result<Void> delete(@PathVariable String date) {
        requireManage();
        try {
            holidayService.delete(LocalDate.parse(date));
        } catch (Exception e) {
            throw new BizException("日期格式不正确，应为 yyyy-MM-dd");
        }
        return Result.ok();
    }
}

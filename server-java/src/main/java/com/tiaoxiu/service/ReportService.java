package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.PageResult;
import com.tiaoxiu.dto.ReportDto;
import com.tiaoxiu.entity.*;
import com.tiaoxiu.repository.LeaveUsageRepository;
import com.tiaoxiu.repository.OvertimeRepository;
import com.tiaoxiu.repository.RoleRepository;
import com.tiaoxiu.repository.UserRepository;
import com.tiaoxiu.repository.UserRoleRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 统计报表：余额实时派生（不回写冗余字段）。 */
@Service
public class ReportService {

    /** 统计口径中的「员工」不含管理员与录入员 */
    private static final Set<String> EXCLUDE_ROLE_CODES = Set.of("ADMIN", "CLERK");
    private static final String UNSET = "未设置";
    private static final int TREND_MONTHS = 12;

    private static final String[] EXPORT_HEAD =
            {"序号", "员工姓名", "部门", "当前录入调休时长", "已使用调休时长", "总计调休余额"};

    private final UserRepository userRepository;
    private final OvertimeRepository overtimeRepository;
    private final LeaveUsageRepository leaveUsageRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BalanceService balanceService;

    /**
     * 构造器注入。
     *
     * @param userRepository        用户仓储
     * @param overtimeRepository    加班记录仓储（统计录入调休时长）
     * @param leaveUsageRepository  调休使用记录仓储（统计已使用时长）
     * @param roleRepository        角色仓储（识别需排除的管理员 / 录入员）
     * @param userRoleRepository    用户角色仓储
     * @param balanceService        余额引擎（取实时余额）
     */
    public ReportService(UserRepository userRepository, OvertimeRepository overtimeRepository,
                         LeaveUsageRepository leaveUsageRepository, RoleRepository roleRepository,
                         UserRoleRepository userRoleRepository, BalanceService balanceService) {
        this.userRepository = userRepository;
        this.overtimeRepository = overtimeRepository;
        this.leaveUsageRepository = leaveUsageRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.balanceService = balanceService;
    }

    /**
     * 工作台概览：员工总数、可调休总额、透支人数、本月新增 / 本月使用。
     *
     * <p>统计口径只含「员工」，不含管理员与录入员；已作废的调休使用不计入本月使用。
     *
     * @return 工作台概览数据
     */
    public ReportDto.Dashboard dashboard() {
        // 员工总数 / 可调休总额 / 透支人数仅统计员工，不含管理员与录入员
        List<User> users = staffUsers();
        BigDecimal total = BigDecimal.ZERO;
        int overdraft = 0;
        List<ReportDto.UserBalance> ubList = new ArrayList<>();
        for (User u : users) {
            BigDecimal b = balanceService.currentBalance(u.getId());
            boolean od = b.compareTo(BigDecimal.ZERO) < 0;
            if (od) overdraft++;
            total = total.add(b);
            ReportDto.UserBalance ub = new ReportDto.UserBalance();
            ub.setId(u.getId());
            ub.setUsername(u.getUsername());
            ub.setName(u.getName());
            ub.setDepartment(u.getDepartment());
            ub.setBalance(b.setScale(2, RoundingMode.HALF_UP));
            ub.setOverdraft(od);
            ubList.add(ub);
        }

        LocalDate now = LocalDate.now();
        LocalDate ms = now.withDayOfMonth(1);
        LocalDate me = now.withDayOfMonth(now.lengthOfMonth());
        Set<Long> excluded = excludedUserIds();

        BigDecimal monthEarned = BigDecimal.ZERO;
        for (OvertimeRecord r : overtimeRepository.findByDateBetween(ms, me)) {
            if (excluded.contains(r.getUserId())) continue;
            if (!OvertimeRecord.STATUS_CONFIRMED.equals(r.getStatus())) continue;
            BigDecimal converted = r.getConvertedHours();
            if (converted == null) {
                // 兼容历史数据：hours / ratio 可能为空（旧记录的列未回填）
                BigDecimal hours = r.getHours();
                BigDecimal ratio = r.getRatio();
                converted = (hours != null && ratio != null)
                        ? hours.multiply(ratio)
                        : BigDecimal.ZERO;
            }
            monthEarned = monthEarned.add(converted);
        }
        BigDecimal monthUsed = BigDecimal.ZERO;
        // 调休使用记录（无审批流程）：录入即生效，已作废的不计入；排除管理员/录入员
        for (LeaveUsageRecord r : leaveUsageRepository.findByDateBetweenOrderByDateAscIdAsc(ms, me)) {
            if (excluded.contains(r.getUserId())) continue;
            if (LeaveUsageRecord.STATUS_VOID.equals(r.getStatus())) continue;
            if (r.getHours() != null) monthUsed = monthUsed.add(r.getHours());
        }

        ReportDto.Dashboard d = new ReportDto.Dashboard();
        d.setUserCount(users.size());
        d.setTotalBalance(total.setScale(2, RoundingMode.HALF_UP));
        d.setOverdraftCount(overdraft);
        d.setMonthEarned(monthEarned.setScale(2, RoundingMode.HALF_UP));
        d.setMonthUsed(monthUsed.setScale(2, RoundingMode.HALF_UP));
        d.setUsers(ubList);
        return d;
    }

    /**
     * 查询某员工的余额档案（供详情弹窗使用）。
     *
     * @param userId 用户 ID
     * @return 员工余额信息
     * @throws BizException 用户不存在时抛出
     */
    public ReportDto.UserBalance profile(Long userId) {
        User u = userRepository.findById(userId).orElseThrow(() -> new com.tiaoxiu.common.BizException("成员不存在"));
        BigDecimal b = balanceService.currentBalance(userId);
        ReportDto.UserBalance ub = new ReportDto.UserBalance();
        ub.setId(u.getId());
        ub.setUsername(u.getUsername());
        ub.setName(u.getName());
        ub.setDepartment(u.getDepartment());
        ub.setBalance(b.setScale(2, RoundingMode.HALF_UP));
        ub.setOverdraft(b.compareTo(BigDecimal.ZERO) < 0);
        return ub;
    }

    // ==================== 全员调休查看 ====================

    /**
     * 全员调休查看：分页列表。
     *
     * <p>实现上先构建完整结果集再在内存中分页，因为每行都要聚合加班与使用数据并按姓名排序，
     * 无法直接下推到 SQL；数据量受员工规模限制，可控。
     *
     * @param q 查询条件（姓名、部门、日期区间、分页）
     * @return 分页结果
     */
    public PageResult<ReportDto.AllStaffLeaveResponse> allStaffLeave(ReportDto.AllStaffLeaveQuery q) {
        if (q == null) q = new ReportDto.AllStaffLeaveQuery();
        List<ReportDto.AllStaffLeaveResponse> all = buildAllStaffLeave(q);
        int page = Math.max(q.getPage(), 1);
        int size = Math.min(Math.max(q.getSize(), 1), 200);
        int total = all.size();
        int fromIdx = Math.min((page - 1) * size, total);
        int toIdx = Math.min(fromIdx + size, total);
        PageImpl<ReportDto.AllStaffLeaveResponse> p = new PageImpl<>(
                all.subList(fromIdx, toIdx), PageRequest.of(page - 1, size), total);
        PageResult<ReportDto.AllStaffLeaveResponse> res = PageResult.of(p);
        res.setPage(page); // 对外页码以 1 起始
        return res;
    }

    /**
     * 全员调休查看：导出（不分页，返回全部符合条件的数据）。
     *
     * @param q 查询条件；为 null 时按无条件处理
     * @return 全部员工调休明细列表，按姓名排序
     */
    public List<ReportDto.AllStaffLeaveResponse> allStaffLeaveForExport(ReportDto.AllStaffLeaveQuery q) {
        return buildAllStaffLeave(q == null ? new ReportDto.AllStaffLeaveQuery() : q);
    }

    /**
     * 构建全员调休明细：录入调休合计、已使用合计、余额，以及近 12 个月的月度趋势。
     *
     * @param q 查询条件
     * @return 员工调休明细列表，按姓名排序（空姓名排最后）
     */
    private List<ReportDto.AllStaffLeaveResponse> buildAllStaffLeave(ReportDto.AllStaffLeaveQuery q) {
        LocalDate from = q.getFrom();
        LocalDate to = q.getTo();
        List<User> users = filterStaff(q.getName(), q.getDepartment());

        Map<Long, BigDecimal> earnedMap = sumOvertimeByUser(from, to);
        Map<Long, BigDecimal> usedMap = sumUsageByUser(from, to);

        // 近 12 个月：以 to（留空则当月）所在月为终点，不受 from 影响；空月份补 0
        YearMonth endMonth = YearMonth.from(to != null ? to : LocalDate.now());
        YearMonth startMonth = endMonth.minusMonths(TREND_MONTHS - 1);
        LocalDate trendFrom = startMonth.atDay(1);
        LocalDate trendTo = endMonth.atEndOfMonth();
        // 末月只统计到 to 当天，避免越过用户选择的结束日期（末月为不足整月的部分统计）
        if (to != null && to.isBefore(trendTo)) trendTo = to;
        Map<Long, Map<YearMonth, BigDecimal>> trendMap = new HashMap<>();
        if (!trendFrom.isAfter(trendTo) && !users.isEmpty()) {
            trendMap = monthlyOvertimeByUser(trendFrom, trendTo);
        }
        List<YearMonth> months = monthRange(startMonth, endMonth);

        List<ReportDto.AllStaffLeaveResponse> rows = new ArrayList<>(users.size());
        for (User u : users) {
            BigDecimal earned = scale(earnedMap.get(u.getId()));
            BigDecimal used = scale(usedMap.get(u.getId()));
            ReportDto.AllStaffLeaveResponse r = new ReportDto.AllStaffLeaveResponse();
            r.setUserId(u.getId());
            r.setName(u.getName());
            r.setDepartment(u.getDepartment());
            r.setEarnedHours(earned);
            r.setUsedHours(used);
            r.setBalance(scale(earned.subtract(used)));
            r.setTrendMonths(toMonthPoints(months, trendMap.get(u.getId())));
            rows.add(r);
        }
        rows.sort(Comparator.<ReportDto.AllStaffLeaveResponse, String>comparing(
                ReportDto.AllStaffLeaveResponse::getName, Comparator.nullsLast(String::compareTo)));
        return rows;
    }

    /**
     * 部门趋势：部门内所有员工按月汇总的录入调休时长。
     *
     * <p>月份区间缺失时默认取「近 12 个月至当月」；若传入的起止月份颠倒会自动交换。
     *
     * @param department 部门名称（模糊匹配）；为空表示全部部门
     * @param fromMonth  起始月份 yyyy-MM，可为空
     * @param toMonth    结束月份 yyyy-MM，可为空
     * @return 逐月趋势点，空月份补 0
     */
    public List<ReportDto.TrendPoint> departmentTrend(String department, String fromMonth, String toMonth) {
        YearMonth start = parseMonth(fromMonth, YearMonth.now().minusMonths(TREND_MONTHS - 1));
        YearMonth end = parseMonth(toMonth, YearMonth.now());
        if (end.isBefore(start)) {
            YearMonth tmp = start;
            start = end;
            end = tmp;
        }
        List<User> users = filterStaff(null, department);
        Set<Long> userIds = new HashSet<>();
        for (User u : users) userIds.add(u.getId());

        Map<YearMonth, BigDecimal> agg = new LinkedHashMap<>();
        for (YearMonth m : monthRange(start, end)) agg.put(m, BigDecimal.ZERO);
        if (!userIds.isEmpty()) {
            for (OvertimeRecord r : overtimeRepository.findByDateBetween(start.atDay(1), end.atEndOfMonth())) {
                if (!OvertimeRecord.STATUS_CONFIRMED.equals(r.getStatus())) continue;
                if (r.getDate() == null || !userIds.contains(r.getUserId())) continue;
                YearMonth m = YearMonth.from(r.getDate());
                BigDecimal cur = agg.get(m);
                if (cur != null) agg.put(m, cur.add(convertedHours(r)));
            }
        }
        List<ReportDto.TrendPoint> points = new ArrayList<>(agg.size());
        for (Map.Entry<YearMonth, BigDecimal> e : agg.entrySet()) {
            ReportDto.TrendPoint p = new ReportDto.TrendPoint();
            p.setMonth(e.getKey().toString());
            p.setHours(scale(e.getValue()));
            points.add(p);
        }
        return points;
    }

    /**
     * 员工趋势：区间内每日录入调休时长（无数据的日期补 0，保证折线连续）。
     *
     * @param userId 员工 ID
     * @param from   起始日期，为空时默认取结束日前 29 天
     * @param to     结束日期，为空时默认为今天
     * @return 逐日趋势点
     * @throws BizException 员工为空，或查询区间超过一年时抛出
     */
    public List<ReportDto.TrendPoint> employeeTrend(Long userId, LocalDate from, LocalDate to) {
        if (userId == null) throw new BizException("员工不能为空");
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(29);
        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        if (ChronoUnit.DAYS.between(start, end) > 366) throw new BizException("查询区间不能超过一年");

        Map<LocalDate, BigDecimal> agg = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) agg.put(d, BigDecimal.ZERO);
        for (OvertimeRecord r : overtimeRepository.findByUserIdAndDateBetween(userId, start, end)) {
            if (!OvertimeRecord.STATUS_CONFIRMED.equals(r.getStatus())) continue;
            if (r.getDate() == null) continue;
            BigDecimal cur = agg.get(r.getDate());
            if (cur != null) agg.put(r.getDate(), cur.add(convertedHours(r)));
        }
        List<ReportDto.TrendPoint> points = new ArrayList<>(agg.size());
        for (Map.Entry<LocalDate, BigDecimal> e : agg.entrySet()) {
            ReportDto.TrendPoint p = new ReportDto.TrendPoint();
            p.setDate(e.getKey().toString());
            p.setMonth(YearMonth.from(e.getKey()).toString());
            p.setHours(scale(e.getValue()));
            points.add(p);
        }
        return points;
    }

    /**
     * 工作台弹窗统计图数据：职位人数分布饼图 + 部门余额柱状图 + 透支人员名单。
     *
     * <p>饼图按人数降序、柱状图按金额降序、透支名单按余额升序（透支最多的排最前）。
     * 职位或部门为空时归入「未设置」，避免图表出现空分类。
     *
     * @return 三张图的数据
     */
    public ReportDto.DashboardChartsData dashboardCharts() {
        List<User> users = staffUsers();

        Map<String, Long> positionCount = new LinkedHashMap<>();
        Map<String, BigDecimal> departmentSum = new LinkedHashMap<>();
        List<ReportDto.UserBalance> overdraftList = new ArrayList<>();
        for (User u : users) {
            String position = StringUtils.hasText(u.getPosition()) ? u.getPosition().trim() : UNSET;
            positionCount.merge(position, 1L, Long::sum);

            BigDecimal b = balanceService.currentBalance(u.getId());
            String department = StringUtils.hasText(u.getDepartment()) ? u.getDepartment().trim() : UNSET;
            departmentSum.merge(department, b, BigDecimal::add);

            if (b.compareTo(BigDecimal.ZERO) < 0) {
                ReportDto.UserBalance ub = new ReportDto.UserBalance();
                ub.setId(u.getId());
                ub.setName(u.getName());
                ub.setDepartment(u.getDepartment());
                ub.setBalance(scale(b));
                ub.setOverdraft(true);
                overdraftList.add(ub);
            }
        }

        List<ReportDto.PieItem> pie = new ArrayList<>(positionCount.size());
        for (Map.Entry<String, Long> e : positionCount.entrySet()) {
            ReportDto.PieItem item = new ReportDto.PieItem();
            item.setName(e.getKey());
            item.setValue(e.getValue());
            pie.add(item);
        }
        pie.sort(Comparator.<ReportDto.PieItem>comparingLong(ReportDto.PieItem::getValue).reversed());

        List<ReportDto.BarItem> bar = new ArrayList<>(departmentSum.size());
        for (Map.Entry<String, BigDecimal> e : departmentSum.entrySet()) {
            ReportDto.BarItem item = new ReportDto.BarItem();
            item.setName(e.getKey());
            item.setValue(scale(e.getValue()));
            bar.add(item);
        }
        bar.sort(Comparator.<ReportDto.BarItem, BigDecimal>comparing(ReportDto.BarItem::getValue).reversed());

        overdraftList.sort(Comparator.<ReportDto.UserBalance, BigDecimal>comparing(
                ReportDto.UserBalance::getBalance));

        ReportDto.DashboardChartsData data = new ReportDto.DashboardChartsData();
        data.setPositionPie(pie);
        data.setDepartmentBar(bar);
        data.setOverdraftList(overdraftList);
        return data;
    }

    /**
     * 全员调休查看：导出 Excel（含序号列）。
     *
     * @param rows 待导出的明细列表
     * @return xlsx 文件字节流
     * @throws BizException 生成失败时抛出
     */
    public byte[] exportAllStaffLeaveExcel(List<ReportDto.AllStaffLeaveResponse> rows) {
        try (Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("全员调休查看");
            CellStyle headStyle = headStyle(wb);
            Row head = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEAD.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(EXPORT_HEAD[i]);
                c.setCellStyle(headStyle);
            }
            for (int i = 0; i < rows.size(); i++) {
                ReportDto.AllStaffLeaveResponse r = rows.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(1).setCellValue(r.getName() == null ? "" : r.getName());
                row.createCell(2).setCellValue(r.getDepartment() == null ? "" : r.getDepartment());
                row.createCell(3).setCellValue(scale(r.getEarnedHours()).doubleValue());
                row.createCell(4).setCellValue(scale(r.getUsedHours()).doubleValue());
                row.createCell(5).setCellValue(scale(r.getBalance()).doubleValue());
            }
            for (int i = 0; i < EXPORT_HEAD.length; i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("导出失败：" + e.getMessage());
        }
    }

    // ==================== 内部工具 ====================

    /** 在职（非软删）且非管理员/录入员的员工列表 */
    private List<User> staffUsers() {
        List<User> users = userRepository.findByStatusNot(User.STATUS_DELETED);
        if (users.isEmpty()) return users;
        Set<Long> excluded = excludedUserIds();
        if (excluded.isEmpty()) return users;
        List<User> list = new ArrayList<>(users.size());
        for (User u : users) if (!excluded.contains(u.getId())) list.add(u);
        return list;
    }

    /** 拥有 ADMIN / CLERK 角色的用户 id（批量加载，避免逐人查库） */
    private Set<Long> excludedUserIds() {
        Set<Long> roleIds = new HashSet<>();
        for (Role r : roleRepository.findAll()) {
            if (r.getCode() != null && EXCLUDE_ROLE_CODES.contains(r.getCode().toUpperCase(Locale.ROOT))) {
                roleIds.add(r.getId());
            }
        }
        Set<Long> userIds = new HashSet<>();
        if (roleIds.isEmpty()) return userIds;
        for (UserRole ur : userRoleRepository.findAll()) {
            if (ur.getRoleId() != null && roleIds.contains(ur.getRoleId())) userIds.add(ur.getUserId());
        }
        return userIds;
    }

    /** 姓名 / 部门模糊过滤（空条件返回全部员工） */
    private List<User> filterStaff(String name, String department) {
        String n = normalize(name);
        String d = normalize(department);
        List<User> users = staffUsers();
        if (n == null && d == null) return users;
        List<User> list = new ArrayList<>();
        for (User u : users) {
            if (n != null && !contains(normalize(u.getName()), n)) continue;
            if (d != null && !contains(normalize(u.getDepartment()), d)) continue;
            list.add(u);
        }
        return list;
    }

    /**
     * 区间内各用户加班折算（录入调休）合计，仅统计已确认记录。
     *
     * @param from 起始日期，为 null 时用最小值兜底（不限）
     * @param to   结束日期，为 null 时用最大值兜底（不限）
     * @return 「用户 ID → 录入调休合计」映射
     */
    private Map<Long, BigDecimal> sumOvertimeByUser(LocalDate from, LocalDate to) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (OvertimeRecord r : overtimeRepository.findByDateBetween(boundFrom(from), boundTo(to))) {
            if (!OvertimeRecord.STATUS_CONFIRMED.equals(r.getStatus())) continue;
            map.merge(r.getUserId(), convertedHours(r), BigDecimal::add);
        }
        return map;
    }

    /**
     * 区间内各用户调休使用合计（已作废记录不计入）。
     *
     * @param from 起始日期，为 null 时不限
     * @param to   结束日期，为 null 时不限
     * @return 「用户 ID → 已使用合计」映射
     */
    private Map<Long, BigDecimal> sumUsageByUser(LocalDate from, LocalDate to) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (LeaveUsageRecord r : leaveUsageRepository.findByDateBetweenOrderByDateAscIdAsc(boundFrom(from), boundTo(to))) {
            if (LeaveUsageRecord.STATUS_VOID.equals(r.getStatus())) continue;
            map.merge(r.getUserId(), r.getHours() == null ? BigDecimal.ZERO : r.getHours(), BigDecimal::add);
        }
        return map;
    }

    /**
     * 区间内各用户按月的加班折算合计（用于近 12 个月趋势）。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 「用户 ID → （月份 → 当月合计）」嵌套映射
     */
    private Map<Long, Map<YearMonth, BigDecimal>> monthlyOvertimeByUser(LocalDate from, LocalDate to) {
        Map<Long, Map<YearMonth, BigDecimal>> map = new HashMap<>();
        for (OvertimeRecord r : overtimeRepository.findByDateBetween(from, to)) {
            if (!OvertimeRecord.STATUS_CONFIRMED.equals(r.getStatus()) || r.getDate() == null) continue;
            map.computeIfAbsent(r.getUserId(), k -> new HashMap<>())
                    .merge(YearMonth.from(r.getDate()), convertedHours(r), BigDecimal::add);
        }
        return map;
    }

    /**
     * 把「月份 → 数值」的稀疏映射展开为完整月份序列，缺失月份补 0。
     *
     * @param months 完整的月份序列（连续）
     * @param data   该员工的月度数据，可为 null（无任何记录）
     * @return 逐月趋势点列表
     */
    private List<ReportDto.TrendPoint> toMonthPoints(List<YearMonth> months, Map<YearMonth, BigDecimal> data) {
        List<ReportDto.TrendPoint> points = new ArrayList<>(months.size());
        for (YearMonth m : months) {
            ReportDto.TrendPoint p = new ReportDto.TrendPoint();
            p.setMonth(m.toString());
            p.setHours(scale(data == null ? null : data.get(m)));
            points.add(p);
        }
        return points;
    }

    /**
     * 生成从 start 到 end（含）的连续月份序列。
     *
     * @param start 起始月份
     * @param end   结束月份
     * @return 连续月份列表
     */
    private static List<YearMonth> monthRange(YearMonth start, YearMonth end) {
        List<YearMonth> list = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) list.add(m);
        return list;
    }

    /** 兼容历史数据：converted_hours 为空时用 hours × ratio */
    private static BigDecimal convertedHours(OvertimeRecord r) {
        BigDecimal converted = r.getConvertedHours();
        if (converted != null) return converted;
        BigDecimal hours = r.getHours();
        BigDecimal ratio = r.getRatio();
        return (hours != null && ratio != null) ? hours.multiply(ratio) : BigDecimal.ZERO;
    }

    /**
     * 金额规范化：null 视为 0，统一保留两位小数。
     *
     * @param v 原值，可为 null
     * @return 两位小数金额
     */
    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 解析月份，格式非法或为空时返回默认值（趋势查询对参数宽容，不打断用户操作）。
     *
     * @param v   月份字符串 yyyy-MM
     * @param def 解析失败时的默认月份
     * @return 解析后的月份或默认值
     */
    private static YearMonth parseMonth(String v, YearMonth def) {
        if (!StringUtils.hasText(v)) return def;
        try {
            return YearMonth.parse(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 关键字规范化：去空格转小写，实现大小写不敏感的模糊匹配。
     *
     * @param v 原值
     * @return 规范化后的值；空则返回 null
     */
    private static String normalize(String v) {
        return StringUtils.hasText(v) ? v.trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 安全的包含判断（text 为 null 时返回 false）。
     *
     * @param text    待搜索文本，可为 null
     * @param keyword 关键字
     * @return 包含返回 true
     */
    private static boolean contains(String text, String keyword) {
        return text != null && text.contains(keyword);
    }

    /**
     * 起始日期兜底：未传时用一个极早的日期，等价于不限制下界。
     *
     * @param from 起始日期，可为 null
     * @return 实际用于查询的起始日期
     */
    private static LocalDate boundFrom(LocalDate from) {
        return from != null ? from : LocalDate.of(1900, 1, 1);
    }

    /**
     * 结束日期兜底：未传时用一个极晚的日期，等价于不限制上界。
     *
     * @param to 结束日期，可为 null
     * @return 实际用于查询的结束日期
     */
    private static LocalDate boundTo(LocalDate to) {
        return to != null ? to : LocalDate.of(2999, 12, 31);
    }

    /**
     * 创建表头单元格样式：加粗、居中、灰底。
     *
     * @param wb 工作簿
     * @return 表头样式
     */
    private static CellStyle headStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}

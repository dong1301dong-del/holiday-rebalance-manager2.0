package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.HolidayDto;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.entity.Holiday;
import com.tiaoxiu.repository.HolidayRepository;
import com.tiaoxiu.service.ConfigService;
import com.tiaoxiu.util.ChineseHolidayRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 节假日日历：人工维护 &gt; 系统官方规则 &gt; 周末默认休息日 &gt; 工作日默认。
 * 每年每一天在库中都有记录，首次访问某月时按内置规则初始化（auto=true，officialType=type）。
 */
@Service
public class HolidayService {

    /** 保存日期类型的日志 action */
    public static final String ACTION_CHANGE = "变更日期类型";
    public static final String ACTION_REFRESH = "刷新节假日";

    /** 月份格式正则：yyyy-MM，限定月份为 01-12，避免把 2026-13 之类非法值传进 YearMonth */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    /** 年份格式正则：yyyy */
    private static final Pattern YEAR_PATTERN = Pattern.compile("^\\d{4}$");

    private final HolidayRepository holidayRepository;
    private final AuditLogService auditLogService;
    private final ConfigService configService;

    /**
     * 构造器注入。
     *
     * @param holidayRepository 节假日仓储
     * @param auditLogService   审计日志服务，用于记录日期类型变更与刷新操作
     * @param configService     系统配置服务，提供可配置的折算比例
     */
    public HolidayService(HolidayRepository holidayRepository, AuditLogService auditLogService, ConfigService configService) {
        this.holidayRepository = holidayRepository;
        this.auditLogService = auditLogService;
        this.configService = configService;
    }

    // ============ 年度概览 / 月份网格 ============

    /**
     * 年度概览：统计该年 12 个月各自的三类日天数与人工变更天数；缺失的日期先按内置规则初始化再统计。
     *
     * @param year 年份
     * @return 12 个月的概览统计列表，按月份升序
     */
    @Transactional
    public List<HolidayDto.MonthOverview> yearOverview(int year) {
        List<HolidayDto.MonthOverview> result = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            YearMonth ym = YearMonth.of(year, m);
            List<Holiday> days = ensureInitialized(ym.atDay(1), ym.atEndOfMonth());
            HolidayDto.MonthOverview o = new HolidayDto.MonthOverview();
            o.setMonth(ym.toString());
            o.setLabel(year + "年" + String.format("%02d", m) + "月");
            int workday = 0, rest = 0, manual = 0;
            for (Holiday h : days) {
                if (Holiday.isRestLike(h.getType())) rest++;
                else workday++;
                if (isManual(h)) manual++;
            }
            o.setWorkdayCount(workday);
            o.setRestCount(rest);
            o.setManualCount(manual);
            result.add(o);
        }
        return result;
    }

    /**
     * 月份日历网格：以周一为一周起始，补齐上月末尾与下月开头（补位日期 inMonth=false，不可编辑）。
     *
     * <p>补位日期仍会展示（保证日历是完整的 7 列 × N 行），但不落库、不可编辑。
     *
     * @param month 月份，格式 yyyy-MM
     * @return 该月的日历网格（含补位日期）
     * @throws BizException 月份格式不正确时抛出
     */
    @Transactional
    public HolidayDto.MonthGrid monthGrid(String month) {
        YearMonth ym = parseMonth(month);
        ensureInitialized(ym.atDay(1), ym.atEndOfMonth());

        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        // 周一为一周起始
        int lead = first.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        int tail = DayOfWeek.SUNDAY.getValue() - last.getDayOfWeek().getValue();
        LocalDate gridStart = first.minusDays(lead);
        LocalDate gridEnd = last.plusDays(tail);

        Map<LocalDate, Holiday> map = loadMap(gridStart, gridEnd);
        HolidayDto.MonthGrid grid = new HolidayDto.MonthGrid();
        grid.setMonth(ym.toString());
        grid.setLabel(ym.getYear() + "年" + String.format("%02d", ym.getMonthValue()) + "月");
        List<HolidayDto.DayCell> cells = new ArrayList<>();
        for (LocalDate d = gridStart; !d.isAfter(gridEnd); d = d.plusDays(1)) {
            Holiday h = map.get(d);
            String type;
            String name;
            String official;
            boolean manual = false;
            if (h != null) {
                type = Holiday.normalizeType(h.getType());
                name = h.getName();
                official = h.getOfficialType() != null ? Holiday.normalizeType(h.getOfficialType()) : type;
                manual = isManual(h);
            } else {
                ChineseHolidayRules.DayRule rule = ChineseHolidayRules.officialOf(d);
                type = rule != null ? Holiday.normalizeType(rule.type()) : Holiday.TYPE_WORKDAY;
                name = rule != null ? rule.name() : null;
                official = type;
            }
            HolidayDto.DayCell cell = new HolidayDto.DayCell();
            cell.setDate(d.toString());
            cell.setDay(d.getDayOfMonth());
            cell.setType(type);
            cell.setTypeLabel(ChineseHolidayRules.typeLabel(type));
            cell.setName(name);
            cell.setInMonth(!d.isBefore(first) && !d.isAfter(last));
            cell.setManual(manual);
            cell.setOfficialType(official);
            cells.add(cell);
        }
        grid.setDays(cells);
        return grid;
    }

    // ============ 保存变更 ============

    /**
     * 保存某月变更。与官方类型不一致视为人工变更（auto=false）；
     * 涉及法定节假日与工作日/休息日互转属于高风险操作，返回 warnings 并记录 WARN 日志。
     *
     * <p>仅接受属于该月的日期，越界日期直接跳过（防止前端篡改月份参数改到别的月份）。
     *
     * @param req 保存请求，含月份与变更项列表
     * @return 保存结果，含成功条数与高风险变更的警告文案
     * @throws BizException 请求为空、日期格式错误或日期类型非法时抛出
     */
    @Transactional
    public HolidayDto.SaveResult saveMonth(HolidayDto.SaveMonthRequest req) {
        if (req == null) throw new BizException("请求参数不能为空");
        YearMonth ym = parseMonth(req.getMonth());
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        Map<LocalDate, Holiday> map = loadMap(start, end);

        HolidayDto.SaveResult result = new HolidayDto.SaveResult();
        List<Holiday> toSave = new ArrayList<>();
        int saved = 0;
        boolean hasRisk = false;

        for (HolidayDto.ChangeItem item : nullSafe(req.getChanges())) {
            if (item == null || item.getDate() == null) continue;
            LocalDate d;
            try {
                d = LocalDate.parse(item.getDate().trim());
            } catch (Exception e) {
                throw new BizException("日期格式不正确：" + item.getDate());
            }
            if (d.isBefore(start) || d.isAfter(end)) continue; // 只允许修改当前月的日期
            Holiday h = map.get(d);
            if (h == null) continue;
            String newType = normalizeType(item.getType());
            String official = Holiday.normalizeType(h.getOfficialType() != null ? h.getOfficialType() : officialTypeOf(d));
            if (newType.equals(Holiday.normalizeType(h.getType())) && Boolean.TRUE.equals(h.getAuto())) continue; // 无变化

            String beforeType = Holiday.normalizeType(h.getType());
            String riskName = h.getName();
            boolean risk = StringUtils.hasText(riskName);
            h.setType(newType);
            h.setOfficialType(official);
            h.setAuto(newType.equals(official));
            h.setName(resolveName(d, newType, h.getName()));
            toSave.add(h);
            saved++;

            if (risk) {
                hasRisk = true;
                result.getWarnings().add(warningOf(riskName, newType));
            }
            auditLogService.logChange(AuditLog.MODULE_HOLIDAY, ACTION_CHANGE, d.toString(),
                    "将 " + d + " 由「" + ChineseHolidayRules.typeLabel(beforeType)
                            + "」变更为「" + ChineseHolidayRules.typeLabel(newType) + "」"
                            + (risk ? "（高风险：涉及国家法定节假日）" : ""),
                    beforeType, newType, risk ? AuditLog.LEVEL_WARN : AuditLog.LEVEL_INFO);
        }

        if (!toSave.isEmpty()) holidayRepository.saveAll(toSave);
        result.setSaved(saved);
        auditLogService.log(AuditLog.MODULE_HOLIDAY, ACTION_CHANGE, ym.toString(),
                "保存 " + ym + " 节假日变更，共 " + saved + " 天"
                        + (hasRisk ? "，其中 " + result.getWarnings().size() + " 天为高风险变更" : ""),
                hasRisk ? AuditLog.LEVEL_WARN : AuditLog.LEVEL_INFO, AuditLog.RESULT_SUCCESS);
        return result;
    }

    // ============ 刷新 ============

    /**
     * 按内置规则重算官方数据；存在人工变更时除非 force，否则不写入。
     *
     * <p>两段式设计：先扫描出与官方规则不一致的「人工变更日」作为冲突列表返回，
     * 由前端二次确认后再带 force=true 提交，避免一键刷新把管理员的手动调整悄悄覆盖掉。
     *
     * @param req 刷新请求，支持按年（scope=year）或按月（scope=month）刷新
     * @return 刷新结果，未真正写入时 refreshed=false 并携带冲突日期列表
     * @throws BizException 请求为空、月份或年份格式不正确时抛出
     */
    @Transactional
    public HolidayDto.RefreshResult refresh(HolidayDto.RefreshRequest req) {
        if (req == null) throw new BizException("请求参数不能为空");
        String scope = StringUtils.hasText(req.getScope()) ? req.getScope() : "year";
        String value = req.getValue();
        LocalDate start;
        LocalDate end;
        if ("month".equalsIgnoreCase(scope)) {
            YearMonth ym = parseMonth(value);
            start = ym.atDay(1);
            end = ym.atEndOfMonth();
        } else {
            if (value == null || !YEAR_PATTERN.matcher(value.trim()).matches()) {
                throw new BizException("年份格式不正确，应为 yyyy");
            }
            int year = Integer.parseInt(value.trim());
            start = LocalDate.of(year, 1, 1);
            end = LocalDate.of(year, 12, 31);
        }
        boolean force = Boolean.TRUE.equals(req.getForce());

        Map<LocalDate, ChineseHolidayRules.DayRule> rules = rulesOfRange(start, end);
        Map<LocalDate, Holiday> rows = loadMap(start, end);

        List<HolidayDto.Conflict> conflicts = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Holiday h = rows.get(d);
            ChineseHolidayRules.DayRule rule = rules.get(d);
            if (h == null || rule == null) continue;
            boolean manual = !Boolean.TRUE.equals(h.getAuto())
                    || (h.getOfficialType() != null && !h.getOfficialType().equals(h.getType()));
            if (!manual) continue;
            if (!rule.type().equals(h.getType())) {
                conflicts.add(conflictOf(d, h, rule));
            }
        }

        HolidayDto.RefreshResult res = new HolidayDto.RefreshResult();
        if (!conflicts.isEmpty() && !force) {
            res.setRefreshed(false);
            res.setConflicts(conflicts);
            res.setMessage("检测到 " + conflicts.size() + " 天存在人工变更，确认强制刷新将覆盖这些修改");
            return res; // 不写入
        }

        List<Holiday> toSave = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            ChineseHolidayRules.DayRule rule = rules.get(d);
            if (rule == null) continue;
            Holiday h = rows.get(d);
            if (h == null) {
                h = new Holiday(d, rule.name(), rule.type(), rule.type(), Boolean.TRUE);
            } else {
                // 历史数据补全：补齐官方类型并归一化已废弃类型为双态，仅脏数据才回写
                String official = h.getOfficialType() != null
                        ? Holiday.normalizeType(h.getOfficialType())
                        : (rule != null ? Holiday.normalizeType(rule.type()) : Holiday.TYPE_WORKDAY);
                String curType = Holiday.normalizeType(h.getType());
                boolean dirty = false;
                if (!official.equals(h.getOfficialType())) {
                    h.setOfficialType(official);
                    dirty = true;
                }
                if (!curType.equals(h.getType())) {
                    h.setType(curType);
                    dirty = true;
                }
                if (!isKnownType(h.getType())) {
                    h.setType(official);
                    h.setName(rule != null ? rule.name() : null);
                    h.setAuto(Boolean.TRUE);
                    dirty = true;
                }
                if (dirty) {
                    toSave.add(h);
                }
            }
        }
        holidayRepository.saveAll(toSave);

        res.setRefreshed(true);
        res.setConflicts(conflicts);
        if (conflicts.isEmpty()) {
            res.setMessage("刷新成功");
            auditLogService.log(AuditLog.MODULE_HOLIDAY, ACTION_REFRESH, value,
                    "按内置规则刷新 " + value + "，共 " + toSave.size() + " 天",
                    AuditLog.LEVEL_INFO, AuditLog.RESULT_SUCCESS);
        } else {
            res.setMessage("已强制刷新，覆盖 " + conflicts.size() + " 天人工变更");
            auditLogService.log(AuditLog.MODULE_HOLIDAY,
                    "强制刷新节假日（覆盖 " + conflicts.size() + " 天人工变更）", value,
                    "强制刷新 " + value + "，共 " + toSave.size() + " 天，覆盖人工变更："
                            + joinDates(conflicts),
                    AuditLog.LEVEL_WARN, AuditLog.RESULT_SUCCESS);
        }
        return res;
    }

    // ============ 解析（供加班录入表单调用） ============

    /**
     * 解析某天的类型与折算系数，供加班录入表单自动带出用：法定工作日 0.5，休息日 1，法定节假日 1。
     *
     * <p>库中记录优先；若库里没维护或类型已废弃，则回退到内置规则。
     *
     * @param date 目标日期
     * @return 解析结果（日类型、系数、节假日名称、是否人工变更）
     * @throws BizException 日期为空时抛出
     */
    @Transactional(readOnly = true)
    public HolidayDto.ResolveResult resolve(LocalDate date) {
        if (date == null) throw new BizException("日期不能为空");
        HolidayDto.ResolveResult r = new HolidayDto.ResolveResult();
        r.setDate(date.toString());
        Holiday h = holidayRepository.findByDate(date).orElse(null);
        String type;
        String name = null;
        boolean manual = false;
        if (h != null && isKnownType(h.getType())) {
            type = Holiday.normalizeType(h.getType());
            name = h.getName();
            manual = isManual(h);
        } else {
            ChineseHolidayRules.DayRule rule = ChineseHolidayRules.officialOf(date);
            type = rule != null ? Holiday.normalizeType(rule.type()) : Holiday.TYPE_WORKDAY;
            name = rule != null ? rule.name() : null;
        }
        r.setType(type);
        r.setTypeLabel(ChineseHolidayRules.typeLabel(type));
        r.setRatio(ratioForHolidayType(type));
        r.setName(name);
        r.setManual(manual);
        r.setDayType(type);
        r.setHolidayName(name);
        return r;
    }

    /**
     * 判断某日期是否为休息日（含法定节假日、周末等）。
     *
     * @param date 目标日期
     * @return 休息日返回 true；日期为空返回 false
     */
    @Transactional(readOnly = true)
    public boolean isRestDay(LocalDate date) {
        if (date == null) return false;
        Holiday h = holidayRepository.findByDate(date).orElse(null);
        if (h != null && isKnownType(h.getType())) {
            return Holiday.isRestLike(h.getType());
        }
        ChineseHolidayRules.DayRule rule = ChineseHolidayRules.officialOf(date);
        if (rule != null) {
            return Holiday.isRestLike(rule.type());
        }
        return ChineseHolidayRules.isWeekend(date);
    }

    /**
     * 取某日期的节假日名称（库记录优先，其次官方规则）。
     *
     * @param date 目标日期
     * @return 节假日名称；无名称或日期为空返回 null
     */
    @Transactional(readOnly = true)
    public String holidayNameOf(LocalDate date) {
        if (date == null) return null;
        Holiday h = holidayRepository.findByDate(date).orElse(null);
        if (h != null && StringUtils.hasText(h.getName())) {
            return h.getName();
        }
        ChineseHolidayRules.DayRule rule = ChineseHolidayRules.officialOf(date);
        return rule != null ? rule.name() : null;
    }

    // ============ 兼容旧调用 ============

    /**
     * 查询全部节假日记录，按日期升序（兼容旧调用）。
     *
     * @return 全部节假日记录列表
     */
    @Transactional(readOnly = true)
    public List<Holiday> list() {
        return holidayRepository.findAllByOrderByDateAsc();
    }

    /**
     * 查询指定日期区间内的节假日记录（兼容旧调用）。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 区间内的节假日记录列表
     */
    @Transactional(readOnly = true)
    public List<Holiday> listRange(LocalDate start, LocalDate end) {
        return holidayRepository.findByDateBetween(start, end);
    }

    /**
     * 判定某日期的日类型（用于加班折算，保留旧加班模块的常量语义）。
     *
     * <p>额外区分「调休补班日」：库中类型是工作日但当天是周末时判定为 ADJUSTED，
     * 因为补班日的折算比例与工作日一致（0.5），需要与普通休息日区分开。
     *
     * @param date 目标日期
     * @return 日类型，见 {@link OvertimeDayType}
     */
    @Transactional(readOnly = true)
    public String determineDayType(LocalDate date) {
        Holiday h = holidayRepository.findByDate(date).orElse(null);
        if (h != null && isKnownType(h.getType())) {
            if (Holiday.isRestLike(h.getType())) {
                return OvertimeDayType.RESTDAY;
            }
            // 其余（WORKDAY）：若落在周末则为调休补班
            return ChineseHolidayRules.isWeekend(date) ? OvertimeDayType.ADJUSTED : OvertimeDayType.WORKDAY;
        }
        return ChineseHolidayRules.isWeekend(date) ? OvertimeDayType.RESTDAY : OvertimeDayType.WORKDAY;
    }

    /**
     * 按加班日类型返回折算比例：工作日 / 补班日 0.5；休息日 / 法定节假日 1。
     *
     * @param dayType 加班日类型，见 {@link OvertimeDayType}
     * @return 折算比例
     */
    public BigDecimal ratioForDayType(String dayType) {
        if (OvertimeDayType.WORKDAY.equals(dayType) || OvertimeDayType.ADJUSTED.equals(dayType)) {
            return configService.ratioWorkday();
        }
        return configService.ratioRest();
    }

    /**
     * 按节假日类型返回折算比例：法定工作日 0.5，休息日 / 法定节假日 1，均改为可配置读取。
     *
     * @param type 节假日类型，见 Holiday.TYPE_*
     * @return 折算比例
     */
    public BigDecimal ratioForHolidayType(String type) {
        if (Holiday.TYPE_WORKDAY.equals(type)) {
            return configService.ratioWorkday();
        }
        return configService.ratioRest();
    }

    /**
     * 新增或更新单天的节假日记录（兼容旧的单条维护接口）。
     *
     * <p>未指定 auto 时默认按「人工维护」处理，从而优先级高于内置规则。
     *
     * @param req 维护请求，含日期、名称、类型
     * @return 保存后的节假日记录
     * @throws BizException 日期为空或类型非法时抛出
     */
    @Transactional
    public Holiday upsert(HolidayDto.UpsertRequest req) {
        if (req == null || req.getDate() == null || !StringUtils.hasText(req.getDate())) {
            throw new BizException("日期不能为空");
        }
        LocalDate d = LocalDate.parse(req.getDate().trim());
        String type = normalizeType(req.getType());
        Holiday h = holidayRepository.findByDate(d).orElse(null);
        if (h == null) {
            h = new Holiday();
            h.setDate(d);
            h.setOfficialType(officialTypeOf(d));
        }
        if (h.getOfficialType() == null) h.setOfficialType(officialTypeOf(d));
        h.setType(type);
        h.setName(req.getName());
        h.setAuto(req.getAuto() != null ? req.getAuto() : Boolean.FALSE);
        Holiday saved = holidayRepository.save(h);
        auditLogService.logChange(AuditLog.MODULE_HOLIDAY, ACTION_CHANGE, d.toString(),
                "维护 " + d + " 为「" + ChineseHolidayRules.typeLabel(type) + "」",
                h.getOfficialType(), type, AuditLog.LEVEL_INFO);
        return saved;
    }

    /**
     * 删除某天的节假日记录（高风险操作，记录 WARN 级日志）。
     *
     * <p>删除后该日将回退到「内置规则 / 周末默认」判定，可能改变历史加班的折算口径。
     *
     * @param date 目标日期
     * @throws BizException 日期为空时抛出
     */
    @Transactional
    public void delete(LocalDate date) {
        if (date == null) throw new BizException("日期不能为空");
        holidayRepository.deleteByDate(date);
        auditLogService.log(AuditLog.MODULE_HOLIDAY, ACTION_CHANGE, date.toString(),
                "删除 " + date + " 的节假日记录", AuditLog.LEVEL_WARN, AuditLog.RESULT_SUCCESS);
    }

    // ============ 内部工具 ============

    /**
     * 确保区间内每一天都有记录，缺失的按内置规则初始化（auto=true，officialType=type）。
     *
     * <p>采用「惰性初始化」：不预先灌全量日历，而是在首次访问某月时才补齐该月的记录，
     * 既省去全量初始化开销，又保证查询永远有数据可返回。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 区间内每一天的节假日实体（含本次新初始化的）
     */
    @Transactional
    public List<Holiday> ensureInitialized(LocalDate start, LocalDate end) {
        Map<LocalDate, ChineseHolidayRules.DayRule> rules = rulesOfRange(start, end);
        Map<LocalDate, Holiday> existing = loadMap(start, end);
        List<Holiday> toSave = new ArrayList<>();
        List<Holiday> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Holiday h = existing.get(d);
            ChineseHolidayRules.DayRule rule = rules.get(d);
            if (h == null) {
                h = new Holiday(d, rule != null ? rule.name() : null,
                        rule != null ? rule.type() : Holiday.TYPE_WORKDAY,
                        rule != null ? rule.type() : Holiday.TYPE_WORKDAY, Boolean.TRUE);
                toSave.add(h);
            } else if (h.getOfficialType() == null || !isKnownType(h.getType())) {
                // 历史数据补全：补齐官方类型，归一化已废弃的类型
                h.setOfficialType(rule != null ? rule.type() : h.getType());
                if (!isKnownType(h.getType())) {
                    h.setType(h.getOfficialType());
                    h.setName(rule != null ? rule.name() : null);
                    h.setAuto(Boolean.TRUE);
                }
                toSave.add(h);
            }
            result.add(h);
        }
        if (!toSave.isEmpty()) holidayRepository.saveAll(toSave);
        return result;
    }

    /**
     * 一次性取出区间覆盖到的所有年份的官方规则（按年缓存式计算，跨年区间会合并多年结果）。
     *
     * @param start 起始日期
     * @param end   结束日期
     * @return 「日期 → 官方规则」映射
     */
    private Map<LocalDate, ChineseHolidayRules.DayRule> rulesOfRange(LocalDate start, LocalDate end) {
        Map<LocalDate, ChineseHolidayRules.DayRule> map = new LinkedHashMap<>();
        // 区间可能跨年（如 12 月的日历网格含次年 1 月的补位日），需逐年取规则再合并
        for (int y = start.getYear(); y <= end.getYear(); y++) {
            map.putAll(ChineseHolidayRules.officialOfYear(y));
        }
        return map;
    }

    /**
     * 把区间内的节假日记录转成「日期 → 实体」映射，便于后续按日 O(1) 取值。
     *
     * @param start 起始日期
     * @param end   结束日期
     * @return 日期到实体的映射
     */
    private Map<LocalDate, Holiday> loadMap(LocalDate start, LocalDate end) {
        Map<LocalDate, Holiday> map = new LinkedHashMap<>();
        for (Holiday h : holidayRepository.findByDateBetween(start, end)) {
            map.put(h.getDate(), h);
        }
        return map;
    }

    /**
     * 取某日的官方类型，规则缺失时保守地按工作日处理。
     *
     * @param date 目标日期
     * @return 官方日类型
     */
    private String officialTypeOf(LocalDate date) {
        ChineseHolidayRules.DayRule rule = ChineseHolidayRules.officialOf(date);
        return rule != null ? rule.type() : Holiday.TYPE_WORKDAY;
    }

    /**
     * 决定变更后该日显示的名称：有法定名称则用法定名称；WORKDAY 返回 null；
     * 其余沿用旧名，没有则 null。
     *
     * @param date    目标日期
     * @param newType 变更后的类型
     * @param oldName 原名称
     * @return 应使用的名称；非节假日且无官方名称时返回 null
     */
    private String resolveName(LocalDate date, String newType, String oldName) {
        ChineseHolidayRules.DayRule rule = ChineseHolidayRules.officialOf(date);
        if (rule != null && StringUtils.hasText(rule.name())) {
            return rule.name();
        }
        if (Holiday.TYPE_WORKDAY.equals(newType)) {
            return null;
        }
        return StringUtils.hasText(oldName) ? oldName : null;
    }

    /**
     * 是否属于高风险变更：有节假日名称即视为高风险（涉及国家法定节假日）。
     *
     * @param name 节假日名称
     * @return 有名称返回 true
     */
    private boolean isHighRisk(String name) {
        return StringUtils.hasText(name);
    }

    /**
     * 生成高风险变更的确认文案。
     *
     * @param name    节假日名称
     * @param newType 变更后的类型
     * @return 面向用户的警告文案
     */
    private String warningOf(String name, String newType) {
        return "该日期为国家法定节假日「" + name + "」，变更为「"
                + ChineseHolidayRules.typeLabel(newType) + "」可能影响历史数据与后续加班折算口径，是否继续？";
    }

    /**
     * 组装一条「人工变更冲突」记录，供前端在强制刷新前展示对比。
     *
     * @param d    日期
     * @param h    库中当前记录
     * @param rule 官方规则
     * @return 冲突项
     */
    private HolidayDto.Conflict conflictOf(LocalDate d, Holiday h, ChineseHolidayRules.DayRule rule) {
        HolidayDto.Conflict c = new HolidayDto.Conflict();
        c.setDate(d.toString());
        c.setCurrentType(h.getType());
        c.setCurrentTypeLabel(ChineseHolidayRules.typeLabel(h.getType()));
        c.setOfficialType(rule.type());
        c.setOfficialTypeLabel(ChineseHolidayRules.typeLabel(rule.type()));
        c.setName(h.getName());
        return c;
    }

    /**
     * 把冲突日期拼接成日志文案，最多列 10 天，超出部分汇总为「等 N 天」。
     *
     * @param conflicts 冲突列表
     * @return 拼接后的日期描述
     */
    private String joinDates(List<HolidayDto.Conflict> conflicts) {
        StringBuilder sb = new StringBuilder();
        // 限制列举数量，避免冲突过多时把日志字段（TEXT）撑爆
        int limit = Math.min(conflicts.size(), 10);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append("、");
            sb.append(conflicts.get(i).getDate());
        }
        if (conflicts.size() > limit) sb.append(" 等 ").append(conflicts.size()).append(" 天");
        return sb.toString();
    }

    /**
     * 判断某天是否被人工干预过：非系统生成，或生效类型偏离官方类型。
     *
     * @param h 节假日记录
     * @return 人工变更过返回 true
     */
    private boolean isManual(Holiday h) {
        if (!Boolean.TRUE.equals(h.getAuto())) return true;
        return h.getOfficialType() != null && !h.getOfficialType().equals(h.getType());
    }

    /**
     * 判断类型是否为当前支持的三类之一（用于过滤历史遗留的废弃类型）。
     *
     * @param type 日类型
     * @return 是合法类型返回 true
     */
    private boolean isKnownType(String type) {
        return Holiday.isKnownType(type);
    }

    /**
     * 校验并归一日类型：先把 LEGAL 归一到 RESTDAY，再限定只能为 WORKDAY / RESTDAY。
     *
     * @param type 日类型
     * @return 校验通过并归一后的日类型
     * @throws BizException 类型不为 WORKDAY / RESTDAY 时抛出
     */
    private String normalizeType(String type) {
        String t = Holiday.normalizeType(type);
        if (!Holiday.TYPE_WORKDAY.equals(t) && !Holiday.TYPE_RESTDAY.equals(t)) {
            throw new BizException("日期类型不正确，应为 WORKDAY（法定工作日）或 RESTDAY（法定休息日）");
        }
        return t;
    }

    /**
     * 解析月份参数。
     *
     * @param month 月份字符串，格式 yyyy-MM
     * @return 解析后的 {@link YearMonth}
     * @throws BizException 格式不正确时抛出
     */
    private YearMonth parseMonth(String month) {
        if (month == null || !MONTH_PATTERN.matcher(month.trim()).matches()) {
            throw new BizException("月份格式不正确，应为 yyyy-MM");
        }
        return YearMonth.parse(month.trim());
    }

    /**
     * 空列表兜底，避免调用方对 null 列表做 for-each 时抛空指针。
     *
     * @param list 可能为 null 的列表
     * @param <T>  元素类型
     * @return 原列表或空列表
     */
    private <T> List<T> nullSafe(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }

    /** 加班日类型常量（与节假日类型区分，保留旧加班模块语义） */
    public static class OvertimeDayType {
        public static final String WORKDAY = "WORKDAY";
        public static final String RESTDAY = "RESTDAY";
        public static final String HOLIDAY = "HOLIDAY";
        public static final String ADJUSTED = "ADJUSTED";
    }
}

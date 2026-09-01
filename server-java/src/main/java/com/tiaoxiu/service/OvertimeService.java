package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.PageResult;
import com.tiaoxiu.dto.HolidayDto;
import com.tiaoxiu.dto.OvertimeDto;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.entity.BalanceLog;
import com.tiaoxiu.entity.OvertimeRecord;
import com.tiaoxiu.entity.Role;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.entity.UserRole;
import com.tiaoxiu.repository.OvertimeRepository;
import com.tiaoxiu.repository.RoleRepository;
import com.tiaoxiu.repository.UserRepository;
import com.tiaoxiu.repository.UserRoleRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 加班转调休录入管理。
 * <p>
 * 时长规则（仅适用于加班，与调休管理无关）：
 * <ul>
 *   <li>加班时长 = 结束时间 − 开始时间（小时，两位小数），不做任何时段扣除、不封顶；</li>
 *   <li>起止时间以 30 分钟为一跳，分钟必须为 00 或 30；</li>
 *   <li>转调休时长 = 加班时长 × 转调休系数；</li>
 *   <li>系数由节假日日历自动判定：法定工作日 0.5，休息日 1，法定节假日 1。</li>
 * </ul>
 */
@Service
public class OvertimeService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<DateTimeFormatter> DATE_PATTERNS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    );
    private static final List<DateTimeFormatter> TIME_PATTERNS = Arrays.asList(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("HHmm")
    );

    private static final String[] EXPORT_HEAD = {
            "加班日期", "员工姓名", "部门", "模式", "类型", "开始时间", "结束时间", "打卡时间", "加班时长", "转调休时长", "备注"
    };
    private static final String[] TEMPLATE_HEAD = {
            "员工姓名/工号", "加班日期", "模式", "开始时间", "结束时间", "打卡时间", "转休时长", "备注"
    };

    /** 模板「模式」列下拉值 */
    private static final String MODE_LABEL_OVERTIME = "加班转调休";
    private static final String MODE_LABEL_MANUAL = "其他转调休";

    private final OvertimeRepository overtimeRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final HolidayService holidayService;
    private final BalanceService balanceService;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入。
     *
     * @param overtimeRepository 加班记录仓储
     * @param userRepository     用户仓储（校验员工存在性与状态、姓名/工号反查）
     * @param userRoleRepository 用户角色绑定仓储
     * @param roleRepository     角色仓储
     * @param holidayService     节假日服务（判定日类型与折算系数）
     * @param balanceService     余额引擎（入账 / 冲减调休余额）
     * @param auditLogService    审计日志服务
     */
    public OvertimeService(OvertimeRepository overtimeRepository, UserRepository userRepository,
                           UserRoleRepository userRoleRepository, RoleRepository roleRepository,
                           HolidayService holidayService, BalanceService balanceService,
                           AuditLogService auditLogService) {
        this.overtimeRepository = overtimeRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.holidayService = holidayService;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
    }

    // ==================== 权限校验 ====================

    /**
     * 校验目标用户是否为可被录入加班/调休的普通员工。
     * <p>系统管理员（ADMIN）与录入员（CLERK）属于系统工作者，不参与加班/调休余额计算。
     *
     * @param userId 用户 ID
     * @throws BizException 用户不存在或属于 ADMIN/CLERK 时抛出
     */
    private void requireNotSystemWorker(Long userId) {
        User u = userRepository.findById(userId).orElseThrow(() -> new BizException("员工不存在"));
        if (User.STATUS_DELETED.equals(u.getStatus())) throw new BizException("员工已删除");
        List<UserRole> urs = userRoleRepository.findByUserId(userId);
        for (UserRole ur : urs) {
            Role r = roleRepository.findById(ur.getRoleId()).orElse(null);
            if (r != null && ("ADMIN".equals(r.getCode()) || "CLERK".equals(r.getCode()))) {
                throw new BizException("系统管理员与录入员不允许被录入加班/调休数据");
            }
        }
    }

    // ==================== 查询 ====================

    /**
     * 分页查询加班记录。
     *
     * @param q 查询条件（日期区间、姓名模糊、部门、用户 ID、分页参数）；为 null 时按无条件处理
     * @return 分页结果，按日期倒序、同日按 ID 倒序
     */
    @Transactional(readOnly = true)
    public PageResult<OvertimeDto.OvertimeResponse> query(OvertimeDto.QueryRequest q) {
        if (q == null) q = new OvertimeDto.QueryRequest();
        // 页码/页大小做边界收敛，防止前端传超大 size 拖垮数据库
        int page = Math.max(q.getPage(), 1);
        int size = Math.min(Math.max(q.getSize(), 1), 200);
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "date", "id"));
        Page<OvertimeRecord> p = overtimeRepository.findAll(buildSpec(q), pageable);
        Map<Long, User> userMap = loadUserMap(p.getContent());
        Page<OvertimeDto.OvertimeResponse> mapped = p.map(r -> toResponse(r, userMap.get(r.getUserId())));
        PageResult<OvertimeDto.OvertimeResponse> res = PageResult.of(mapped);
        // 分页参数对外以 1 起始
        res.setPage(page);
        return res;
    }

    /**
     * 导出用查询：与列表相同条件，不分页；条件全空则导出全表。
     *
     * @param q 查询条件；为 null 或条件全空时导出全部
     * @return 待导出的记录列表，排序与列表页一致
     */
    @Transactional(readOnly = true)
    public List<OvertimeDto.OvertimeResponse> queryForExport(OvertimeDto.QueryRequest q) {
        if (q == null) q = new OvertimeDto.QueryRequest();
        List<OvertimeRecord> records;
        if (q.isEmpty()) {
            records = overtimeRepository.findAllByOrderByDateDescIdDesc();
        } else {
            records = overtimeRepository.findAll(buildSpec(q),
                    Sort.by(Sort.Direction.DESC, "date").and(Sort.by(Sort.Direction.DESC, "id")));
        }
        Map<Long, User> userMap = loadUserMap(records);
        return records.stream()
                .map(r -> toResponse(r, userMap.get(r.getUserId())))
                .collect(Collectors.toList());
    }

    /**
     * 按查询条件动态组装 JPA 查询条件。
     *
     * <p>姓名 / 部门是用户表的字段，无法直接对加班表做 SQL 过滤，因此先在
     * {@link #matchUserIds} 中把姓名部门翻译成用户 ID 集合，再用 IN 条件过滤。
     *
     * @param q 查询条件
     * @return 组装好的动态查询条件
     */
    private Specification<OvertimeRecord> buildSpec(OvertimeDto.QueryRequest q) {
        Set<Long> matchedUserIds = matchUserIds(q);
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            if (q.getFrom() != null) ps.add(cb.greaterThanOrEqualTo(root.get("date"), q.getFrom()));
            if (q.getTo() != null) ps.add(cb.lessThanOrEqualTo(root.get("date"), q.getTo()));
            if (q.getUserId() != null) ps.add(cb.equal(root.get("userId"), q.getUserId()));
            else if (matchedUserIds != null) {
                // 有筛选条件但没匹配到任何员工时，必须用恒假条件而不是空 IN，否则会查出全表
                if (matchedUserIds.isEmpty()) ps.add(cb.disjunction());
                else ps.add(root.get("userId").in(matchedUserIds));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    /**
     * 按姓名（模糊）与部门（模糊）筛选用户 ID。
     *
     * <p>返回值有语义区分：无条件返回 null（表示「不过滤」），有条件但没匹配到返回空集合（表示「查无结果」），
     * 调用方据此决定是跳过该条件还是加恒假条件。
     *
     * @param q 查询条件
     * @return 匹配的用户 ID 集合；无姓名/部门条件时返回 null
     */
    private Set<Long> matchUserIds(OvertimeDto.QueryRequest q) {
        String name = StringUtils.hasText(q.getName()) ? q.getName().trim() : null;
        String dept = StringUtils.hasText(q.getDepartment()) ? q.getDepartment().trim() : null;
        if (name == null && dept == null) return null;
        return userRepository.findByStatusNot(User.STATUS_DELETED).stream()
                .filter(u -> name == null || (u.getName() != null && u.getName().contains(name)))
                .filter(u -> dept == null || (u.getDepartment() != null && u.getDepartment().contains(dept)))
                .map(User::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 批量加载记录关联的用户，避免逐条查询造成 N+1。
     *
     * @param records 加班记录列表
     * @return 「用户 ID → 用户」映射
     */
    private Map<Long, User> loadUserMap(List<OvertimeRecord> records) {
        Set<Long> ids = records.stream().map(OvertimeRecord::getUserId).collect(Collectors.toSet());
        if (ids.isEmpty()) return new HashMap<>();
        Map<Long, User> map = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> map.put(u.getId(), u));
        return map;
    }

    private OvertimeDto.OvertimeResponse toResponse(OvertimeRecord r, User u) {
        OvertimeDto.OvertimeResponse res = new OvertimeDto.OvertimeResponse();
        res.setId(r.getId());
        res.setDate(r.getDate());
        res.setUserId(r.getUserId());
        res.setUserName(u != null ? u.getName() : null);
        res.setDepartment(u != null ? u.getDepartment() : null);
        res.setDayType(r.getDayType());
        res.setDayTypeLabel(dayTypeLabel(r.getDayType()));
        String mode = defaultMode(r.getRecordMode());
        res.setMode(mode);
        res.setModeLabel(modeLabel(mode));
        res.setStartTime(r.getStartTime());
        res.setEndTime(r.getEndTime());
        res.setClockInTime(r.getClockInTime());
        res.setHours(scale2(r.getHours()));
        res.setRatio(r.getRatio());
        res.setConvertedHours(scale2(r.getConvertedHours()));
        res.setHolidayName(r.getHolidayName());
        res.setRemark(r.getRemark());
        res.setStatus(r.getStatus());
        return res;
    }

    // ==================== 日类型与系数判定 ====================

    /**
     * 根据节假日日历自动判定日类型与转调休系数。
     * 判定链由 HolidayService.resolve 统一维护：人工维护日历 &gt; 系统官方规则 &gt; 周末兜底。
     */
    /**
     * 解析指定日期的日类型与折算系数。
     *
     * @param date 加班日期
     * @return 日类型与系数
     * @throws BizException 日期为空时抛出
     */
    @Transactional(readOnly = true)
    public OvertimeDto.DayResolve resolveDay(LocalDate date) {
        if (date == null) throw new BizException("日期不能为空");
        HolidayDto.ResolveResult resolved = holidayService.resolve(date);
        String dayType = StringUtils.hasText(resolved.getDayType()) ? resolved.getDayType() : resolved.getType();
        if (!StringUtils.hasText(dayType)) dayType = OvertimeRecord.DAY_WORKDAY;
        BigDecimal ratio = resolved.getRatio() != null ? resolved.getRatio() : ratioOf(dayType);
        String holidayName = StringUtils.hasText(resolved.getHolidayName())
                ? resolved.getHolidayName() : resolved.getName();
        return new OvertimeDto.DayResolve(date, dayType, dayTypeLabel(dayType), ratio, holidayName);
    }

    /**
     * 日类型转中文名。
     *
     * <p>末尾对 HOLIDAY / ADJUSTED 的兼容分支是为了展示历史数据——
     * 老版本加班模块用过这两个编码，现在统一收敛为 LEGAL / WORKDAY / RESTDAY。
     *
     * @param dayType 日类型
     * @return 中文名称；无法识别时原样返回
     */
    public static String dayTypeLabel(String dayType) {
        if (OvertimeRecord.DAY_LEGAL.equals(dayType)) return "法定节假日";
        if (OvertimeRecord.DAY_RESTDAY.equals(dayType)) return "休息日";
        if (OvertimeRecord.DAY_WORKDAY.equals(dayType)) return "法定工作日";
        // 历史数据兼容
        if ("HOLIDAY".equals(dayType)) return "法定节假日";
        if ("ADJUSTED".equals(dayType)) return "补班日";
        return dayType;
    }

    /**
     * 按日类型取折算系数：法定工作日与补班日 0.5，休息日与法定节假日 1。
     *
     * @param dayType 日类型
     * @return 折算系数
     */
    public static BigDecimal ratioOf(String dayType) {
        if (OvertimeRecord.DAY_WORKDAY.equals(dayType) || "ADJUSTED".equals(dayType)) return new BigDecimal("0.5");
        return BigDecimal.ONE;
    }

    // ==================== 录入模式 ====================

    /**
     * 规范化录入模式：空值兜底为加班转休，并统一转大写（兼容前端传小写或历史数据为空）。
     *
     * @param mode 原始模式值，可为 null
     * @return 规范化后的模式：OVERTIME 或 MANUAL
     */
    public static String defaultMode(String mode) {
        return StringUtils.hasText(mode) ? mode.trim().toUpperCase(Locale.ROOT) : OvertimeRecord.MODE_OVERTIME;
    }

    /**
     * 模式转中文名。
     *
     * @param mode 模式值
     * @return 中文名：「其他转调休」或「加班转调休」
     */
    public static String modeLabel(String mode) {
        return OvertimeRecord.MODE_MANUAL.equals(defaultMode(mode)) ? MODE_LABEL_MANUAL : MODE_LABEL_OVERTIME;
    }

    /**
     * 判断是否为「其他转休」模式（直接填转休时长，不计算加班时长与系数）。
     *
     * @param mode 模式值
     * @return 是其他转休返回 true
     */
    public static boolean isManual(String mode) {
        return OvertimeRecord.MODE_MANUAL.equals(defaultMode(mode));
    }

    /**
     * 解析「模式」列：加班转调休 / 加班转休 / OVERTIME → OVERTIME；其他转调休 / 其他转休 / MANUAL → MANUAL。
     * 空值缺省为 OVERTIME；无法识别时抛业务异常。
     */
    /**
     * 解析「模式」列：兼容中文标签、英文编码与简写（如「加班」「其他」），空值缺省为 OVERTIME。
     *
     * @param raw 原始模式文本，可为空
     * @return 规范化后的模式：OVERTIME 或 MANUAL
     * @throws BizException 无法识别的模式值时抛出
     */
    public static String parseMode(String raw) {
        if (!StringUtils.hasText(raw)) return OvertimeRecord.MODE_OVERTIME;
        String s = raw.trim();
        String upper = s.toUpperCase(Locale.ROOT);
        if (OvertimeRecord.MODE_MANUAL.equals(upper) || MODE_LABEL_MANUAL.equals(s)
                || "其他转休".equals(s) || "其他".equals(s)) {
            return OvertimeRecord.MODE_MANUAL;
        }
        if (OvertimeRecord.MODE_OVERTIME.equals(upper) || MODE_LABEL_OVERTIME.equals(s)
                || "加班转休".equals(s) || "加班".equals(s)) {
            return OvertimeRecord.MODE_OVERTIME;
        }
        throw new BizException("模式不正确：" + s + "（应为 " + MODE_LABEL_OVERTIME + " 或 " + MODE_LABEL_MANUAL + "）");
    }

    // ==================== 录入 ====================

    /**
     * 批量录入：同一人一次可录多条；任一条失败则整体回滚（方法级事务保证）。
     *
     * @param inputs     录入行列表
     * @param operatorId 操作人 ID，写入 createdBy
     * @param source     录入来源，manual / import
     * @return 已保存的加班记录列表
     * @throws BizException 列表为空，或任一行校验失败（异常信息带行号）
     */
    @Transactional
    public List<OvertimeRecord> createBatch(List<OvertimeDto.RecordInput> inputs, Long operatorId, String source) {
        if (inputs == null || inputs.isEmpty()) throw new BizException("请至少录入一条加班记录");
        List<OvertimeRecord> saved = new ArrayList<>();
        Map<String, Integer> perUserDay = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            OvertimeDto.RecordInput in = inputs.get(i);
            try {
                OvertimeRecord r = createOne(in, operatorId, source);
                saved.add(r);
                String key = r.getUserId() + "|" + r.getDate();
                perUserDay.merge(key, 1, Integer::sum);
            } catch (BizException e) {
                // 包装行号后继续向上抛，由事务回滚整批，避免只录进一部分
                throw new BizException("第" + (i + 1) + "条：" + e.getMessage());
            } catch (Exception e) {
                throw new BizException("第" + (i + 1) + "条：" + (e.getMessage() == null ? "录入失败" : e.getMessage()));
            }
        }
        String detail = "新增 " + saved.size() + " 条；" + perUserDay.entrySet().stream().map(e -> {
            String[] parts = e.getKey().split("\\|");
            String uname = userRepository.findById(Long.valueOf(parts[0])).map(User::getName).orElse("员工#" + parts[0]);
            return "员工 " + uname + "，日期 " + parts[1] + "（" + e.getValue() + " 条）";
        }).collect(Collectors.joining("；"));
        auditLogService.log(AuditLog.MODULE_OVERTIME, "录入加班", "加班转调休录入", detail);
        return saved;
    }

    /**
     * 单条录入（手工新增）。
     *
     * @param req        录入内容
     * @param operatorId 操作人 ID
     * @return 已保存的加班记录
     * @throws BizException 参数缺失、员工不存在或状态异常时抛出
     */
    @Transactional
    public OvertimeRecord create(OvertimeDto.RecordInput req, Long operatorId) {
        OvertimeRecord r = createOne(req, operatorId, OvertimeRecord.SOURCE_MANUAL);
        User u = userRepository.findById(r.getUserId()).orElse(null);
        auditLogService.log(AuditLog.MODULE_OVERTIME, "录入加班",
                u != null ? u.getName() : ("员工#" + r.getUserId()),
                "新增 1 条，员工 " + (u != null ? u.getName() : r.getUserId()) + "，日期 " + r.getDate());
        return r;
    }

    /**
     * 录入单条记录的核心逻辑：校验 → 判定日类型 → 计算时长 → 落库 → 入账调休余额。
     *
     * <p>两种模式在此分叉：加班转休按「起止时间 × 系数」折算，其他转休直接采用输入的转休时长。
     *
     * @param req        录入内容
     * @param operatorId 操作人 ID
     * @param source     录入来源，manual / import
     * @return 已保存的加班记录
     * @throws BizException 任一项校验不通过时抛出
     */
    private OvertimeRecord createOne(OvertimeDto.RecordInput req, Long operatorId, String source) {
        if (req == null) throw new BizException("录入内容不能为空");
        if (req.getUserId() == null) throw new BizException("请选择员工");
        if (req.getDate() == null) throw new BizException("请选择加班日期");
        User user = userRepository.findById(req.getUserId()).orElseThrow(() -> new BizException("员工不存在"));
        if (!User.STATUS_ACTIVE.equals(user.getStatus())) throw new BizException("员工状态异常，无法录入加班");
        requireNotSystemWorker(req.getUserId());

        String mode = defaultMode(req.getMode());
        OvertimeDto.DayResolve day = resolveDay(req.getDate());

        OvertimeRecord r = new OvertimeRecord();
        r.setUserId(user.getId());
        r.setDate(req.getDate());
        r.setRecordMode(mode);
        r.setDayType(day.getDayType());
        r.setClockInTime(req.getClockInTime());
        r.setRemark(req.getRemark());
        r.setSource(source != null ? source : OvertimeRecord.SOURCE_MANUAL);
        r.setStatus(OvertimeRecord.STATUS_CONFIRMED);
        r.setCreatedBy(operatorId);

        BigDecimal hours;
        BigDecimal converted;
        String balanceDetail;
        if (isManual(mode)) {
            // 其他转休：不计算加班时长与系数，转休时长直接取输入值
            if (req.getConvertedHours() == null) throw new BizException("请填写转休时长");
            if (req.getConvertedHours().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException("转休时长不能为负数");
            }
            converted = req.getConvertedHours().setScale(2, RoundingMode.HALF_UP);
            hours = BigDecimal.ZERO;
            r.setStartTime(null);
            r.setEndTime(null);
            r.setHours(hours);
            r.setRatio(null);
            r.setConvertedHours(converted);
            r.setHolidayName(null);
            balanceDetail = "其他转休入账 " + req.getDate() + " " + converted + "h";
        } else {
            LocalTime start = req.getStartTime();
            LocalTime end = req.getEndTime();
            validateTime(start, end);
            // 加班转休模式下打卡时间为必填项，用于与考勤系统核对
            if (req.getClockInTime() == null) throw new BizException("请填写打卡时间");
            hours = computeHours(start, end);
            converted = hours.multiply(day.getRatio()).setScale(2, RoundingMode.HALF_UP);
            r.setStartTime(start);
            r.setEndTime(end);
            r.setHours(hours);
            r.setRatio(day.getRatio());
            r.setConvertedHours(converted);
            r.setHolidayName(day.getHolidayName());
            balanceDetail = "加班入账 " + req.getDate() + " " + hours + "h×" + day.getRatio() + "=" + converted + "h";
        }
        // 加班转休模式：同一人同一天相同起始时间禁止重复录入
        if (!isManual(mode)) {
            List<OvertimeRecord> dup = overtimeRepository.findOverlappingStart(user.getId(), req.getDate(), req.getStartTime());
            if (!dup.isEmpty()) {
                throw new BizException("该员工在 " + req.getDate() + " 已存在起始时间 " + req.getStartTime() + " 的加班记录，不能重复录入");
            }
        }
        OvertimeRecord saved = overtimeRepository.save(r);

        balanceService.earn(user.getId(), converted, "OVERTIME", saved.getId(),
                balanceDetail + (StringUtils.hasText(req.getRemark()) ? "；备注：" + req.getRemark() : ""));
        return saved;
    }

    // ==================== 编辑 / 删除 ====================

    /**
     * 编辑加班记录，并按新旧转休时长的差额同步调整调休余额。
     *
     * @param id         记录 ID
     * @param req        编辑内容
     * @param operatorId 操作人 ID
     * @return 保存后的记录
     * @throws BizException 记录不存在、已作废，或员工/时间校验不通过时抛出
     */
    @Transactional
    public OvertimeRecord update(Long id, OvertimeDto.UpdateRequest req, Long operatorId) {
        OvertimeRecord r = overtimeRepository.findById(id).orElseThrow(() -> new BizException("加班记录不存在"));
        if (OvertimeRecord.STATUS_VOID.equals(r.getStatus())) throw new BizException("已作废的记录不可编辑");
        if (req == null) throw new BizException("提交内容不能为空");
        if (req.getDate() == null) throw new BizException("请选择加班日期");
        Long targetUserId = req.getUserId() != null ? req.getUserId() : r.getUserId();
        User user = userRepository.findById(targetUserId).orElseThrow(() -> new BizException("员工不存在"));
        if (!User.STATUS_ACTIVE.equals(user.getStatus())) throw new BizException("员工状态异常，无法编辑加班");
        requireNotSystemWorker(targetUserId);

        // 未传模式时沿用原记录的模式
        String mode = req.getMode() != null ? parseMode(req.getMode()) : defaultMode(r.getRecordMode());

        String before = describe(r);
        BigDecimal oldConverted = nz(r.getConvertedHours());
        Long oldUserId = r.getUserId();
        OvertimeDto.DayResolve day = resolveDay(req.getDate());

        BigDecimal hours;
        BigDecimal converted;
        if (isManual(mode)) {
            BigDecimal input = req.getConvertedHours() != null ? req.getConvertedHours() : r.getConvertedHours();
            if (input == null) throw new BizException("请填写转休时长");
            if (input.compareTo(BigDecimal.ZERO) < 0) throw new BizException("转休时长不能为负数");
            converted = input.setScale(2, RoundingMode.HALF_UP);
            hours = BigDecimal.ZERO;
        } else {
            validateTime(req.getStartTime(), req.getEndTime());
            // 加班转休模式下打卡时间为必填项，用于与考勤系统核对
            if (req.getClockInTime() == null) throw new BizException("请填写打卡时间");
            hours = computeHours(req.getStartTime(), req.getEndTime());
            converted = hours.multiply(day.getRatio()).setScale(2, RoundingMode.HALF_UP);
        }

        r.setUserId(user.getId());
        r.setDate(req.getDate());
        r.setRecordMode(mode);
        r.setDayType(day.getDayType());
        r.setStartTime(isManual(mode) ? null : req.getStartTime());
        r.setEndTime(isManual(mode) ? null : req.getEndTime());
        r.setClockInTime(req.getClockInTime());
        r.setHours(hours);
        r.setRatio(isManual(mode) ? null : day.getRatio());
        r.setConvertedHours(converted);
        r.setHolidayName(isManual(mode) ? null : day.getHolidayName());
        r.setRemark(req.getRemark());
        // 加班转休模式：同一人同一天相同起始时间禁止重复录入（排除正在编辑的自身记录）
        if (!isManual(mode)) {
            List<OvertimeRecord> dup = overtimeRepository.findOverlappingStart(user.getId(), req.getDate(), req.getStartTime());
            boolean selfOnly = dup.stream().allMatch(d -> d.getId().equals(id));
            if (!dup.isEmpty() && !selfOnly) {
                throw new BizException("该员工在 " + req.getDate() + " 已存在起始时间 " + req.getStartTime() + " 的加班记录，不能重复录入");
            }
        }
        OvertimeRecord saved = overtimeRepository.save(r);

        adjustBalanceForUpdate(oldUserId, r.getUserId(), r.getId(), oldConverted, converted);

        String after = describe(saved);
        auditLogService.logChange(AuditLog.MODULE_OVERTIME, "编辑加班",
                user.getName() + " " + req.getDate(), "编辑加班记录 #" + id, before, after, AuditLog.LEVEL_WARN);
        return saved;
    }

    /**
     * 删除加班记录；若该记录此前已折算入账，则先等额冲减调休余额再物理删除。
     *
     * @param id         记录 ID
     * @param operatorId 操作人 ID
     * @throws BizException 记录不存在时抛出
     */
    @Transactional
    public void remove(Long id, Long operatorId) {
        OvertimeRecord r = overtimeRepository.findById(id).orElseThrow(() -> new BizException("加班记录不存在"));
        BigDecimal converted = nz(r.getConvertedHours());
        if (converted.compareTo(BigDecimal.ZERO) > 0 && !OvertimeRecord.STATUS_VOID.equals(r.getStatus())) {
            balanceService.spend(r.getUserId(), converted, "OVERTIME", r.getId(),
                    "删除加班记录 #" + id + "，冲减调休 " + converted + "h");
        }
        String before = describe(r);
        overtimeRepository.delete(r);
        User u = userRepository.findById(r.getUserId()).orElse(null);
        auditLogService.logChange(AuditLog.MODULE_OVERTIME, "删除加班",
                (u != null ? u.getName() : "员工#" + r.getUserId()) + " " + r.getDate(),
                "删除加班记录 #" + id, before, "", AuditLog.LEVEL_WARN);
    }

    /**
     * 按差额调整余额；若编辑时更换了员工，则先对原员工全额冲减、再给新员工全额补记。
     *
     * <p>换人场景不能走「差额」逻辑，因为差额要在两个人的账户之间转移，
     * 用差额会导致原员工少冲减、新员工少入账。
     *
     * @param oldUserId    原归属员工 ID
     * @param newUserId    新归属员工 ID
     * @param recordId     加班记录 ID，作为余额流水的关联 ID
     * @param oldConverted 编辑前的转休时长
     * @param newConverted 编辑后的转休时长
     */
    private void adjustBalanceForUpdate(Long oldUserId, Long newUserId, Long recordId,
                                        BigDecimal oldConverted, BigDecimal newConverted) {
        // 必须先判断是否换人：换人时即使转休时长完全相同，也要在两个账户之间搬账。
        // 若先用「差额为 0 就 return」短路，换人场景会被直接跳过，
        // 表现为「系统提示更新成功，但两个人的余额都没变」。
        if (oldUserId != null && !oldUserId.equals(newUserId)) {
            // 换了员工：原员工全额冲减，新员工按新值补记
            if (oldConverted.compareTo(BigDecimal.ZERO) != 0) {
                balanceService.spend(oldUserId, oldConverted, "OVERTIME", recordId,
                        "编辑加班（员工变更），冲减调休 -" + oldConverted + "h");
            }
            if (newConverted.compareTo(BigDecimal.ZERO) != 0) {
                balanceService.earn(newUserId, newConverted, "OVERTIME", recordId,
                        "编辑加班（员工变更），补记调休 +" + newConverted + "h");
            }
            return;
        }
        // 同一个人：按差额补记/冲减；差额为 0 说明确实没有变化，不产生流水
        BigDecimal diff = newConverted.subtract(oldConverted).setScale(2, RoundingMode.HALF_UP);
        if (diff.compareTo(BigDecimal.ZERO) == 0) return;
        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.earn(newUserId, diff, "OVERTIME", recordId, "编辑加班，补记调休 +" + diff + "h");
        } else {
            balanceService.spend(newUserId, diff.abs(), "OVERTIME", recordId,
                    "编辑加班，冲减调休 -" + diff.abs() + "h");
        }
    }

    private String describe(OvertimeRecord r) {
        return "日期=" + r.getDate()
                + "; 模式=" + modeLabel(r.getRecordMode())
                + "; 时间=" + r.getStartTime() + "-" + r.getEndTime()
                + "; 打卡=" + (r.getClockInTime() == null ? "" : r.getClockInTime())
                + "; 加班时长=" + nz(r.getHours()) + "h"
                + "; 系数=" + nz(r.getRatio())
                + "; 转调休时长=" + nz(r.getConvertedHours()) + "h"
                + "; 备注=" + (r.getRemark() == null ? "" : r.getRemark());
    }

    // ==================== 时长计算 ====================

    /**
     * 计算加班时长 = 结束时间 − 开始时间（小时，两位小数），不做时段扣除、不封顶。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 加班小时数，保留两位小数（四舍五入）
     */
    public BigDecimal computeHours(LocalTime start, LocalTime end) {
        // 先换算成分钟再除 60，避免直接用纳秒/秒做除法产生浮点精度问题
        long minutes = Duration.between(start, end).toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /**
     * 校验起止时间：非空、以 30 分钟为一跳（分钟为 00 或 30、秒为 0）、结束必须晚于开始。
     *
     * <p>30 分钟一跳是业务约定，与前端时间选择器的步长保持一致，避免出现 18:07 这类无法核对的时间。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @throws BizException 任一项不满足时抛出
     */
    private void validateTime(LocalTime start, LocalTime end) {
        if (start == null) throw new BizException("请选择开始时间");
        if (end == null) throw new BizException("请选择结束时间");
        if (start.getMinute() % 30 != 0 || start.getSecond() != 0) {
            throw new BizException("开始时间须为整点或半点（分钟为 00 或 30）");
        }
        if (end.getMinute() % 30 != 0 || end.getSecond() != 0) {
            throw new BizException("结束时间须为整点或半点（分钟为 00 或 30）");
        }
        if (!end.isAfter(start)) throw new BizException("结束时间必须晚于开始时间");
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== Excel 导入 / 导出 / 模板 ====================

    /**
     * 导入 Excel。列顺序：员工姓名/工号、加班日期、模式、开始时间、结束时间、打卡时间、转休时长、备注。
     * <ul>
     *   <li>模式为「其他转调休」时，只需填员工、日期、模式、转休时长、备注；</li>
     *   <li>模式为「加班转调休」（缺省）时，需填开始时间、结束时间，转休时长由系统计算。</li>
     * </ul>
     * 返回 { success, failed, errors } —— 失败行收集错误原因，成功行照常入库。
     *
     * @param file       上传的 Excel 文件
     * @param operatorId 操作人 ID
     * @return 导入结果（成功数、失败数、逐行错误）
     * @throws BizException 文件为空或 Excel 无法解析时抛出
     */
    @Transactional
    public OvertimeDto.ImportResult importExcel(MultipartFile file, Long operatorId) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的文件");
        OvertimeDto.ImportResult result = new OvertimeDto.ImportResult();
        Map<String, User> byName = new HashMap<>();
        Map<String, User> byUsername = new HashMap<>();
        for (User u : userRepository.findByStatusNot(User.STATUS_DELETED)) {
            if (StringUtils.hasText(u.getName())) byName.put(u.getName().trim(), u);
            if (StringUtils.hasText(u.getUsername())) byUsername.put(u.getUsername().trim().toLowerCase(Locale.ROOT), u);
        }

        List<List<String>> rows;
        try (InputStream in = file.getInputStream();
             Workbook wb = WorkbookFactory.create(in)) {
            rows = readRows(wb);
        } catch (IOException e) {
            throw new BizException("Excel 读取失败：" + e.getMessage());
        } catch (Exception e) {
            throw new BizException("Excel 解析失败：" + e.getMessage());
        }

        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 1;
            List<String> row = rows.get(i);
            // 空行直接跳过；表头只在第一行做判断，避免把正文里恰好含「姓名」的内容误判为表头
            if (isBlankRow(row)) continue;
            if (i == 0 && isHeaderRow(row)) continue;
            try {
                String who = cell(row, 0);
                if (!StringUtils.hasText(who)) throw new BizException("员工姓名为空");
                User u = byName.get(who.trim());
                if (u == null) u = byUsername.get(who.trim().toLowerCase(Locale.ROOT));
                if (u == null) throw new BizException("未找到员工：" + who);
                requireNotSystemWorker(u.getId());

                LocalDate date = parseDate(cell(row, 1));
                String mode = parseMode(cell(row, 2));
                LocalTime clockInTime = parseTimeOrNull(cell(row, 5));
                OvertimeDto.RecordInput in;
                if (isManual(mode)) {
                    // 其他转休：只需员工、日期、模式、转休时长、备注
                    in = new OvertimeDto.RecordInput(u.getId(), date, mode, null, null, clockInTime,
                            parseDecimal(cell(row, 6)), cell(row, 7));
                } else {
                    LocalTime start = parseTime(cell(row, 3));
                    LocalTime end = parseTime(cell(row, 4));
                    in = new OvertimeDto.RecordInput(u.getId(), date, mode, start, end, clockInTime, null, cell(row, 7));
                }
                createOne(in, operatorId, OvertimeRecord.SOURCE_IMPORT);
                result.setSuccess(result.getSuccess() + 1);
            } catch (BizException e) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add("第" + rowNo + "行：" + e.getMessage());
            } catch (Exception e) {
                result.setFailed(result.getFailed() + 1);
                result.getErrors().add("第" + rowNo + "行：" + (e.getMessage() == null ? "导入失败" : e.getMessage()));
            }
        }
        // 一条都没读到（既无成功也无失败）说明表里没有可识别的数据行，
        // 必须给出明确原因，否则前端只会显示「成功 0 条，失败 0 条」让人无从排查。
        if (result.getSuccess() == 0 && result.getFailed() == 0) {
            throw new BizException("未读取到任何数据行：请确认数据填写在第一个工作表「导入模板」中，"
                    + "第一列为员工姓名或工号且不能为空；「填写说明」工作表仅供参考，填在那里不会被导入。");
        }
        auditLogService.log(AuditLog.MODULE_OVERTIME, "导入加班", "加班转调休录入",
                "导入文件 " + file.getOriginalFilename() + "，成功 " + result.getSuccess()
                        + " 条，失败 " + result.getFailed() + " 条");
        return result;
    }

    /**
     * 导出查询结果（列与列表一致）。
     *
     * @param rows 待导出的记录列表
     * @return xlsx 文件字节流
     * @throws BizException 生成失败时抛出
     */
    public byte[] exportExcel(List<OvertimeDto.OvertimeResponse> rows) {
        try (Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("加班转调休录入记录");
            CellStyle headStyle = headStyle(wb);
            Row head = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEAD.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(EXPORT_HEAD[i]);
                c.setCellStyle(headStyle);
            }
            for (int i = 0; i < rows.size(); i++) {
                OvertimeDto.OvertimeResponse r = rows.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(r.getDate() == null ? "" : r.getDate().format(DATE_FMT));
                row.createCell(1).setCellValue(r.getUserName() == null ? "" : r.getUserName());
                row.createCell(2).setCellValue(r.getDepartment() == null ? "" : r.getDepartment());
                row.createCell(3).setCellValue(r.getModeLabel() == null ? "" : r.getModeLabel());
                row.createCell(4).setCellValue(r.getDayTypeLabel() == null ? "" : r.getDayTypeLabel());
                row.createCell(5).setCellValue(r.getStartTime() == null ? "" : r.getStartTime().format(TIME_FMT));
                row.createCell(6).setCellValue(r.getEndTime() == null ? "" : r.getEndTime().format(TIME_FMT));
                row.createCell(7).setCellValue(r.getClockInTime() == null ? "" : r.getClockInTime().format(TIME_FMT));
                row.createCell(8).setCellValue(nz(r.getHours()).doubleValue());
                row.createCell(9).setCellValue(nz(r.getConvertedHours()).doubleValue());
                row.createCell(10).setCellValue(r.getRemark() == null ? "" : r.getRemark());
            }
            for (int i = 0; i < EXPORT_HEAD.length; i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 3840) sheet.setColumnWidth(i, 3840); // 最小 12 字符宽
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("导出失败：" + e.getMessage());
        }
    }

    /**
     * 生成导入模板：表头 + 两行示例（加班转调休 / 其他转调休），
     * 并新增「填写说明」工作表。模式列保留下拉；时间列不再做下拉（避免 48 个选项超长导致 Excel 损坏）。
     *
     * @return xlsx 文件字节流
     * @throws BizException 生成失败时抛出
     */
    public byte[] template() {
        try (Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headStyle = headStyle(wb);
            Sheet sheet = wb.createSheet("导入模板");
            Row head = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEAD.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(TEMPLATE_HEAD[i]);
                c.setCellStyle(headStyle);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("张三");
            sample.createCell(1).setCellValue(LocalDate.now().format(DATE_FMT));
            sample.createCell(2).setCellValue(MODE_LABEL_OVERTIME);
            sample.createCell(3).setCellValue("18:30");
            sample.createCell(4).setCellValue("20:30");
            sample.createCell(5).setCellValue("20:30");
            sample.createCell(6).setCellValue("");
            sample.createCell(7).setCellValue("项目赶工");
            // 其他转休示例行：只需员工姓名 / 日期 / 模式 / 转休时长 / 备注
            Row sample2 = sheet.createRow(2);
            sample2.createCell(0).setCellValue("李四");
            sample2.createCell(1).setCellValue(LocalDate.now().format(DATE_FMT));
            sample2.createCell(2).setCellValue(MODE_LABEL_MANUAL);
            sample2.createCell(3).setCellValue("");
            sample2.createCell(4).setCellValue("");
            sample2.createCell(5).setCellValue("");
            sample2.createCell(6).setCellValue(4);
            sample2.createCell(7).setCellValue("历史结转补休");
            // 模式列下拉（不超过 255 字符）
            DataValidationHelper helper = sheet.getDataValidationHelper();
            CellRangeAddressList modeRange = new CellRangeAddressList(1, 500, 2, 2);
            sheet.addValidationData(helper.createValidation(
                    helper.createExplicitListConstraint(new String[]{MODE_LABEL_OVERTIME, MODE_LABEL_MANUAL}),
                    modeRange));
            // 列宽：最小 12 字符
            for (int i = 0; i < TEMPLATE_HEAD.length; i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 3840) sheet.setColumnWidth(i, 3840);
            }

            // 填写说明工作表
            Sheet guide = wb.createSheet("填写说明");
            String[] guideHead = {"列名", "必填", "格式说明"};
            Row gHead = guide.createRow(0);
            for (int i = 0; i < guideHead.length; i++) {
                Cell c = gHead.createCell(i);
                c.setCellValue(guideHead[i]);
                c.setCellStyle(headStyle);
            }
            String[][] guideRows = {
                    {"员工姓名/工号", "是", "与系统用户管理中姓名或账号完全一致"},
                    {"加班日期", "是", "yyyy-MM-dd，例如 2026-01-01"},
                    {"模式", "是", "加班转调休 或 其他转调休"},
                    {"开始时间", "条件必填", "模式=加班转调休时必填；HH:mm 24 小时制，例如 18:30"},
                    {"结束时间", "条件必填", "模式=加班转调休时必填；HH:mm，必须晚于开始时间，例如 20:30"},
                    {"打卡时间", "条件必填", "模式=加班转调休时必填；HH:mm 精确到分钟，例如 20:30"},
                    {"转休时长", "条件必填", "模式=其他转调休时必填；正数，最多 2 位小数"},
                    {"备注", "条件必填", "模式=其他转调休时必填；加班转调休时选填"}
            };
            for (int i = 0; i < guideRows.length; i++) {
                Row r = guide.createRow(i + 1);
                for (int j = 0; j < guideRows[i].length; j++) r.createCell(j).setCellValue(guideRows[i][j]);
            }
            guide.autoSizeColumn(0);
            guide.autoSizeColumn(1);
            guide.autoSizeColumn(2);
            if (guide.getColumnWidth(0) < 3840) guide.setColumnWidth(0, 3840);
            if (guide.getColumnWidth(1) < 2560) guide.setColumnWidth(1, 2560);
            if (guide.getColumnWidth(2) < 7680) guide.setColumnWidth(2, 7680);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("模板生成失败：" + e.getMessage());
        }
    }

    /**
     * 创建表头单元格样式：加粗、居中、灰底。
     *
     * @param wb 工作簿
     * @return 表头样式
     */
    private CellStyle headStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * 读取第一个工作表的全部行，每格统一格式化为字符串（公式单元格会先求值）。
     *
     * <p>只读取前 {@code TEMPLATE_HEAD.length} 列，忽略用户自行追加的额外列。
     *
     * @param wb 工作簿
     * @return 行数据（每行是一个单元格字符串列表）
     */
    private List<List<String>> readRows(Workbook wb) {
        Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : wb.createSheet();
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            List<String> cells = new ArrayList<>();
            for (int j = 0; j < TEMPLATE_HEAD.length; j++) {
                Cell cell = row.getCell(j);
                cells.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    /**
     * 判断是否为全空行（用于跳过 Excel 中的空行与尾部空白）。
     *
     * @param row 行数据
     * @return 全空返回 true
     */
    private boolean isBlankRow(List<String> row) {
        return row.stream().allMatch(v -> v == null || v.isBlank());
    }

    /**
     * 判断是否为表头行：首列包含「姓名 / 工号 / 员工」之一即认为是表头。
     *
     * @param row 行数据
     * @return 是表头返回 true
     */
    private boolean isHeaderRow(List<String> row) {
        String c0 = cell(row, 0);
        return c0 != null && (c0.contains("姓名") || c0.contains("工号") || c0.contains("员工"));
    }

    /**
     * 安全取单元格值：越界、null 或空白统一返回 null，便于调用方用 StringUtils 判空。
     *
     * @param row 行数据
     * @param idx 列下标
     * @return 单元格文本；空则返回 null
     */
    private String cell(List<String> row, int idx) {
        if (idx >= row.size()) return null;
        String v = row.get(idx);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * 解析日期，依次尝试多种常见格式，最后兼容 Excel 的数值日期。
     *
     * @param raw 原始文本
     * @return 解析后的日期
     * @throws BizException 全部格式都不匹配时抛出
     */
    private LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException("加班日期为空");
        String s = raw.trim();
        for (DateTimeFormatter f : DATE_PATTERNS) {
            try {
                return LocalDate.parse(s, f);
            } catch (Exception ignore) {
                // 尝试下一种格式
            }
        }
        // Excel 数值日期：以 1899-12-30 为基准（兼容 Excel 的 1900 日期系统与闰年 bug）
        try {
            double d = Double.parseDouble(s);
            if (d > 0) return LocalDate.of(1899, 12, 30).plusDays((long) d);
        } catch (NumberFormatException ignore) {
            // 非法日期
        }
        throw new BizException("加班日期格式不正确：" + s + "（应为 yyyy-MM-dd）");
    }

    /**
     * 解析时间，空值返回 null（打卡时间为选填项）。
     *
     * @param raw 原始文本，可为空
     * @return 解析后的时间；输入为空时返回 null
     */
    private LocalTime parseTimeOrNull(String raw) {
        return StringUtils.hasText(raw) ? parseTime(raw) : null;
    }

    /**
     * 解析转休时长（小时），支持小数，保留两位。
     *
     * @param raw 原始文本
     * @return 转休时长
     * @throws BizException 为空或不是数字时抛出
     */
    private BigDecimal parseDecimal(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException("转休时长为空");
        try {
            return new BigDecimal(raw.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new BizException("转休时长格式不正确：" + raw.trim() + "（应为数字小时数）");
        }
    }

    /**
     * 解析时间：先按多种文本格式尝试（兼容中文全角冒号），再兼容 Excel 的小数时间。
     *
     * @param raw 原始文本
     * @return 解析后的时间
     * @throws BizException 为空或格式无法识别时抛出
     */
    private LocalTime parseTime(String raw) {
        if (!StringUtils.hasText(raw)) throw new BizException("开始/结束时间为空");
        String s = raw.trim().replace('：', ':');
        for (DateTimeFormatter f : TIME_PATTERNS) {
            try {
                return LocalTime.parse(s, f);
            } catch (Exception ignore) {
                // 尝试下一种格式
            }
        }
        // Excel 数值时间：以「一天的小数」表示，乘 24*60 换算成分钟后取整到分钟
        try {
            double d = Double.parseDouble(s);
            long minutes = Math.round(d * 24 * 60);
            return LocalTime.of((int) (minutes / 60) % 24, (int) (minutes % 60));
        } catch (NumberFormatException e) {
            throw new BizException("时间格式不正确：" + raw + "（应为 HH:mm）");
        }
    }
}

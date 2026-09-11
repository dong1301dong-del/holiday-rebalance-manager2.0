package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.LeaveDto;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.entity.LeaveUsageRecord;
import com.tiaoxiu.entity.Role;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.entity.UserMessage;
import com.tiaoxiu.entity.UserRole;
import com.tiaoxiu.repository.LeaveUsageRepository;
import com.tiaoxiu.repository.RoleRepository;
import com.tiaoxiu.repository.UserMessageRepository;
import com.tiaoxiu.repository.UserRepository;
import com.tiaoxiu.repository.UserRoleRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 调休使用记录：录入即扣减余额、允许透支并提醒、编辑按差额调整、作废恢复余额且物理数据保留。
 * <p>
 * <b>没有审批环节</b>：旧 leave_requests 的审批流程已彻底移除。
 */
@Service
public class LeaveService {

    private final LeaveUsageRepository leaveUsageRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserMessageRepository userMessageRepository;
    private final BalanceService balanceService;
    private final AuditLogService auditLogService;
    private final RecordConflictGuard conflictGuard;
    private final HolidayService holidayService;

    /**
     * 构造器注入。
     *
     * @param leaveUsageRepository   调休使用记录仓储
     * @param userRepository         用户仓储
     * @param userRoleRepository     用户角色绑定仓储
     * @param roleRepository         角色仓储
     * @param userMessageRepository  站内消息仓储（透支提醒）
     * @param balanceService         余额引擎（扣减 / 恢复调休余额）
     * @param auditLogService        审计日志服务
     * @param conflictGuard          记录时间区间冲突守卫
     * @param holidayService         节假日日历服务（判定休息日 / 节假日名称）
     */
    public LeaveService(LeaveUsageRepository leaveUsageRepository, UserRepository userRepository,
                        UserRoleRepository userRoleRepository, RoleRepository roleRepository,
                        UserMessageRepository userMessageRepository, BalanceService balanceService,
                        AuditLogService auditLogService, RecordConflictGuard conflictGuard,
                        HolidayService holidayService) {
        this.leaveUsageRepository = leaveUsageRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.userMessageRepository = userMessageRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.conflictGuard = conflictGuard;
        this.holidayService = holidayService;
    }

    // ==================== 权限校验 ====================

    /**
     * 校验目标用户是否为可被录入调休使用的普通员工。
     * <p>系统管理员（ADMIN）与录入员（CLERK）属于系统工作者，不参与调休余额计算。
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
                throw new BizException("系统管理员与录入员不允许被录入调休数据");
            }
        }
    }

    // ==================== 查询 ====================

    /**
     * 分页查询调休使用记录。
     *
     * <p>默认只返回正常记录，已作废的需显式传 {@code includeVoid=true}。
     *
     * @param q 查询条件（日期区间、姓名模糊、部门、用户 ID、是否含作废、分页）
     * @return 分页结果，按日期倒序、同日按 ID 倒序
     */
    @Transactional(readOnly = true)
    public Page<LeaveDto.LeaveUsageResponse> query(LeaveDto.QueryRequest q) {
        if (q == null) q = new LeaveDto.QueryRequest();
        int page = q.getPage() == null || q.getPage() < 1 ? 0 : q.getPage() - 1;
        int size = q.getSize() == null || q.getSize() < 1 ? 20 : Math.min(q.getSize(), 200);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date", "id"));

        // 姓名/部门是用户表字段，先翻译成用户 ID 集合再过滤；一个人都没匹配上时直接返回空页，避免查出全表
        Set<Long> scopedIds = null;
        if (q.getUserId() != null) {
            scopedIds = Set.of(q.getUserId());
        } else if (StringUtils.hasText(q.getName()) || StringUtils.hasText(q.getDepartment())) {
            scopedIds = matchUserIds(q.getName(), q.getDepartment());
            if (scopedIds.isEmpty()) return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        final Set<Long> ids = scopedIds;
        final LocalDate from = q.getFrom();
        final LocalDate to = q.getTo();
        final boolean includeVoid = Boolean.TRUE.equals(q.getIncludeVoid());

        Specification<LeaveUsageRecord> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (ids != null) ps.add(root.get("userId").in(ids));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("date"), from));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("date"), to));
            if (!includeVoid) ps.add(cb.equal(root.get("status"), LeaveUsageRecord.STATUS_NORMAL));
            return cb.and(ps.toArray(new Predicate[0]));
        };

        Page<LeaveUsageRecord> p = leaveUsageRepository.findAll(spec, pageable);
        Map<Long, User> userMap = loadUserMap(p.getContent().stream().map(LeaveUsageRecord::getUserId).toList());
        return p.map(r -> toResponse(r, userMap.get(r.getUserId())));
    }

    /**
     * 导出用：不分页，通过逐页翻页（每页 200 条）的方式取回全部符合条件的数据。
     *
     * <p>采用「循环翻页」而非一次性查全表，是为了复用 {@link #query} 的条件组装逻辑，
     * 同时避免单次查询把过多数据加载进内存。
     *
     * @param q 查询条件
     * @return 全部符合条件的记录
     */
    @Transactional(readOnly = true)
    public List<LeaveDto.LeaveUsageResponse> queryAll(LeaveDto.QueryRequest q) {
        if (q == null) q = new LeaveDto.QueryRequest();
        q.setPage(1);
        q.setSize(200);
        Page<LeaveDto.LeaveUsageResponse> first = query(q);
        List<LeaveDto.LeaveUsageResponse> all = new ArrayList<>(first.getContent());
        for (int p = 2; p <= first.getTotalPages(); p++) {
            LeaveDto.QueryRequest next = new LeaveDto.QueryRequest();
            next.setFrom(q.getFrom());
            next.setTo(q.getTo());
            next.setName(q.getName());
            next.setDepartment(q.getDepartment());
            next.setUserId(q.getUserId());
            next.setIncludeVoid(q.getIncludeVoid());
            next.setPage(p);
            next.setSize(200);
            all.addAll(query(next).getContent());
        }
        return all;
    }

    /**
     * 按姓名与部门（均为模糊匹配）筛选未删除用户的 ID。
     *
     * @param name       姓名关键字，可为 null
     * @param department 部门关键字，可为 null
     * @return 匹配的用户 ID 集合；无匹配时为空集合
     */
    private Set<Long> matchUserIds(String name, String department) {
        List<User> users = userRepository.findByStatusNot(User.STATUS_DELETED);
        String n = name == null ? null : name.trim();
        String d = department == null ? null : department.trim();
        return users.stream()
                .filter(u -> n == null || n.isEmpty() || (u.getName() != null && u.getName().contains(n)))
                .filter(u -> d == null || d.isEmpty() || (u.getDepartment() != null && u.getDepartment().contains(d)))
                .map(User::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 批量加载用户（按 ID 去重后逐个查询）。
     *
     * @param userIds 用户 ID 列表，可重复
     * @return 「用户 ID → 用户」映射，保持插入顺序
     */
    private Map<Long, User> loadUserMap(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return new LinkedHashMap<>();
        Map<Long, User> map = new LinkedHashMap<>();
        for (Long id : userIds) {
            if (id != null && !map.containsKey(id)) {
                userRepository.findById(id).ifPresent(u -> map.put(id, u));
            }
        }
        return map;
    }

    /**
     * 实体转响应 DTO，补充员工姓名 / 部门与状态中文名。
     *
     * @param r 调休使用记录实体
     * @param u 关联用户；为 null 时用「员工#ID」兜底展示
     * @return 响应 DTO
     */
    private static LeaveDto.LeaveUsageResponse toResponse(LeaveUsageRecord r, User u) {
        LeaveDto.LeaveUsageResponse res = new LeaveDto.LeaveUsageResponse();
        res.setId(r.getId());
        res.setDate(r.getDate());
        res.setUserId(r.getUserId());
        res.setUserName(u != null ? u.getName() : ("员工#" + r.getUserId()));
        res.setDepartment(u != null ? u.getDepartment() : null);
        res.setStartTime(r.getStartTime());
        res.setEndTime(r.getEndTime());
        res.setHours(r.getHours());
        res.setRemark(r.getRemark());
        res.setStatus(r.getStatus());
        res.setStatusLabel(LeaveUsageRecord.STATUS_VOID.equals(r.getStatus()) ? "已作废" : "正常");
        return res;
    }

    // ==================== 录入 ====================

    /**
     * 批量录入：先按人汇总本次扣减量并整体校验余额，再逐条写库并扣减。
     *
     * <p>必须先「按人汇总」再校验，因为同一人可能一次录多条，
     * 逐条校验会出现「前几条都够、加起来不够」的漏判。
     *
     * @param req 批量录入请求，含记录列表与是否允许透支
     * @return 录入结果，含成功条数与本次录入后处于透支状态的员工姓名
     * @throws BizException 记录为空、员工或日期校验失败，或未允许透支但余额不足时抛出
     */
    @Transactional
    public LeaveDto.BatchResult createBatch(LeaveDto.BatchCreateRequest req) {
        if (req == null || req.getRecords() == null || req.getRecords().isEmpty()) {
            throw new BizException("请至少录入一条记录");
        }
        boolean allowOverdraft = Boolean.TRUE.equals(req.getAllowOverdraft());

        List<LeaveUsageRecord> pending = new ArrayList<>();
        // 按人汇总本次需要扣减的小时数
        Map<Long, BigDecimal> need = new LinkedHashMap<>();
        Map<Long, User> users = new LinkedHashMap<>();

        int idx = 0;
        for (LeaveDto.LeaveUsageItem item : req.getRecords()) {
            idx++;
            User u = requireUser(item.getUserId());
            users.put(u.getId(), u);
            BigDecimal hours = computeHours(item.getStartTime(), item.getEndTime());
            need.merge(u.getId(), hours, BigDecimal::add);

            LeaveUsageRecord r = new LeaveUsageRecord();
            r.setUserId(u.getId());
            r.setDate(requireDate(item.getDate(), idx));
            r.setStartTime(item.getStartTime());
            r.setEndTime(item.getEndTime());
            r.setHours(hours);
            r.setRemark(item.getRemark());
            r.setStatus(LeaveUsageRecord.STATUS_NORMAL);
            r.setCreatedBy(SecurityUtil.getUserId());
            pending.add(r);
        }
        this.assertRestDayConfirmed(pending, Boolean.TRUE.equals(req.getAllowRestDay()));
        LeaveService.assertBatchNoOverlap(pending);
        for (LeaveUsageRecord leaveUsageRecord : pending) {
            this.conflictGuard.assertIntervalFree(leaveUsageRecord.getUserId(), leaveUsageRecord.getDate(),
                    leaveUsageRecord.getStartTime(), leaveUsageRecord.getEndTime(), null, null);
        }

        if (!allowOverdraft) {
            for (Map.Entry<Long, BigDecimal> e : need.entrySet()) {
                BigDecimal bal = balanceService.currentBalance(e.getKey());
                if (bal.subtract(e.getValue()).compareTo(BigDecimal.ZERO) < 0) {
                    throw new BizException(String.format("余额不足，当前余额 %s 小时，本次使用 %s 小时，是否继续？",
                            fmt(bal), fmt(e.getValue())));
                }
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        for (LeaveUsageRecord r : pending) {
            LeaveUsageRecord saved = leaveUsageRepository.save(r);
            total = total.add(saved.getHours());
            balanceService.spend(saved.getUserId(), saved.getHours(), LeaveUsageRecord.REF_TYPE, saved.getId(),
                    "调休使用 " + saved.getDate());
        }

        LeaveDto.BatchResult result = new LeaveDto.BatchResult();
        result.setCount(pending.size());
        for (Long uid : need.keySet()) {
            refreshOverdraft(uid, users.get(uid), result.getOverdraftNames());
        }

        String names = need.keySet().stream()
                .map(id -> users.get(id) == null ? ("员工#" + id) : users.get(id).getName())
                .distinct()
                .collect(Collectors.joining("、"));
        auditLogService.log(AuditLog.MODULE_LEAVE, "录入调休使用记录", names,
                String.format("共 %d 条，合计 %s 小时%s", pending.size(), fmt(total),
                        allowOverdraft && !result.getOverdraftNames().isEmpty() ? "（透支录入）" : ""));
        return result;
    }

    /**
     * 批量新增前校验：同一用户同一天内不得存在时间重叠的记录。
     *
     * @param pending 待写入的调休使用记录列表
     * @throws BizException 存在同一天时间重叠的记录时抛出
     */
    private static void assertBatchNoOverlap(List<LeaveUsageRecord> pending) {
        for (int i = 0; i < pending.size(); ++i) {
            LeaveUsageRecord a = pending.get(i);
            for (int j = i + 1; j < pending.size(); ++j) {
                LeaveUsageRecord b = pending.get(j);
                if (!a.getUserId().equals(b.getUserId()) || !a.getDate().equals(b.getDate())
                        || !RecordConflictGuard.overlaps(a.getStartTime(), a.getEndTime(), b.getStartTime(), b.getEndTime())) {
                    continue;
                }
                throw new BizException("本次提交中存在同一天时间重叠的记录：" + String.valueOf(a.getDate()) + " "
                        + String.valueOf(a.getStartTime()) + "–" + String.valueOf(a.getEndTime()) + " 与 "
                        + String.valueOf(b.getStartTime()) + "–" + String.valueOf(b.getEndTime()) + "，请调整后重试");
            }
        }
    }

    /**
     * 批量新增前校验：若在休息日登记调休，需经前端二次确认（allowRestDay=true）。
     *
     * @param pending       待写入的调休使用记录列表
     * @param allowRestDay  前端是否已确认允许在休息日登记
     * @throws BizException 存在未确认的休息日登记时抛出
     */
    private void assertRestDayConfirmed(List<LeaveUsageRecord> pending, boolean allowRestDay) {
        if (allowRestDay) {
            return;
        }
        List<String> restDates = new ArrayList<>();
        for (LeaveUsageRecord r : pending) {
            if (r.getDate() == null || !this.holidayService.isRestDay(r.getDate())) {
                continue;
            }
            String label = String.valueOf(r.getDate())
                    + (this.holidayService.holidayNameOf(r.getDate()) != null
                    ? "（" + this.holidayService.holidayNameOf(r.getDate()) + "）" : "");
            if (restDates.contains(label)) {
                continue;
            }
            restDates.add(label);
        }
        if (restDates.isEmpty()) {
            return;
        }
        throw new BizException("所选日期属于休息日：" + String.join("、", restDates)
                + "。休息日本不用上班，在此登记调休会直接扣减余额，是否继续？");
    }

    /**
     * 单条录入（Excel 导入时逐行复用）。
     *
     * @param item           单条录入内容
     * @param allowOverdraft 余额不足时是否允许透支扣减
     * @return 录入后的记录
     * @throws BizException 员工/日期校验失败，或未允许透支但余额不足时抛出
     */
    @Transactional
    public LeaveDto.LeaveUsageResponse createOne(LeaveDto.LeaveUsageItem item, boolean allowOverdraft) {
        User u = requireUser(item.getUserId());
        LocalDate date = requireDate(item.getDate(), 0);
        BigDecimal hours = computeHours(item.getStartTime(), item.getEndTime());
        this.conflictGuard.assertIntervalFree(u.getId(), date, item.getStartTime(), item.getEndTime(), null, null);
        if (!allowOverdraft) {
            BigDecimal bal = balanceService.currentBalance(u.getId());
            if (bal.subtract(hours).compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(String.format("余额不足，当前余额 %s 小时，本次使用 %s 小时，是否继续？",
                        fmt(bal), fmt(hours)));
            }
        }
        LeaveUsageRecord r = new LeaveUsageRecord();
        r.setUserId(u.getId());
        r.setDate(date);
        r.setStartTime(item.getStartTime());
        r.setEndTime(item.getEndTime());
        r.setHours(hours);
        r.setRemark(item.getRemark());
        r.setStatus(LeaveUsageRecord.STATUS_NORMAL);
        r.setCreatedBy(SecurityUtil.getUserId());
        LeaveUsageRecord saved = leaveUsageRepository.save(r);
        balanceService.spend(saved.getUserId(), saved.getHours(), LeaveUsageRecord.REF_TYPE, saved.getId(),
                "调休使用 " + saved.getDate());
        refreshOverdraft(saved.getUserId(), u, null);
        return toResponse(saved, u);
    }

    // ==================== 编辑 ====================

    /**
     * 编辑调休使用记录，并同步重算相关员工的调休余额。
     *
     * <p>余额同步分两种情形：
     * <ul>
     *   <li><b>同一员工</b>：按新旧时长的差额补扣或退还。</li>
     *   <li><b>更换员工</b>：原员工全额退还旧时长，新员工全额扣减新时长——
     *       即使新旧时长完全相同也必须搬账，否则会出现「提示更新成功但余额没变」。</li>
     * </ul>
     * 换人时会同时刷新两个人的透支状态。
     *
     * @param id  记录 ID
     * @param req 编辑内容，未传的字段沿用原值；传 userId 且与原值不同即视为换人
     * @return 编辑后的记录
     * @throws BizException 记录不存在、已作废、新员工非法，或时间校验不通过时抛出
     */
    @Transactional
    public LeaveDto.LeaveUsageResponse update(Long id, LeaveDto.UpdateRequest req) {
        LeaveUsageRecord r = requireRecord(id);
        if (LeaveUsageRecord.STATUS_VOID.equals(r.getStatus())) throw new BizException("已作废的记录不可编辑");
        if (req == null) throw new BizException("请求体不能为空");

        Long oldUserId = r.getUserId();
        BigDecimal oldHours = r.getHours();
        User oldUser = userRepository.findById(oldUserId).orElse(null);

        // 归属员工：传了且不同才算换人，新员工必须是可录入的普通员工
        Long newUserId = req.getUserId() != null ? req.getUserId() : oldUserId;
        boolean userChanged = !newUserId.equals(oldUserId);
        if (userChanged) requireNotSystemWorker(newUserId);
        User newUser = userChanged ? userRepository.findById(newUserId).orElse(null) : oldUser;

        LocalDate newDate = req.getDate() != null ? req.getDate() : r.getDate();
        LocalTime newStart = req.getStartTime() != null ? req.getStartTime() : r.getStartTime();
        LocalTime newEnd = req.getEndTime() != null ? req.getEndTime() : r.getEndTime();
        BigDecimal newHours = computeHours(newStart, newEnd);

        this.conflictGuard.assertIntervalFree(newUserId, newDate, newStart, newEnd, null, id);

        String before = describe(r);

        r.setUserId(newUserId);
        r.setDate(newDate);
        r.setStartTime(newStart);
        r.setEndTime(newEnd);
        r.setHours(newHours);
        if (req.getRemark() != null) r.setRemark(req.getRemark());
        LeaveUsageRecord saved = leaveUsageRepository.save(r);

        if (userChanged) {
            // 换人：原员工全额退还，新员工全额扣减（时长相同也要搬账）
            if (oldHours.compareTo(BigDecimal.ZERO) != 0) {
                balanceService.adjustUp(oldUserId, oldHours, LeaveUsageRecord.REF_TYPE_ADJUST, saved.getId(),
                        "编辑调休使用记录（员工变更），退还调休 +" + oldHours + "h");
            }
            if (newHours.compareTo(BigDecimal.ZERO) != 0) {
                balanceService.spend(newUserId, newHours, LeaveUsageRecord.REF_TYPE_ADJUST, saved.getId(),
                        "编辑调休使用记录（员工变更），扣减调休 -" + newHours + "h");
            }
            refreshOverdraft(oldUserId, oldUser, null);
            refreshOverdraft(newUserId, newUser, null);
        } else {
            BigDecimal delta = newHours.subtract(oldHours);
            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                balanceService.spend(newUserId, delta, LeaveUsageRecord.REF_TYPE_ADJUST, saved.getId(),
                        "编辑调休使用记录，补扣差额");
            } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
                balanceService.adjustUp(newUserId, delta.abs(), LeaveUsageRecord.REF_TYPE_ADJUST, saved.getId(),
                        "编辑调休使用记录，退还差额");
            }
            refreshOverdraft(newUserId, newUser, null);
        }

        String target = newUser != null ? newUser.getName() : ("员工#" + newUserId);
        if (userChanged) {
            target = (oldUser != null ? oldUser.getName() : ("员工#" + oldUserId)) + " → " + target;
        }
        auditLogService.logChange(AuditLog.MODULE_LEAVE, "编辑调休使用记录", target,
                "编辑调休使用记录 #" + id, before, describe(saved), AuditLog.LEVEL_WARN);
        return toResponse(saved, newUser);
    }

    // ==================== 作废 / 删除 ====================

    /**
     * 作废：状态置 VOID，使用时长恢复至调休余额，物理数据保留以便审计。
     *
     * @param id     记录 ID
     * @param reason 作废原因，为空时记为「未填写」
     * @throws BizException 记录不存在或已作废时抛出
     */
    @Transactional
    public void voidRecord(Long id, String reason) {
        LeaveUsageRecord r = requireRecord(id);
        if (LeaveUsageRecord.STATUS_VOID.equals(r.getStatus())) throw new BizException("该记录已作废，不可重复作废");
        User u = userRepository.findById(r.getUserId()).orElse(null);

        String before = describe(r);
        r.setStatus(LeaveUsageRecord.STATUS_VOID);
        r.setVoidReason(reason == null || reason.isBlank() ? "未填写" : reason.trim());
        r.setVoidBy(SecurityUtil.getUserId());
        r.setVoidAt(LocalDateTime.now());
        LeaveUsageRecord saved = leaveUsageRepository.save(r);

        // 恢复余额：记一笔调增
        balanceService.adjustUp(saved.getUserId(), saved.getHours(), LeaveUsageRecord.REF_TYPE_VOID, saved.getId(),
                "作废调休使用记录，恢复额度");
        refreshOverdraft(saved.getUserId(), u, null);

        auditLogService.logChange(AuditLog.MODULE_LEAVE, "作废调休使用记录",
                u != null ? u.getName() : ("员工#" + saved.getUserId()),
                "作废原因：" + saved.getVoidReason(), before, describe(saved), AuditLog.LEVEL_WARN);
    }

    /**
     * 物理删除（兼容旧调用保留）：若记录尚未作废，先把已扣减的余额恢复，再删除记录。
     *
     * <p>业务上更推荐用 {@link #voidRecord} 作废而非物理删除。
     *
     * @param id 记录 ID
     * @throws BizException 记录不存在时抛出
     */
    @Transactional
    public void delete(Long id) {
        LeaveUsageRecord r = requireRecord(id);
        User u = userRepository.findById(r.getUserId()).orElse(null);
        String before = describe(r);

        if (!LeaveUsageRecord.STATUS_VOID.equals(r.getStatus())) {
            balanceService.adjustUp(r.getUserId(), r.getHours(), LeaveUsageRecord.REF_TYPE_VOID, r.getId(),
                    "删除调休使用记录，恢复额度");
        }
        leaveUsageRepository.delete(r);
        if (u != null) refreshOverdraft(u.getId(), u, null);

        auditLogService.logChange(AuditLog.MODULE_LEAVE, "删除调休使用记录",
                u != null ? u.getName() : ("员工#" + r.getUserId()),
                "物理删除调休使用记录 #" + id, before, null, AuditLog.LEVEL_WARN);
    }

    // ==================== 余额与消息 ====================

    /**
     * 查询某员工的调休余额。
     *
     * @param userId 用户 ID
     * @return 余额信息（含是否透支）
     * @throws BizException 用户不存在时抛出
     */
    @Transactional(readOnly = true)
    public LeaveDto.BalanceResponse balance(Long userId) {
        User u = userRepository.findById(userId).orElseThrow(() -> new BizException("成员不存在"));
        BigDecimal bal = balanceService.currentBalance(userId);
        LeaveDto.BalanceResponse res = new LeaveDto.BalanceResponse();
        res.setUserId(u.getId());
        res.setUserName(u.getName());
        res.setBalance(bal);
        res.setOverdraft(bal.compareTo(BigDecimal.ZERO) < 0);
        return res;
    }

    /**
     * 查询未处理的余额透支提醒（按创建时间倒序）。
     *
     * @return 提醒消息列表，每条附带该员工当前余额
     */
    @Transactional(readOnly = true)
    public List<LeaveDto.MessageResponse> messages() {
        List<UserMessage> list = userMessageRepository
                .findByTypeAndResolvedFalseOrderByCreatedAtDesc(UserMessage.TYPE_OVERDRAFT);
        List<LeaveDto.MessageResponse> res = new ArrayList<>();
        for (UserMessage m : list) {
            User u = m.getUserId() == null ? null : userRepository.findById(m.getUserId()).orElse(null);
            LeaveDto.MessageResponse mr = new LeaveDto.MessageResponse();
            mr.setId(m.getId());
            mr.setType(m.getType());
            mr.setUserId(m.getUserId());
            mr.setUserName(u != null ? u.getName() : null);
            mr.setDepartment(u != null ? u.getDepartment() : null);
            mr.setContent(m.getContent());
            mr.setTargetRoles(m.getTargetRoles());
            mr.setRead(m.getRead());
            mr.setResolved(m.getResolved());
            mr.setBalance(u != null ? balanceService.currentBalance(u.getId()) : BigDecimal.ZERO);
            mr.setCreatedAt(m.getCreatedAt());
            res.add(mr);
        }
        return res;
    }

    /**
     * 将某条提醒标记为已读。
     *
     * <p>注意：已读不会让消息消失，只有余额恢复后由 {@link #refreshOverdraft} 置为「已处理」才会消失。
     *
     * @param id 消息 ID
     * @throws BizException 消息不存在时抛出
     */
    @Transactional
    public void markRead(Long id) {
        UserMessage m = userMessageRepository.findById(id).orElseThrow(() -> new BizException("消息不存在"));
        m.setRead(true);
        userMessageRepository.save(m);
    }

    /**
     * 余额变动后刷新透支提醒：余额 < 0 生成（未存在时）提醒；余额恢复 >= 0 自动把未处理提醒置 resolved。
     */
    /**
     * 余额变动后刷新透支提醒。
     *
     * @param userId         员工 ID
     * @param user           员工实体，可为 null（仅用于生成文案）
     * @param overdraftNames 输出参数：余额为负时把员工姓名加入其中，供批量录入结果提示；传 null 表示不收集
     */
    private void refreshOverdraft(Long userId, User user, List<String> overdraftNames) {
        BigDecimal bal = balanceService.currentBalance(userId);
        if (bal.compareTo(BigDecimal.ZERO) < 0) {
            if (!userMessageRepository.existsByUserIdAndTypeAndResolvedFalse(userId, UserMessage.TYPE_OVERDRAFT)) {
                UserMessage m = new UserMessage();
                m.setType(UserMessage.TYPE_OVERDRAFT);
                m.setUserId(userId);
                m.setContent("【" + displayName(userId, user) + "】当前调休余额不足，请注意！");
                m.setTargetRoles(UserMessage.ROLES_ADMIN_CLERK);
                m.setRead(false);
                m.setResolved(false);
                userMessageRepository.save(m);
            }
            if (overdraftNames != null) overdraftNames.add(displayName(userId, user));
        } else {
            List<UserMessage> unresolved =
                    userMessageRepository.findByUserIdAndTypeAndResolvedFalse(userId, UserMessage.TYPE_OVERDRAFT);
            for (UserMessage m : unresolved) {
                m.setResolved(true);
                m.setResolvedAt(LocalDateTime.now());
                userMessageRepository.save(m);
            }
        }
    }

    // ==================== 公共工具 ====================

    /**
     * 计算使用时长 = 结束时间 − 开始时间（小时，两位小数），起止时间必须落在整点或半点。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 使用小时数
     * @throws BizException 起止时间为空、非整点半点，或结束不晚于开始时抛出
     */
    public static BigDecimal computeHours(LocalTime start, LocalTime end) {
        if (start == null || end == null) throw new BizException("开始时间与结束时间不能为空");
        checkHalfHour(start, "开始时间");
        checkHalfHour(end, "结束时间");
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes <= 0) throw new BizException("结束时间必须晚于开始时间");
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /**
     * 校验时间是否落在整点或半点（30 分钟一跳的业务约定）。
     *
     * @param t     待校验时间
     * @param label 字段中文名，用于错误提示
     * @throws BizException 分钟不是 00/30，或存在秒 / 纳秒时抛出
     */
    private static void checkHalfHour(LocalTime t, String label) {
        if (t.getMinute() % 30 != 0 || t.getSecond() != 0 || t.getNano() != 0) {
            throw new BizException(label + "需为整点或半点");
        }
    }

    /**
     * 按 ID 取记录，不存在则抛业务异常。
     *
     * @param id 记录 ID
     * @return 调休使用记录
     * @throws BizException 记录不存在时抛出
     */
    private LeaveUsageRecord requireRecord(Long id) {
        return leaveUsageRepository.findById(id).orElseThrow(() -> new BizException("调休使用记录不存在"));
    }

    /**
     * 校验并取回员工：必须存在且状态正常。
     *
     * @param userId 用户 ID
     * @return 用户实体
     * @throws BizException 未选择、不存在或状态异常时抛出
     */
    private User requireUser(Long userId) {
        if (userId == null) throw new BizException("请选择员工");
        User u = userRepository.findById(userId).orElseThrow(() -> new BizException("成员不存在"));
        if (!User.STATUS_ACTIVE.equals(u.getStatus())) throw new BizException("成员状态异常：" + u.getName());
        requireNotSystemWorker(userId);
        return u;
    }

    /**
     * 校验使用日期非空。
     *
     * @param date 使用日期
     * @param idx  行号，大于 0 时错误信息带行号（批量录入场景）
     * @return 校验通过的日期
     * @throws BizException 日期为空时抛出
     */
    private static LocalDate requireDate(LocalDate date, int idx) {
        if (date == null) throw new BizException((idx > 0 ? "第" + idx + "行：" : "") + "使用日期不能为空");
        return date;
    }

    /**
     * 员工显示名兜底：用户实体缺失时退化为「员工#ID」。
     *
     * @param userId 用户 ID
     * @param user   用户实体，可为 null
     * @return 展示用姓名
     */
    private static String displayName(Long userId, User user) {
        return user != null ? user.getName() : ("员工#" + userId);
    }

    /**
     * 金额格式化：保留两位小数、不使用科学计数法。
     *
     * @param v 金额，可为 null
     * @return 格式化后的字符串；null 返回 "0.00"
     */
    private static String fmt(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 生成记录的一句话摘要，用于审计日志的变更前 / 变更后值。
     *
     * @param r 调休使用记录
     * @return 摘要文本
     */
    private static String describe(LeaveUsageRecord r) {
        return String.format("%s %s~%s %s 小时",
                r.getDate(), r.getStartTime(), r.getEndTime(), fmt(r.getHours()));
    }
}

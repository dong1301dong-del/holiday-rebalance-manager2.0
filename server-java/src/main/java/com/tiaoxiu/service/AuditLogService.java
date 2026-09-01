package com.tiaoxiu.service;

import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.repository.AuditLogRepository;
import com.tiaoxiu.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** 系统日志：写入与查询。写入使用独立事务，避免主业务回滚导致日志丢失。 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    /**
     * 构造器注入。
     *
     * @param auditLogRepository 审计日志仓储
     * @param userRepository     用户仓储（补全操作人账号与姓名）
     */
    public AuditLogService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * 记录一条 INFO 级别、结果为成功的日志（操作人取自当前登录上下文）。
     *
     * @param module 业务模块，见 AuditLog.MODULE_*
     * @param action 操作类型，如「录入加班」
     * @param target 操作对象描述，如员工姓名
     * @param detail 操作详情
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String module, String action, String target, String detail) {
        log(module, action, target, detail, null, null, AuditLog.LEVEL_INFO, AuditLog.RESULT_SUCCESS);
    }

    /**
     * 记录一条指定级别与结果的日志（操作人取自当前登录上下文）。
     *
     * @param module 业务模块
     * @param action 操作类型
     * @param target 操作对象描述
     * @param detail 操作详情
     * @param level  日志级别，见 AuditLog.LEVEL_*；为 null 时按 INFO
     * @param result 操作结果，见 AuditLog.RESULT_*；为 null 时按 SUCCESS
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String module, String action, String target, String detail, String level, String result) {
        log(module, action, target, detail, null, null, level, result);
    }

    /**
     * 记录一条含变更前 / 变更后值的日志（用于编辑、作废等高风险操作留痕）。
     *
     * @param module      业务模块
     * @param action      操作类型
     * @param target      操作对象描述
     * @param detail      操作详情
     * @param beforeValue 变更前内容
     * @param afterValue  变更后内容
     * @param level       日志级别
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logChange(String module, String action, String target, String detail,
                          String beforeValue, String afterValue, String level) {
        log(module, action, target, detail, beforeValue, afterValue, level, AuditLog.RESULT_SUCCESS);
    }

    /**
     * 写入日志的完整实现。
     *
     * <p>两点关键设计：
     * <ul>
     *   <li>使用 {@code REQUIRES_NEW} 独立事务：主业务回滚时日志仍要留下，否则故障无法追溯；</li>
     *   <li>整体 try-catch 吞掉异常：审计是旁路能力，写入失败绝不能中断主业务。</li>
     * </ul>
     *
     * @param module      业务模块
     * @param action      操作类型
     * @param target      操作对象描述
     * @param detail      操作详情
     * @param beforeValue 变更前内容，可为 null
     * @param afterValue  变更后内容，可为 null
     * @param level       日志级别，为 null 时按 INFO
     * @param result      操作结果，为 null 时按 SUCCESS
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String module, String action, String target, String detail,
                    String beforeValue, String afterValue, String level, String result) {
        try {
            AuditLog log = new AuditLog();
            Long uid = SecurityUtil.getUserId();
            log.setUserId(uid);
            if (uid != null) {
                User u = userRepository.findById(uid).orElse(null);
                if (u != null) {
                    log.setUsername(u.getUsername());
                    log.setUserRealName(u.getName());
                }
            }
            log.setModule(module);
            log.setAction(action);
            log.setTarget(target);
            log.setDetail(detail);
            log.setBeforeValue(beforeValue);
            log.setAfterValue(afterValue);
            log.setLevel(level == null ? AuditLog.LEVEL_INFO : level);
            log.setResult(result == null ? AuditLog.RESULT_SUCCESS : result);
            log.setIp(SecurityUtil.getClientIp());
            auditLogRepository.save(log);
        } catch (Exception ignore) {
            // 日志写入失败绝不能影响主业务
        }
    }

    /**
     * 指定操作人写入日志，用于「登录」等尚未建立会话、SecurityUtil 中还没有用户信息的场景。
     *
     * <p>同样采用独立事务并吞掉异常，保证不影响主流程。
     *
     * @param userId  操作人 ID，可为 null（如账号不存在的登录尝试）
     * @param module  业务模块
     * @param action  操作类型
     * @param target  操作对象描述
     * @param detail  操作详情
     * @param level   日志级别，为 null 时按 INFO
     * @param result  操作结果，为 null 时按 SUCCESS
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAs(Long userId, String module, String action, String target, String detail, String level, String result) {
        try {
            AuditLog log = new AuditLog();
            log.setUserId(userId);
            if (userId != null) {
                User u = userRepository.findById(userId).orElse(null);
                if (u != null) {
                    log.setUsername(u.getUsername());
                    log.setUserRealName(u.getName());
                }
            }
            log.setModule(module);
            log.setAction(action);
            log.setTarget(target);
            log.setDetail(detail);
            log.setLevel(level == null ? AuditLog.LEVEL_INFO : level);
            log.setResult(result == null ? AuditLog.RESULT_SUCCESS : result);
            log.setIp(SecurityUtil.getClientIp());
            auditLogRepository.save(log);
        } catch (Exception ignore) {
        }
    }

    /**
     * 分页查询系统日志（条件任意组合，未传的条件不过滤）。
     *
     * <p>日期区间按「当天 00:00:00 ~ 23:59:59」处理，符合用户对「按天筛选」的预期。
     *
     * @param module  模块精确匹配，可为空
     * @param action  操作类型精确匹配，可为空
     * @param level   日志级别精确匹配，可为空
     * @param keyword 关键字，模糊匹配操作人账号 / 姓名 / 操作对象 / 详情，可为空
     * @param from    起始日期（含），可为 null
     * @param to      结束日期（含），可为 null
     * @param page    页码，0 基
     * @param size    每页大小，最大 200
     * @return 分页日志结果，按创建时间倒序
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> query(String module, String action, String level, String keyword,
                                LocalDate from, LocalDate to, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(module)) ps.add(cb.equal(root.get("module"), module));
            if (StringUtils.hasText(action)) ps.add(cb.equal(root.get("action"), action));
            if (StringUtils.hasText(level)) ps.add(cb.equal(root.get("level"), level));
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim() + "%";
                ps.add(cb.or(
                        cb.like(root.get("username"), kw),
                        cb.like(root.get("userRealName"), kw),
                        cb.like(root.get("target"), kw),
                        cb.like(root.get("detail"), kw)
                ));
            }
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), LocalDateTime.of(from, LocalTime.MIN)));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), LocalDateTime.of(to, LocalTime.MAX)));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return auditLogRepository.findAll(spec, pageable);
    }
}

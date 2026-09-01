package com.tiaoxiu.task;

import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.repository.AuditLogRepository;
import com.tiaoxiu.repository.UserRepository;
import com.tiaoxiu.service.BalanceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 月度透支提醒定时任务：每月 10 号上午 9 点扫描调休余额为负的员工，写入审计日志留痕。
 *
 * <p>补充说明：实时的透支提醒（站内消息）由 {@link com.tiaoxiu.service.LeaveService#refreshOverdraft}
 * 在每次余额变动时生成；本任务是周期性汇总，用于月度层面的跟进与留痕。
 *
 * <p>注意：本任务所在线程没有 Web 请求上下文，因此 {@link com.tiaoxiu.common.SecurityUtil}
 * 中取不到操作人，写入的日志 userId 为空。
 */
@Component
public class ReminderScheduler {

    private final UserRepository userRepository;
    private final BalanceService balanceService;
    private final AuditLogRepository auditLogRepository;

    /**
     * 构造器注入。
     *
     * @param userRepository      用户仓储
     * @param balanceService      余额引擎（取实时余额）
     * @param auditLogRepository  审计日志仓储（写入提醒记录）
     */
    public ReminderScheduler(UserRepository userRepository, BalanceService balanceService, AuditLogRepository auditLogRepository) {
        this.userRepository = userRepository;
        this.balanceService = balanceService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 扫描所有未删除用户，为余额为负的员工逐条写提醒日志，最后再写一条汇总日志。
     *
     * <p>cron 表达式 {@code 0 0 9 10 * ?} 表示每月 10 号 09:00:00 触发。
     */
    @Scheduled(cron = "0 0 9 10 * ?")
    @Transactional
    public void overdraftReminder() {
        List<User> users = userRepository.findByStatusNot(User.STATUS_DELETED);
        int count = 0;
        for (User u : users) {
            BigDecimal b = balanceService.currentBalance(u.getId());
            if (b.compareTo(BigDecimal.ZERO) < 0) {
                AuditLog log = new AuditLog();
                log.setAction("OVERDRAFT_REMINDER");
                log.setTarget("user:" + u.getId());
                log.setDetail("成员[" + u.getName() + "]调休余额透支 " + b + "h，请尽快安排加班补足");
                auditLogRepository.save(log);
                count++;
            }
        }
        if (count > 0) {
            AuditLog summary = new AuditLog();
            summary.setAction("OVERDRAFT_REMINDER_SUMMARY");
            summary.setDetail("本月共 " + count + " 名成员调休余额透支");
            auditLogRepository.save(summary);
        }
    }
}

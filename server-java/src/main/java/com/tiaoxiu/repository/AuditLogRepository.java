package com.tiaoxiu.repository;

import com.tiaoxiu.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * 系统日志（审计日志）的数据访问接口：操作表 {@code audit_logs}。
 *
 * <p>本接口不定义任何派生查询方法，全部查询能力来自继承的 {@link JpaSpecificationExecutor}：
 * 系统日志页面的筛选条件（模块、操作类型、级别、结果、操作人、时间区间等）是任意组合的，
 * 用 Specification 动态拼条件比穷举派生方法更合适。
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}

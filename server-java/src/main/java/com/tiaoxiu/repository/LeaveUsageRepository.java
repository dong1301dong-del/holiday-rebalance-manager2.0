package com.tiaoxiu.repository;

import com.tiaoxiu.entity.LeaveUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 调休使用记录的数据访问接口：操作表 {@code leave_usage_records}。
 *
 * <p>同时继承 {@link JpaSpecificationExecutor}，支持动态条件（姓名模糊、部门、日期区间等）分页查询。
 *
 * <p>提供的查询能力：按用户查（时间倒序，用于个人明细）、按状态查、按日期区间查（跨用户，用于统计与导出）。
 * 排序统一带上 ID 作为次级排序键，避免同一天的记录顺序在多次查询间抖动。
 */
@Repository
public interface LeaveUsageRepository
        extends JpaRepository<LeaveUsageRecord, Long>, JpaSpecificationExecutor<LeaveUsageRecord> {

    /**
     * 查询某用户的全部调休使用记录，按日期倒序、同日按 ID 倒序（最新在前）。
     *
     * @param userId 用户 ID
     * @return 该用户的调休使用记录列表
     */
    List<LeaveUsageRecord> findByUserIdOrderByDateDescIdDesc(Long userId);

    /**
     * 按状态查询调休使用记录。
     *
     * @param status 状态，见 LeaveUsageRecord.STATUS_*
     * @return 匹配的记录列表，按日期倒序
     */
    List<LeaveUsageRecord> findByStatusOrderByDateDescIdDesc(String status);

    /**
     * 查询指定日期区间内的调休使用记录（含起止日），按日期升序。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 匹配的记录列表
     */
    List<LeaveUsageRecord> findByDateBetweenOrderByDateAscIdAsc(LocalDate from, LocalDate to);

    @Query("select r from LeaveUsageRecord r where r.userId = :uid and r.date = :date and r.status <> 'VOID'")
    List<LeaveUsageRecord> findEffectiveByUserIdAndDate(@Param("uid") Long userId, @Param("date") LocalDate date);
}

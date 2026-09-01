package com.tiaoxiu.repository;

import com.tiaoxiu.entity.LeaveRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 调休申请（历史遗留）的数据访问接口：操作表 {@code leave_requests}。
 *
 * <p><b>注意：</b>LeaveRequest 及其表已废弃，仅为兼容历史数据保留，新代码请改用
 * {@link LeaveUsageRepository}。保留本接口是为了旧数据仍能查询与导出。
 *
 * <p>提供的查询能力：按用户查、按状态查、按用户 + 状态查，以及按日期区间查（区间重叠匹配）。
 */
@Repository
public interface LeaveRepository extends JpaRepository<LeaveRequest, Long> {

    /**
     * 查询某用户的全部调休申请，按创建时间倒序。
     *
     * @param userId 申请人 ID
     * @return 该用户的申请列表，最新的在前
     */
    List<LeaveRequest> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 按状态查询调休申请，按创建时间倒序。
     *
     * @param status 状态，见 LeaveRequest.STATUS_*
     * @return 匹配的申请列表
     */
    List<LeaveRequest> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * 查询某用户指定状态的调休申请。
     *
     * @param userId 申请人 ID
     * @param status 状态，见 LeaveRequest.STATUS_*
     * @return 匹配的申请列表
     */
    List<LeaveRequest> findByUserIdAndStatus(Long userId, String status);

    /**
     * 查询与指定日期区间有重叠的调休申请。
     *
     * <p>重叠判定条件：申请开始日 ≤ 区间结束日 且 申请结束日 ≥ 区间开始日，
     * 只这样写才能覆盖「申请区间完全包含查询区间」等四种重叠情形。
     *
     * @param start 查询起始日期
     * @param end   查询结束日期
     * @return 有重叠的申请列表，按创建时间倒序
     */
    @Query("select l from LeaveRequest l where l.startDate <= :end and l.endDate >= :start order by l.createdAt desc")
    List<LeaveRequest> findByDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);
}

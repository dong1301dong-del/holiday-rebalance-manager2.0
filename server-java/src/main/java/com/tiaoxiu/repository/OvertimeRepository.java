package com.tiaoxiu.repository;

import com.tiaoxiu.entity.OvertimeRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;

/**
 * 加班转调休记录的数据访问接口：操作表 {@code overtime_records}。
 *
 * <p>除基础 CRUD 外，同时继承 {@link JpaSpecificationExecutor}，
 * 使 {@link com.tiaoxiu.service.OvertimeService} 能用动态条件（姓名模糊、部门、日期区间、状态等）组合分页查询。
 *
 * <p>提供的查询能力：按用户查、按用户+状态查、按用户+日期区间查、按日期区间查（跨用户）、
 * 按状态查、全表按时间倒序查（导出）、按用户集合批量查。
 */
@Repository
public interface OvertimeRepository extends JpaRepository<OvertimeRecord, Long>, JpaSpecificationExecutor<OvertimeRecord> {

    /**
     * 查询某用户的全部加班记录，按日期升序（用于余额流水的明细展示）。
     *
     * @param userId 用户 ID
     * @return 该用户的加班记录列表，按日期从早到晚
     */
    List<OvertimeRecord> findByUserIdOrderByDateAsc(Long userId);

    /**
     * 查询某用户指定状态的加班记录。
     *
     * @param userId 用户 ID
     * @param status 状态，见 OvertimeRecord.STATUS_*
     * @return 匹配的记录列表
     */
    List<OvertimeRecord> findByUserIdAndStatus(Long userId, String status);

    /**
     * 查询某用户在指定日期区间内的加班记录（含起止日）。
     *
     * @param userId 用户 ID
     * @param start  起始日期（含）
     * @param end    结束日期（含）
     * @return 匹配的记录列表
     */
    List<OvertimeRecord> findByUserIdAndDateBetween(Long userId, LocalDate start, LocalDate end);

    /**
     * 查询所有用户在指定日期区间内的加班记录（用于统计报表与导出）。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 匹配的记录列表
     */
    List<OvertimeRecord> findByDateBetween(LocalDate start, LocalDate end);

    /**
     * 按状态查询加班记录。
     *
     * @param status 状态，见 OvertimeRecord.STATUS_*
     * @return 匹配的记录列表
     */
    List<OvertimeRecord> findByStatus(String status);

    /** 查询条件为空时导出全部：按日期倒序、同日按 ID 倒序，保证导出顺序稳定且与页面一致 */
    List<OvertimeRecord> findAllByOrderByDateDescIdDesc();

    /**
     * 批量查询多个用户的加班记录（用于「按部门 / 按人员筛选后一次性拉取」）。
     *
     * @param userIds 用户 ID 集合
     * @return 匹配的记录列表，按日期倒序、同日按 ID 倒序
     */
    List<OvertimeRecord> findByUserIdInOrderByDateDescIdDesc(Collection<Long> userIds);

    /**
     * 查重：同一员工、同一天、相同起始时间的「加班转休」记录（排除已作废）。
     * 用于阻止重复录入「同一人相同加班起始时间」或「新数据占用已有起始时间」。
     *
     * @param userId 员工 ID
     * @param date   加班日期
     * @param start  起始时间
     * @return 命中的已存在记录（可能为空），调用方需排除正在编辑的自身记录
     */
    @org.springframework.data.jpa.repository.Query(
            "select r from OvertimeRecord r where r.userId = :uid and r.date = :date "
                    + "and r.startTime = :start and r.recordMode = 'OVERTIME' and r.status <> 'VOID'")
    List<OvertimeRecord> findOverlappingStart(@org.springframework.data.repository.query.Param("uid") Long userId,
                                             @org.springframework.data.repository.query.Param("date") LocalDate date,
                                             @org.springframework.data.repository.query.Param("start") LocalTime start);
}

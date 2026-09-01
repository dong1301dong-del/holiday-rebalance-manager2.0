package com.tiaoxiu.repository;

import com.tiaoxiu.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 节假日日历的数据访问接口：操作表 {@code holidays}。
 *
 * <p>提供的查询能力：按单日精确查（加班折算系数判定走这条，调用最频繁）、
 * 全表按日期升序查（日历展示）、按日期区间查、按日期集合批量查、按是否系统生成查（区分人工维护项）、
 * 以及按日期删除（覆盖刷新某天时使用）。
 */
@Repository
public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    /**
     * 按日期精确查询（一天有且仅有一条记录）。
     *
     * @param date 目标日期
     * @return 命中则返回该日节假日记录；未维护该日时返回 {@link Optional#empty()}
     */
    Optional<Holiday> findByDate(LocalDate date);

    /**
     * 查询全部节假日记录，按日期升序（用于日历与列表展示）。
     *
     * @return 全部记录列表
     */
    List<Holiday> findAllByOrderByDateAsc();

    /**
     * 查询指定日期区间内的节假日记录（含起止日）。
     *
     * @param start 起始日期（含）
     * @param end   结束日期（含）
     * @return 匹配的记录列表
     */
    List<Holiday> findByDateBetween(LocalDate start, LocalDate end);

    /**
     * 按日期集合批量查询，避免逐日查库（N+1）。
     *
     * @param dates 日期集合
     * @return 命中的记录列表
     */
    List<Holiday> findByDateIn(List<LocalDate> dates);

    /**
     * 按是否系统生成查询，用于区分「人工维护」与「系统规则生成」的记录。
     *
     * @param auto true 查系统生成的，false 查人工维护的
     * @return 匹配的记录列表
     */
    List<Holiday> findByAuto(Boolean auto);

    /**
     * 按日期删除某天的节假日记录（重新生成 / 清空某天时使用）。
     *
     * @param date 目标日期
     */
    void deleteByDate(LocalDate date);
}

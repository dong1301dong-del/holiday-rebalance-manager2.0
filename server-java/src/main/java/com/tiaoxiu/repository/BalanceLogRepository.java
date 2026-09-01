package com.tiaoxiu.repository;

import com.tiaoxiu.entity.BalanceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 调休余额流水的数据访问接口：操作表 {@code balance_logs}。
 *
 * <p>提供的查询能力：按用户查全部流水（时间正序，用于对账）、
 * 按「用户 + 关联类型 + 关联记录 ID」查流水（用于判断某笔业务是否已记过账，避免重复记账）、
 * 以及汇总某用户的当前余额。
 */
@Repository
public interface BalanceLogRepository extends JpaRepository<BalanceLog, Long> {

    /**
     * 查询某用户的全部余额流水，按记账时间升序（便于逐笔累加核对）。
     *
     * @param userId 用户 ID
     * @return 余额流水列表，按 createdAt 从早到晚
     */
    List<BalanceLog> findByUserIdOrderByCreatedAtAsc(Long userId);

    /**
     * 查询某笔业务产生的流水，用于幂等判断（如编辑加班记录时先冲销旧流水再补记新流水）。
     *
     * @param userId  用户 ID
     * @param refType 关联业务类型，如 {@code LEAVE_USAGE} / {@code LEAVE_USAGE_VOID}
     * @param refId   关联业务记录 ID
     * @return 匹配的流水列表，正常情况下应为空或只有一条
     */
    List<BalanceLog> findByUserIdAndRefTypeAndRefId(Long userId, String refType, Long refId);

    /**
     * 汇总某用户的当前调休余额。
     *
     * <p>收入类（EARN / ADJUST / INIT）记为正，支出类（SPEND）记为负后求和。
     * 用 {@code coalesce(..., 0)} 是因为用户无任何流水时 SUM 返回 null，直接参与后续比较会空指针。
     *
     * @param userId 用户 ID
     * @return 当前余额（小时），无流水时返回 0；透支时返回负数
     */
    @Query("select coalesce(sum(case when type in ('EARN','ADJUST','INIT') then hours else -hours end), 0) " +
           "from BalanceLog where userId = :uid")
    BigDecimal sumBalance(@Param("uid") Long userId);
}

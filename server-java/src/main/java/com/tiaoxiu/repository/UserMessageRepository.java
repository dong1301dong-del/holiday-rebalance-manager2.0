package com.tiaoxiu.repository;

import com.tiaoxiu.entity.UserMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 站内消息（透支预警）的数据访问接口：操作表 {@code user_messages}。
 *
 * <p>提供的查询能力：判断某员工是否已有未处理的同类提醒（用于去重，避免定时任务重复刷屏）、
 * 查某员工未处理的同类提醒（余额恢复后批量置为已处理）、
 * 按类型 / 全量查未处理消息（列表展示，按时间倒序，最新的在前）。
 *
 * <p>「未处理」即 {@code resolved = false}；消息一旦被处理不会物理删除，只是不再出现在查询结果里。
 */
@Repository
public interface UserMessageRepository extends JpaRepository<UserMessage, Long> {

    /**
     * 判断该员工是否已有未处理的同类提醒，用于避免重复刷屏。
     *
     * @param userId 被提醒的员工 ID
     * @param type   消息类型，见 UserMessage.TYPE_*
     * @return 存在未处理提醒返回 true
     */
    boolean existsByUserIdAndTypeAndResolvedFalse(Long userId, String type);

    /**
     * 查询该员工未处理的同类提醒（余额恢复为正常时，把这批消息一并置为已处理）。
     *
     * @param userId 被提醒的员工 ID
     * @param type   消息类型
     * @return 未处理的消息列表
     */
    List<UserMessage> findByUserIdAndTypeAndResolvedFalse(Long userId, String type);

    /**
     * 查询某类型的全部未处理消息，按创建时间倒序。
     *
     * @param type 消息类型
     * @return 未处理的消息列表，最新的在前
     */
    List<UserMessage> findByTypeAndResolvedFalseOrderByCreatedAtDesc(String type);

    /**
     * 查询全部未处理的消息，按创建时间倒序。
     *
     * @return 未处理的消息列表，最新的在前
     */
    List<UserMessage> findByResolvedFalseOrderByCreatedAtDesc();
}

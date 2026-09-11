package com.tiaoxiu.repository;

import com.tiaoxiu.entity.TokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 已作废令牌黑名单的数据访问接口：操作表 {@code token_blacklist}。
 *
 * <p>提供：按 token 摘要判断是否在黑名单中（登出后立即失效）、按过期时间批量清理历史记录。
 */
public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklist, Long> {

    /** 判断指定令牌摘要是否已作废（登出后命中）。 */
    boolean existsByTokenHash(String tokenHash);

    /** 清理所有已过期的黑名单记录（到期时间早于给定时刻）。 */
    @Modifying
    @Query(value = "delete from TokenBlacklist t where t.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}

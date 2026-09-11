package com.tiaoxiu.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

/**
 * 已作废令牌黑名单：登出时把当前会话的 token 摘要写入，
 * 后续请求即使携带有效签名的 token，只要命中黑名单即视为已登出。
 *
 * <p>配合 {@link User#tokenVersion} 实现「改密 / 冻结后踢下线」与「单设备登录」；
 * 黑名单只在令牌自然过期前的一段窗口内有效，过期后可清理。
 */
@Data
@Entity
@Comment("已作废令牌黑名单")
@Table(name = "token_blacklist", indexes = {@Index(name = "idx_token_blacklist_expires", columnList = "expires_at")})
public class TokenBlacklist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    @Comment("令牌 SHA-256 摘要")
    private String tokenHash;

    @Column(name = "user_id")
    @Comment("用户ID")
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    @Comment("令牌到期时间")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    @Comment("创建时间")
    private LocalDateTime createdAt;

    /** 持久化前回调：未指定创建时间时自动填充。 */
    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

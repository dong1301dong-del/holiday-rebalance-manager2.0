package com.tiaoxiu.service;

import com.tiaoxiu.entity.TokenBlacklist;
import com.tiaoxiu.repository.TokenBlacklistRepository;
import com.tiaoxiu.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 已作废令牌黑名单服务：登出时将当前 token 摘要入库，使其在到期前立即失效；
 * 并定时清理已过期的历史黑名单记录。
 */
@Service
public class TokenBlacklistService {
    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    private final TokenBlacklistRepository repository;
    private final JwtUtil jwtUtil;

    public TokenBlacklistService(TokenBlacklistRepository repository, JwtUtil jwtUtil) {
        this.repository = repository;
        this.jwtUtil = jwtUtil;
    }

    /** 登出时把 token 加入黑名单；token 为空、非法或已过期时视为无需处理并返回 false。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean blacklist(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = sha256Hex(token);
        if (repository.existsByTokenHash(hash)) {
            return true;
        }
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (Exception e) {
            return false;
        }
        LocalDateTime expiresAt = toLocalDateTime(claims.getExpiration());
        if (expiresAt == null) {
            return false;
        }
        TokenBlacklist row = new TokenBlacklist();
        row.setTokenHash(hash);
        row.setUserId(claims.get("uid", Long.class));
        row.setExpiresAt(expiresAt);
        repository.save(row);
        return true;
    }

    /** 判断 token 是否已被登出作废。 */
    @Transactional(readOnly = true)
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return repository.existsByTokenHash(sha256Hex(token));
    }

    /** 每天 03:10 清理已过期的黑名单记录，避免表无限增长。 */
    @Scheduled(cron = "0 10 3 * * ?")
    @Transactional
    public void purgeExpired() {
        try {
            int removed = repository.deleteExpired(LocalDateTime.now());
            if (removed > 0) {
                log.info("已清理过期的登出令牌黑名单记录 {} 条", removed);
            }
        } catch (Exception e) {
            log.warn("清理黑名单记录失败：{}", e.getMessage());
        }
    }

    /** 计算 token 的 SHA-256 十六进制摘要（用作黑名单中的唯一检索键）。 */
    static String sha256Hex(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit(b >> 4 & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算令牌摘要失败", e);
        }
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
    }
}

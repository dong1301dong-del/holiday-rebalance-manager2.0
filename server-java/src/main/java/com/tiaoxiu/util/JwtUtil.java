package com.tiaoxiu.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * JWT 令牌工具。
 *
 * <p>负责登录成功后签发令牌，以及请求进来时校验并解析令牌内容。
 * 令牌中除用户名外还携带了用户 ID、角色编码列表与令牌版本号，
 * 使得鉴权与数据权限判断无需每次回查数据库。
 *
 * <p>采用 HS256 对称签名，密钥来自配置项 {@code jwt.secret}。
 */
@Component
public class JwtUtil {

    /** 签名密钥，生产环境必须通过配置注入足够长度（HS256 要求 ≥ 32 字节）的随机串 */
    @Value("${jwt.secret}")
    private String secret;

    /** 令牌有效期（小时），默认 24 小时 */
    @Value("${jwt.expiration-hours:24}")
    private long expirationHours;

    /**
     * 根据密钥生成 HMAC 签名密钥。
     *
     * @return 用于签名 / 验签的 {@link SecretKey}
     */
    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 JWT 令牌。
     *
     * <p>载荷说明：
     * <ul>
     *   <li>{@code sub}：用户名</li>
     *   <li>{@code uid}：用户 ID，业务层用于过滤「本人数据」</li>
     *   <li>{@code roles}：角色编码列表，用于接口级权限判断</li>
     *   <li>{@code ver}：令牌版本号，与库中 User.tokenVersion 比对，实现改密 / 冻结后强制下线</li>
     * </ul>
     *
     * @param username     用户名（作为 subject）
     * @param userId       用户 ID
     * @param roles        角色编码列表
     * @param tokenVersion 令牌版本号
     * @return 紧凑格式的 JWT 字符串
     */
    public String generateToken(String username, Long userId, List<String> roles, Long tokenVersion) {
        Date now = new Date();
        // expirationHours 为小时，需换算成毫秒；乘 3600L*1000L 防止 int 溢出
        Date exp = new Date(now.getTime() + expirationHours * 3600L * 1000L);
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("roles", roles)
                .claim("ver", tokenVersion)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key())
                .compact();
    }

    /**
     * 校验签名并解析令牌载荷。
     *
     * @param token 客户端携带的 JWT 字符串（不含 "Bearer " 前缀）
     * @return 令牌中的 claims 集合
     * @throws io.jsonwebtoken.JwtException 签名不合法或令牌已过期时抛出，由调用方（JwtFilter）转换为 401
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 仅提取令牌中的版本号，供「令牌版本校验」使用。
     *
     * @param token JWT 字符串
     * @return 令牌版本号 {@code ver}；若令牌中无该字段则返回 null
     */
    public Long getTokenVersion(String token) {
        return parse(token).get("ver", Long.class);
    }
}

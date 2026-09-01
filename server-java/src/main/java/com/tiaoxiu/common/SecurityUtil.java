package com.tiaoxiu.common;

import java.util.List;

/**
 * 当前登录用户信息上下文（由 {@link com.tiaoxiu.filter.JwtFilter} 在每次请求开始时写入 ThreadLocal）。
 *
 * <p>业务层无需层层传递「当前用户」参数，直接从本类读取即可，例如请假/加班记录自动归属当前用户。
 *
 * <p><b>重要：</b>由于底层是 ThreadLocal 且容器线程会复用，JwtFilter 必须在 finally 中调用
 * {@link #clear()}，否则会串用户信息并造成内存泄漏。
 */
public class SecurityUtil {

    /** 当前登录用户 ID */
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    /** 当前登录用户名 */
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    /** 当前用户拥有的角色编码集合 */
    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();

    /** 令牌版本号，与 User.tokenVersion 比对可实现「改密/禁用后踢下线」 */
    private static final ThreadLocal<Long> TOKEN_VERSION = new ThreadLocal<>();

    /**
     * 写入当前请求的登录上下文（由 JwtFilter 在鉴权通过后调用）。
     *
     * @param userId       用户 ID
     * @param username     用户名
     * @param roles        角色编码列表
     * @param tokenVersion 令牌版本号
     */
    public static void set(Long userId, String username, List<String> roles, Long tokenVersion) {
        USER_ID.set(userId);
        USERNAME.set(username);
        ROLES.set(roles);
        TOKEN_VERSION.set(tokenVersion);
    }

    /**
     * 清空当前线程的登录上下文。
     *
     * <p>必须在请求结束时调用（remove 而非 set(null)），避免线程池复用导致用户信息串号与内存泄漏。
     */
    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        ROLES.remove();
        TOKEN_VERSION.remove();
    }

    public static Long getUserId() { return USER_ID.get(); }
    public static String getUsername() { return USERNAME.get(); }
    public static List<String> getRoles() { return ROLES.get(); }
    public static Long getTokenVersion() { return TOKEN_VERSION.get(); }

    /**
     * 判断当前用户是否拥有指定角色。
     *
     * @param role 角色编码，如 {@code "ADMIN"}、{@code "HR"}
     * @return 拥有该角色返回 true；未登录或不含该角色返回 false
     */
    public static boolean hasRole(String role) {
        List<String> roles = ROLES.get();
        return roles != null && roles.contains(role);
    }

    /**
     * 判断当前用户是否拥有其中任意一个角色（「或」关系）。
     *
     * @param roles 候选角色编码，可传多个
     * @return 命中任一角色返回 true；未登录或全部不匹配返回 false
     */
    public static boolean hasAnyRole(String... roles) {
        List<String> cur = ROLES.get();
        if (cur == null) return false;
        for (String r : roles) if (cur.contains(r)) return true;
        return false;
    }

    /**
     * 获取当前请求客户端 IP（用于系统日志留痕）。
     *
     * <p>优先读取 {@code X-Forwarded-For} 头，因为部署在 Nginx / 网关后面时
     * {@code getRemoteAddr()} 只能拿到代理服务器的地址；取逗号分隔的第一段才是真实客户端 IP。
     *
     * @return 客户端 IP 字符串；非 Web 请求（如定时任务线程）或解析失败时返回 null
     */
    public static String getClientIp() {
        try {
            org.springframework.web.context.request.ServletRequestAttributes attrs =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            jakarta.servlet.http.HttpServletRequest req = attrs.getRequest();
            String xip = req.getHeader("X-Forwarded-For");
            if (xip != null && !xip.isEmpty() && !"unknown".equalsIgnoreCase(xip)) {
                return xip.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}

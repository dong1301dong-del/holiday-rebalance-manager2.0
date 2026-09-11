package com.tiaoxiu.filter;

import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.repository.UserRepository;
import com.tiaoxiu.service.TokenBlacklistService;
import com.tiaoxiu.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * JWT 鉴权过滤器：本系统后端接口的统一入口守卫。
 *
 * <p>职责：对每个进入的请求做三件事——
 * <ol>
 *   <li>放行 OPTIONS 预检、白名单路径与前端静态资源；</li>
 *   <li>校验 {@code Authorization: Bearer <token>}，非法则返回 401 JSON；</li>
 *   <li>校验通过后把用户信息写入 {@link SecurityUtil}，供业务层直接取用。</li>
 * </ol>
 *
 * <p>继承 {@link OncePerRequestFilter} 保证一次请求只执行一次，避免内部转发时重复鉴权。
 *
 * <p>注意：本过滤器只做「是否已登录 + 账号是否有效」的认证，
 * 具体某个接口需要什么角色由各 Controller / Service 内部判断。
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * 构造器注入。
     *
     * @param jwtUtil               JWT 解析工具
     * @param userRepository        用户仓储，用于校验账号状态与令牌版本号
     * @param tokenBlacklistService 令牌黑名单服务，用于登出后失效校验
     */
    public JwtFilter(JwtUtil jwtUtil, UserRepository userRepository, TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /** 无需登录即可访问的路径前缀白名单：登录接口、健康检查、错误转发 */
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login", "/api/auth/health", "/api/health", "/error"
    );

    /** 强制改密期间仍允许访问的路径，避免用户被锁死在「必须改密」页面之外 */
    private static final List<String> MUST_CHANGE_PWD_ALLOWED = List.of(
            "/api/auth/change-password", "/api/auth/me", "/api/auth/logout"
    );

    /** 接口文档（Swagger）路径前缀，仅 ADMIN 可访问 */
    private static final List<String> SWAGGER_PREFIXES = List.of("/swagger-ui", "/v3/api-docs");

    /**
     * 请求过滤主逻辑。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param chain    过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException      写出 401 响应时的 IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (request.getMethod().equals("OPTIONS")
                || WHITE_LIST.stream().anyMatch(path::startsWith)
                || isPublicResource(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "缺少令牌");
            return;
        }
        // "Bearer " 共 7 个字符，截掉前缀才是纯 JWT 串
        String token = header.substring(7);
        try {
            Claims claims = jwtUtil.parse(token);
            // 登出后令牌进入黑名单，必须立即失效，即使它本身尚未过期
            if (tokenBlacklistService.isBlacklisted(token)) {
                writeError(response, HttpStatus.UNAUTHORIZED.value(), "登录状态已失效，请重新登录");
                return;
            }
            Long uid = claims.get("uid", Long.class);
            Long ver = claims.get("ver", Long.class);
            User user = userRepository.findById(uid).orElse(null);
            if (user == null) {
                writeError(response, HttpStatus.UNAUTHORIZED.value(), "用户不存在");
                return;
            }
            // 令牌本身可能仍然合法，但账号已被冻结/删除，此时必须拒绝访问
            if (!User.STATUS_ACTIVE.equals(user.getStatus())) {
                writeError(response, HttpStatus.UNAUTHORIZED.value(), "账号已被冻结或删除");
                return;
            }
            if (!user.getTokenVersion().equals(ver)) {
                // 单设备登录：新设备登录/重置密码会使旧 token 的 ver 失效
                writeError(response, HttpStatus.UNAUTHORIZED.value(), "登录状态已失效，请重新登录");
                return;
            }
            List<String> roles = claims.get("roles", List.class);
            // 强制改密：除改密/me/登出等必要接口外，其余一律拦截，避免绕过
            if (Boolean.TRUE.equals(user.getMustChangePwd())
                    && MUST_CHANGE_PWD_ALLOWED.stream().noneMatch(path::startsWith)) {
                writeError(response, HttpStatus.FORBIDDEN.value(), "请先修改初始密码后再继续操作");
                return;
            }
            // 接口文档仅限管理员访问，防止信息泄露
            if (SWAGGER_PREFIXES.stream().anyMatch(path::startsWith)
                    && (roles == null || !roles.contains("ADMIN"))) {
                writeError(response, HttpStatus.FORBIDDEN.value(), "无权限访问接口文档");
                return;
            }
            SecurityUtil.set(user.getId(), user.getUsername(), roles, user.getTokenVersion());
            try {
                chain.doFilter(request, response);
            } finally {
                // 必须在 finally 中清理 ThreadLocal，否则线程池复用会串用户信息
                SecurityUtil.clear();
            }
        } catch (Exception e) {
            writeError(response, HttpStatus.UNAUTHORIZED.value(), "令牌无效或已过期");
        }
    }

    /**
     * 判断路径是否为可匿名访问的前端静态资源。
     *
     * <p>设计约定：所有 {@code /api/} 开头的路径一律需要鉴权，其余按静态资源规则判断，
     * 这样新增接口时无需维护白名单，默认就是安全的。
     *
     * @param path 请求 URI
     * @return true 表示可匿名访问
     */
    private boolean isPublicResource(String path) {
        // Swagger 接口文档路径即使以 / 开头也不算公开静态资源，需经过上面的鉴权拦截
        if (SWAGGER_PREFIXES.stream().anyMatch(path::startsWith)) return false;
        // 前端由 Spring 直接托管时，首页与静态资源需匿名可访问；API 一律需要鉴权
        if (path.startsWith("/api/")) return false;
        if (path.equals("/") || path.equals("/index.html")) return true;
        if (path.startsWith("/assets/")) return true;
        return path.endsWith(".ico") || path.endsWith(".png") || path.endsWith(".svg")
                || path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".json")
                || path.endsWith(".woff2") || path.endsWith(".woff") || path.endsWith(".ttf");
    }

    /**
     * 直接写出错误 JSON 响应并中断请求。
     *
     * <p>这里手动拼 JSON 而不是抛异常，是因为过滤器位于进入 Spring MVC 之前，
     * 抛出的异常不会被 {@link com.tiaoxiu.common.GlobalExceptionHandler} 捕获。
     *
     * @param response HTTP 响应
     * @param status   HTTP 状态码（同时作为返回 JSON 的 code）
     * @param msg      返回给前端的错误描述
     * @throws IOException 写响应体时可能抛出
     */
    private void writeError(HttpServletResponse response, int status, String msg) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + msg + "\"}");
    }
}

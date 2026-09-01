package com.tiaoxiu.controller;

import com.tiaoxiu.common.Result;
import com.tiaoxiu.dto.AuthDto;
import com.tiaoxiu.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证相关接口：登录、改密、当前用户信息、健康检查。
 *
 * <p>统一路由前缀：{@code /api/auth}。
 *
 * <p>权限要求：除 login 与 health 系列接口在 {@link com.tiaoxiu.filter.JwtFilter} 的白名单中（匿名可访问）外，
 * 其余接口均需携带有效 JWT 令牌。
 */
@Tag(name = "认证管理", description = "登录、改密、当前会话信息")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 构造器注入。
     *
     * @param authService 认证服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户登录。
     *
     * <p>请求路径：{@code POST /api/auth/login}；所需权限：无（白名单匿名访问）。
     *
     * @param req 登录请求（账号 / 密码）
     * @return 成功返回 code=0 及 token、用户信息、动态菜单与权限；
     *         失败抛 {@link com.tiaoxiu.common.BizException}，返回 code≠0 及「用户名或密码错误」等提示
     */
    @Operation(summary = "用户登录", description = "校验账号密码，返回 JWT token 与当前用户菜单权限")
    @PostMapping("/login")
    public Result<AuthDto.LoginResponse> login(@RequestBody AuthDto.LoginRequest req) {
        return Result.ok(authService.login(req.getUsername(), req.getPassword()));
    }

    /**
     * 修改当前登录用户密码。
     *
     * <p>请求路径：{@code POST /api/auth/change-password}；所需权限：已登录。
     *
     * @param req 旧密码 + 新密码
     * @return 成功返回 code=0 及新 token（改密后旧 token 失效，前端需替换本地令牌）；
     *         失败返回原密码错误或密码强度不足的提示
     */
    @Operation(summary = "修改密码", description = "修改当前登录用户密码，成功后返回新 token")
    @PostMapping("/change-password")
    public Result<Map<String, String>> changePassword(@RequestBody AuthDto.ChangePasswordRequest req) {
        String token = authService.changePassword(req.getOldPassword(), req.getNewPassword());
        return Result.ok(Map.of("token", token));
    }

    /**
     * 获取当前登录用户信息（前端刷新页面后恢复会话用）。
     *
     * <p>请求路径：{@code GET /api/auth/me}；所需权限：已登录。
     *
     * @return 成功返回 code=0 及用户信息、角色、可见菜单与权限编码；失败返回「未登录」
     */
    @Operation(summary = "当前用户信息", description = "获取当前 token 对应的用户详情与权限菜单")
    @GetMapping("/me")
    public Result<AuthDto.LoginResponse> me() {
        return Result.ok(authService.me());
    }

    /**
     * 健康检查接口。
     *
     * <p>请求路径：{@code GET /api/auth/health}；所需权限：无（白名单匿名访问）。
     *
     * @return 固定返回 code=0 且 data 为 "ok"
     */
    @Operation(summary = "健康检查", description = "服务存活探针")
    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("ok");
    }

    /**
     * 根路径健康检查（兼容部分监控探针）。
     *
     * <p>请求路径：{@code GET /api/auth/health-root}；所需权限：无（白名单匿名访问）。
     *
     * @return 固定返回 code=0 且 data 为 "ok"
     */
    @Operation(summary = "根路径健康检查", description = "兼容部分监控探针的根路径健康检查")
    @GetMapping("/health-root")
    public Result<String> healthRoot() {
        return Result.ok("ok");
    }
}

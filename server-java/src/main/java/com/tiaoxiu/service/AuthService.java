package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.AuthDto;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.repository.UserRepository;
import com.tiaoxiu.util.JwtUtil;
import com.tiaoxiu.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 认证：登录、改密、单设备登录（tokenVersion 失效旧令牌）、登录锁定、下发动态菜单与权限。 */
@Service
public class AuthService {

    /** 连续密码错误达到该次数后锁定账号，默认 5 次 */
    @Value("${app.login-lock-threshold:5}")
    private int lockThreshold;

    /** 账号锁定时长（分钟），默认 15 分钟 */
    @Value("${app.login-lock-minutes:15}")
    private int lockMinutes;

    private final UserRepository userRepository;
    private final UserService userService;
    private final ResourceService resourceService;
    private final JwtUtil jwtUtil;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入。
     *
     * @param userRepository  用户仓储
     * @param userService     用户服务（取角色编码）
     * @param resourceService 资源服务（构建动态菜单与权限）
     * @param jwtUtil         JWT 工具
     * @param auditLogService 审计日志服务
     */
    public AuthService(UserRepository userRepository, UserService userService,
                       ResourceService resourceService, JwtUtil jwtUtil, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.resourceService = resourceService;
        this.jwtUtil = jwtUtil;
        this.auditLogService = auditLogService;
    }

    /**
     * 登录：校验密码 → 检查锁定 → 签发令牌 → 下发动态菜单与权限。
     *
     * <p>安全设计：
     * <ul>
     *   <li>用户不存在与密码错误返回同样的提示，避免暴露账号是否注册；</li>
     *   <li>连续错误达阈值即锁定账号一段时间，抵御暴力破解；</li>
     *   <li>登录成功自增 tokenVersion，使该用户此前签发的令牌全部失效（单设备登录）。</li>
     * </ul>
     *
     * @param username 登录账号
     * @param password 明文密码
     * @return 登录响应（令牌、用户信息、菜单、权限）
     * @throws BizException 账号不存在、密码错误、账号锁定或状态异常时抛出
     */
    @Transactional
    public AuthDto.LoginResponse login(String username, String password) {
        User user = userRepository.findByUsername(username).orElse(null);
        // 统一提示文案，不区分「账号不存在」与「密码错误」
        if (user == null) throw new BizException("用户名或密码错误");

        // 锁定判断
        if (user.getLoginLockedUntil() != null && user.getLoginLockedUntil().isAfter(LocalDateTime.now())) {
            long remain = Duration.between(LocalDateTime.now(), user.getLoginLockedUntil()).toMinutes();
            throw new BizException("账号已锁定，请于 " + Math.max(remain, 1) + " 分钟后重试");
        }

        if (!PasswordUtil.matches(password, user.getPassword())) {
            int fails = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
            user.setLoginFailCount(fails);
            if (fails >= lockThreshold) {
                user.setLoginLockedUntil(LocalDateTime.now().plusMinutes(lockMinutes));
                // 锁定后清零计数，避免解锁瞬间立刻又被锁定
                user.setLoginFailCount(0);
                userRepository.save(user);
                auditLogService.logAs(user.getId(), AuditLog.MODULE_AUTH, "登录", user.getUsername(),
                        "密码错误次数过多，账号已锁定 " + lockMinutes + " 分钟", AuditLog.LEVEL_WARN, AuditLog.RESULT_FAIL);
                throw new BizException("密码错误次数过多，账号已锁定 " + lockMinutes + " 分钟");
            }
            userRepository.save(user);
            auditLogService.logAs(user.getId(), AuditLog.MODULE_AUTH, "登录", user.getUsername(),
                    "密码错误（第 " + fails + " 次）", AuditLog.LEVEL_WARN, AuditLog.RESULT_FAIL);
            throw new BizException("用户名或密码错误");
        }

        if (!User.STATUS_ACTIVE.equals(user.getStatus())) {
            auditLogService.logAs(user.getId(), AuditLog.MODULE_AUTH, "登录", user.getUsername(),
                    "账号状态异常：" + user.getStatus(), AuditLog.LEVEL_WARN, AuditLog.RESULT_FAIL);
            throw new BizException("账号已被冻结或删除，请联系管理员");
        }

        // 登录成功：重置失败计数
        user.setLoginFailCount(0);
        user.setLoginLockedUntil(null);
        List<String> roles = userService.getRoleCodes(user.getId());
        boolean isAdmin = roles.contains("ADMIN");
        if (!isAdmin) {
            // 非管理员保持单设备登录：新登录使旧令牌失效
            user.setTokenVersion(user.getTokenVersion() + 1);
        }
        // 管理员不提升 tokenVersion：允许多设备同时登录，其此前签发的令牌保持有效
        userRepository.save(user);

        AuthDto.LoginResponse resp = buildResponse(user, roles, user.getTokenVersion());
        resp.setMustChangePwd(user.getMustChangePwd());
        auditLogService.logAs(user.getId(), AuditLog.MODULE_AUTH, "登录", user.getUsername(),
                "登录成功，角色：" + String.join("/", roles), AuditLog.LEVEL_INFO, AuditLog.RESULT_SUCCESS);
        return resp;
    }

    /**
     * 修改当前登录用户的密码，并重新签发令牌。
     *
     * <p>改密后自增 tokenVersion，使该用户所有旧令牌立即失效，强制各处重新登录。
     *
     * @param oldPassword 原密码，用于身份确认
     * @param newPassword 新密码，需达到一级及以上强度，且不能与原密码相同
     * @return 新签发的 JWT 令牌
     * @throws BizException 未登录、原密码错误、新旧密码相同或新密码强度不足时抛出
     */
    @Transactional
    public String changePassword(String oldPassword, String newPassword) {
        Long uid = SecurityUtil.getUserId();
        if (uid == null) throw new BizException("未登录");
        User user = userRepository.findById(uid).orElseThrow(() -> new BizException("用户不存在"));
        if (!PasswordUtil.matches(oldPassword, user.getPassword())) throw new BizException("原密码错误");
        // 新密码不得与原密码相同，否则改密没有实际意义
        if (oldPassword != null && oldPassword.equals(newPassword)) throw new BizException("新密码不能与原密码相同");
        if (!PasswordUtil.isStrong(newPassword)) throw new BizException("新密码强度不足：" + PasswordUtil.strongRuleTip());
        user.setPassword(PasswordUtil.encode(newPassword));
        user.setMustChangePwd(false);
        user.setTokenVersion(user.getTokenVersion() + 1); // 改密使所有旧令牌失效
        userRepository.save(user);
        List<String> roles = userService.getRoleCodes(user.getId());
        return jwtUtil.generateToken(user.getUsername(), user.getId(), roles, user.getTokenVersion());
    }

    /**
     * 获取当前登录用户的信息与动态菜单（前端刷新页面后恢复会话用）。
     *
     * @return 当前用户信息、菜单与权限
     * @throws BizException 未登录或用户不存在时抛出
     */
    public AuthDto.LoginResponse me() {
        Long uid = SecurityUtil.getUserId();
        if (uid == null) throw new BizException("未登录");
        User user = userRepository.findById(uid).orElseThrow(() -> new BizException("用户不存在"));
        List<String> roles = userService.getRoleCodes(user.getId());
        AuthDto.LoginResponse resp = buildResponse(user, roles, user.getTokenVersion());
        resp.setMustChangePwd(user.getMustChangePwd());
        return resp;
    }

    /**
     * 组装登录响应：签发令牌 + 用户信息 + 动态菜单 + 权限编码。
     *
     * @param user         用户实体
     * @param roles        角色编码列表
     * @param tokenVersion 令牌版本号
     * @return 登录响应
     */
    private AuthDto.LoginResponse buildResponse(User user, List<String> roles, Long tokenVersion) {
        String token = jwtUtil.generateToken(user.getUsername(), user.getId(), roles, tokenVersion);
        ResourceService.MenuResult menu = resourceService.buildMenu(roles);

        AuthDto.UserInfo info = new AuthDto.UserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setName(user.getName());
        info.setDepartment(user.getDepartment());
        info.setPosition(user.getPosition());
        info.setRoles(roles);

        AuthDto.LoginResponse resp = new AuthDto.LoginResponse();
        resp.setToken(token);
        resp.setUser(info);
        resp.setMenus(menu.menus);
        resp.setPermissions(menu.permissions);
        return resp;
    }
}

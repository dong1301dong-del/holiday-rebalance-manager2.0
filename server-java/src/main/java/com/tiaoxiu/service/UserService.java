package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.UserDto;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.entity.Role;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.entity.UserRole;
import com.tiaoxiu.repository.RoleRepository;
import com.tiaoxiu.repository.UserRepository;
import com.tiaoxiu.repository.UserRoleRepository;
import com.tiaoxiu.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/** 成员（用户）管理：增删改、分配角色、重置密码、冻结/解冻、软删除。 */
@Service
public class UserService {

    /** 新增用户未指定密码时使用的默认密码；因强制首次改密，风险可控 */
    @Value("${app.default-password:Abc_123456}")
    private String defaultPassword;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BalanceService balanceService;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入。
     *
     * @param userRepository     用户仓储
     * @param roleRepository     角色仓储（按编码反查角色）
     * @param userRoleRepository 用户角色仓储
     * @param balanceService     余额引擎（列表展示实时余额）
     * @param auditLogService    审计日志服务
     */
    public UserService(UserRepository userRepository, RoleRepository roleRepository, UserRoleRepository userRoleRepository,
                       BalanceService balanceService, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
    }

    /**
     * 查询全部未删除用户。
     *
     * @return 用户列表
     */
    public List<User> list() {
        return userRepository.findByStatusNot(User.STATUS_DELETED);
    }

    /**
     * 按 ID 获取用户。
     *
     * @param id 用户 ID
     * @return 用户实体
     * @throws BizException 用户不存在时抛出
     */
    public User get(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new BizException("成员不存在"));
    }

    /**
     * 对外用户列表：在实体基础上补充角色编码与实时调休余额。
     *
     * @return 用户响应列表
     */
    public List<UserDto.UserResponse> listResponses() {
        return list().stream().filter(u -> !Boolean.TRUE.equals(u.getBuiltin())).map(this::toResponse).collect(Collectors.toList());
    }

    /** 判断用户是否为系统内置账号（内置管理员受自我保护约束）。 */
    private boolean isBuiltin(User u) {
        return u != null && Boolean.TRUE.equals(u.getBuiltin());
    }

    /**
     * 禁止管理员对自己执行敏感操作（冻结/删除等），避免把自己锁在系统之外。
     *
     * @param targetId 目标用户 ID
     * @param action   操作描述，用于异常提示
     * @throws BizException 操作人是目标本人时抛出
     */
    private void requireNotSelf(Long targetId, String action) {
        Long me = SecurityUtil.getUserId();
        if (me != null && me.equals(targetId)) {
            throw new BizException("不能" + action + "自己，请由其他管理员操作");
        }
    }

    /**
     * 对外用户详情：在实体基础上补充角色编码与实时调休余额。
     *
     * @param id 用户 ID
     * @return 用户响应
     * @throws BizException 用户不存在时抛出
     */
    public UserDto.UserResponse getResponse(Long id) {
        return toResponse(get(id));
    }

    /**
     * 实体转响应 DTO。
     *
     * @param u 用户实体
     * @return 用户响应（含角色与余额）
     */
    private UserDto.UserResponse toResponse(User u) {
        UserDto.UserResponse r = new UserDto.UserResponse();
        r.setId(u.getId());
        r.setUsername(u.getUsername());
        r.setName(u.getName());
        r.setDepartment(u.getDepartment());
        r.setPosition(u.getPosition());
        r.setStatus(u.getStatus());
        r.setMustChangePwd(u.getMustChangePwd());
        r.setRoles(getRoleCodes(u.getId()));
        r.setBalance(balanceService.currentBalance(u.getId()).setScale(2, java.math.RoundingMode.HALF_UP));
        r.setHireDate(u.getHireDate());
        r.setEmail(u.getEmail());
        r.setPhone(u.getPhone());
        r.setCreatedAt(u.getCreatedAt());
        return r;
    }

    /**
     * 新增用户：账号查重 → 姓名重名校验 → 密码强度校验 → 落库 → 分配角色。
     *
     * <p>新用户一律置 {@code mustChangePwd = true}，强制首次登录改密；
     * 未指定密码时使用配置的默认密码。
     *
     * @param req        新增请求
     * @param operatorId 操作人 ID（用于审计留痕）
     * @return 保存后的用户
     * @throws BizException 用户名/姓名已存在或密码强度不足时抛出
     */
    @Transactional
    public User create(UserDto.CreateRequest req, Long operatorId) {
        User existing = userRepository.findByUsername(req.getUsername()).orElse(null);
        if (existing != null) {
            if (isBuiltin(existing)) {
                throw new BizException("该用户名为系统内置账号保留，不可重复创建");
            }
            throw new BizException("用户名已存在");
        }
        if (StringUtils.hasText(req.getName()) && existsName(req.getName(), null)) {
            throw new BizException("姓名已存在：" + req.getName());
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setName(req.getName());
        u.setDepartment(req.getDepartment());
        u.setPosition(req.getPosition());
        u.setHireDate(req.getHireDate());
        u.setEmail(req.getEmail());
        u.setPhone(req.getPhone());
        u.setStatus(User.STATUS_ACTIVE);
        u.setMustChangePwd(true);
        String pwd = StringUtils.hasText(req.getPassword()) ? req.getPassword() : defaultPassword;
        if (!PasswordUtil.isStrong(pwd)) throw new BizException("密码强度不足：" + PasswordUtil.strongRuleTip());
        u.setPassword(PasswordUtil.encode(pwd));
        User saved = userRepository.save(u);
        assignRoles(saved.getId(), req.getRoleCodes());
        auditLogService.log(AuditLog.MODULE_USER, "新增用户", saved.getName(),
                "新增用户 " + saved.getUsername() + "，角色：" + req.getRoleCodes());
        return saved;
    }

    /**
     * 编辑用户资料：仅覆盖传入的非空字段（增量更新），并可同步调整角色。
     *
     * @param id  用户 ID
     * @param req 编辑请求，未传的字段保持原值
     * @return 保存后的用户
     * @throws BizException 用户不存在或新用户名已被占用时抛出
     */
    @Transactional
    public User update(Long id, UserDto.UpdateRequest req) {
        User u = get(id);
        if (isBuiltin(u)) {
            if (StringUtils.hasText(req.getUsername()) && !req.getUsername().equals(u.getUsername())) {
                throw new BizException("内置管理员的登录账号不可修改");
            }
            if (StringUtils.hasText(req.getStatus()) && !User.STATUS_ACTIVE.equals(req.getStatus())) {
                throw new BizException("内置管理员不可被冻结或删除");
            }
            if (!(req.getRoleCodes() == null || req.getRoleCodes().size() == 1 && Role.CODE_ADMIN.equals(req.getRoleCodes().get(0)))) {
                throw new BizException("内置管理员的角色不可修改");
            }
        }
        String before = describe(u, getRoleCodes(id));
        if (StringUtils.hasText(req.getUsername())) {
            if (!req.getUsername().equals(u.getUsername()) && userRepository.existsByUsername(req.getUsername())) {
                throw new BizException("用户名已存在");
            }
            u.setUsername(req.getUsername());
        }
        if (StringUtils.hasText(req.getName())) {
            if (existsName(req.getName(), id)) throw new BizException("姓名已存在：" + req.getName());
            u.setName(req.getName());
        }
        if (req.getDepartment() != null) u.setDepartment(req.getDepartment());
        if (req.getPosition() != null) u.setPosition(req.getPosition());
        if (req.getHireDate() != null) u.setHireDate(req.getHireDate());
        if (req.getEmail() != null) u.setEmail(req.getEmail());
        if (req.getPhone() != null) u.setPhone(req.getPhone());
        if (StringUtils.hasText(req.getStatus())) u.setStatus(req.getStatus());
        User saved = userRepository.save(u);
        if (req.getRoleCodes() != null) assignRoles(id, req.getRoleCodes());
        String after = describe(saved, getRoleCodes(id));
        auditLogService.logChange(AuditLog.MODULE_USER, "编辑用户", saved.getName(), "编辑用户信息", before, after, AuditLog.LEVEL_INFO);
        return saved;
    }

    /**
     * 生成用户的一句话摘要，用于审计日志的变更前 / 变更后值。
     *
     * @param u         用户实体
     * @param roleCodes 角色编码列表
     * @return 摘要文本
     */
    private String describe(User u, List<String> roleCodes) {
        return String.format("账号=%s,姓名=%s,部门=%s,职位=%s,角色=%s,状态=%s",
                u.getUsername(), u.getName(), u.getDepartment(), u.getPosition(), roleCodes, u.getStatus());
    }

    /**
     * 判断非删除状态下是否已存在同名员工（用于新增 / 编辑时的重名校验）。
     *
     * @param name   待校验姓名
     * @param excludeId 编辑时排除的用户 ID，新增可传 null
     * @return 存在返回 true
     */
    private boolean existsName(String name, Long excludeId) {
        String n = name.trim();
        return userRepository.findByStatusNot(User.STATUS_DELETED).stream()
                .filter(u -> n.equals(StringUtils.trimWhitespace(u.getName())))
                .anyMatch(u -> excludeId == null || !excludeId.equals(u.getId()));
    }

    /**
     * 分配角色：先清空该用户原有角色，再按编码批量重新写入（整体覆盖语义）。
     *
     * @param userId    用户 ID
     * @param roleCodes 角色编码列表；为 null 表示清空所有角色；不存在的编码会被忽略
     * @throws BizException 用户不存在时抛出
     */
    @Transactional
    public void assignRoles(Long userId, List<String> roleCodes) {
        User target = userRepository.findById(userId).orElseThrow(() -> new BizException("成员不存在"));
        if (isBuiltin(target)) {
            if (roleCodes == null || roleCodes.size() != 1 || !Role.CODE_ADMIN.equals(roleCodes.get(0))) {
                throw new BizException("内置管理员的角色不可修改");
            }
            return;
        }
        // 管理员唯一性约束：系统有且只能有一个 admin 账号，且其他账号不能变更为 admin 角色
        List<String> currentCodes = getRoleCodes(userId);
        boolean wantsAdmin = roleCodes != null && roleCodes.contains(Role.CODE_ADMIN);
        boolean isCurrentlyAdmin = currentCodes.contains(Role.CODE_ADMIN);
        long adminCount = countAdmins();
        if (wantsAdmin && !isCurrentlyAdmin && adminCount >= 1) {
            throw new BizException("系统已存在管理员账号，不能再创建第二个管理员");
        }
        if (!wantsAdmin && isCurrentlyAdmin && adminCount <= 1) {
            throw new BizException("不能取消唯一管理员的权限，系统必须保留一个管理员");
        }
        // 全量覆盖而非增量合并：删除旧绑定再写新绑定，逻辑简单且不会产生残留权限
        userRoleRepository.deleteByUserId(userId);
        if (roleCodes != null) {
            List<Role> roles = roleRepository.findByCodeIn(roleCodes);
            List<UserRole> list = new ArrayList<>();
            for (Role r : roles) {
                UserRole ur = new UserRole();
                ur.setUserId(userId);
                ur.setRoleId(r.getId());
                list.add(ur);
            }
            userRoleRepository.saveAll(list);
        }
        if (!new HashSet<>(currentCodes).equals(new HashSet<>(roleCodes == null ? List.of() : roleCodes))) {
            target.setTokenVersion(target.getTokenVersion() + 1L);
            userRepository.save(target);
        }
        // 角色变更属于权限敏感操作，与新增/编辑/重置/停用/删除一致写入审计日志（记录变更前后角色）
        String beforeCodes = currentCodes.isEmpty() ? "（无）" : String.join(",", currentCodes);
        String afterCodes = (roleCodes == null || roleCodes.isEmpty()) ? "（无）" : String.join(",", roleCodes);
        auditLogService.logChange(AuditLog.MODULE_USER, "分配角色",
                target.getName() + " " + target.getUsername(), "分配角色 " + target.getUsername(),
                beforeCodes, afterCodes, AuditLog.LEVEL_WARN);
    }

    /**
     * 统计当前非删除状态下的管理员账号数量。
     *
     * @return 管理员（ADMIN 角色）账号数，恒为 0 或 1（由 {@link #assignRoles} 约束保证唯一）
     */
    private long countAdmins() {
        Role adminRole = roleRepository.findByCode(Role.CODE_ADMIN).orElse(null);
        if (adminRole == null) return 0;
        List<Long> adminUserIds = userRoleRepository.findByRoleId(adminRole.getId()).stream()
                .map(UserRole::getUserId).collect(Collectors.toList());
        if (adminUserIds.isEmpty()) return 0;
        return userRepository.findByStatusNot(User.STATUS_DELETED).stream()
                .filter(u -> adminUserIds.contains(u.getId())).count();
    }

    /**
     * 重置密码：校验强度后加密存储，并自增 tokenVersion 使该用户旧会话全部失效。
     *
     * @param userId      用户 ID
     * @param newPassword 新密码
     * @throws BizException 用户不存在或密码强度不足时抛出
     */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        User u = get(userId);
        if (isBuiltin(u)) {
            throw new BizException("内置管理员的密码不可重置。如需变更，请由运维设置环境变量 ADMIN_DEFAULT_PASSWORD 后重启服务");
        }
        if (!PasswordUtil.isStrong(newPassword)) throw new BizException("密码强度不足：" + PasswordUtil.strongRuleTip());
        u.setPassword(PasswordUtil.encode(newPassword));
        u.setMustChangePwd(true);
        u.setTokenVersion(u.getTokenVersion() + 1); // 使旧会话失效
        userRepository.save(u);
        auditLogService.log(AuditLog.MODULE_USER, "重置密码", u.getName(), "重置 " + u.getUsername() + " 的登录密码");
    }

    /**
     * 冻结 / 解冻用户，并自增 tokenVersion 使旧会话失效（冻结后立即生效）。
     *
     * @param userId 用户 ID
     * @param status 目标状态，仅允许 ACTIVE 或 FROZEN
     * @throws BizException 用户不存在或状态值非法时抛出
     */
    @Transactional
    public void setStatus(Long userId, String status) {
        User u = get(userId);
        requireNotSelf(userId, "冻结或启用");
        if (isBuiltin(u)) {
            throw new BizException("内置管理员不可被冻结");
        }
        if (!User.STATUS_ACTIVE.equals(status) && !User.STATUS_FROZEN.equals(status))
            throw new BizException("非法状态");
        u.setStatus(status);
        u.setTokenVersion(u.getTokenVersion() + 1); // 冻结/解冻使旧会话失效
        userRepository.save(u);
        auditLogService.log(AuditLog.MODULE_USER, User.STATUS_FROZEN.equals(status) ? "冻结用户" : "启用用户",
                u.getName(), "账号状态变更为 " + status);
    }

    /**
     * 删除用户：逻辑删除（状态置 DELETED），保留历史业务数据的关联完整性，同时使旧会话失效。
     *
     * @param userId 用户 ID
     * @throws BizException 用户不存在时抛出
     */
    @Transactional
    public void delete(Long userId) {
        User u = get(userId);
        requireNotSelf(userId, "删除");
        if (isBuiltin(u)) {
            throw new BizException("内置管理员不可删除");
        }
        u.setStatus(User.STATUS_DELETED);
        u.setTokenVersion(u.getTokenVersion() + 1);
        userRepository.save(u);
        auditLogService.log(AuditLog.MODULE_USER, "删除用户", u.getName(), "删除用户 " + u.getUsername());
    }

    /**
     * 判断当前登录用户是否有全员数据查看权限：管理员与录入员看全员，普通员工仅看本人。
     *
     * @return 可查看全员返回 true
     */
    public boolean canViewAll() {
        return SecurityUtil.hasAnyRole("ADMIN", "CLERK");
    }

    /**
     * 查询指定用户拥有的角色编码列表。
     *
     * @param userId 用户 ID
     * @return 角色编码列表；无角色时为空列表
     */
    public List<String> getRoleCodes(Long userId) {
        List<Long> roleIds = userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRoleId).collect(Collectors.toList());
        return roleRepository.findAllById(roleIds).stream().map(Role::getCode).collect(Collectors.toList());
    }
}

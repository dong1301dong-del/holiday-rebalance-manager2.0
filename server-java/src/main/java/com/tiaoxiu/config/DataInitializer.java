package com.tiaoxiu.config;

import com.tiaoxiu.entity.*;
import com.tiaoxiu.repository.*;
import com.tiaoxiu.util.PasswordUtil;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 系统启动时自动播种基础数据。
 * <p>
 * 负责初始化：4 个内置角色、资源树（菜单与按钮权限）、管理员账号、默认配置、2026 节假日。
 * 所有写入逻辑均为幂等，仅在对应表为空或资源树版本过旧时才会写入/重建。
 */
@Component
@Order(1)
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final ResourceRepository resourceRepository;
    private final RoleResourceRepository roleResourceRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final SystemConfigRepository configRepository;
    private final HolidayRepository holidayRepository;

    /**
     * 构造器注入所需的 Repository。
     *
     * @param roleRepository         角色仓储
     * @param resourceRepository     资源（菜单 / 按钮）仓储
     * @param roleResourceRepository 角色与资源绑定关系仓储
     * @param userRepository         用户仓储
     * @param userRoleRepository     用户与角色绑定关系仓储
     * @param configRepository       系统配置仓储
     * @param holidayRepository      节假日仓储
     */
    public DataInitializer(RoleRepository roleRepository, ResourceRepository resourceRepository,
                           RoleResourceRepository roleResourceRepository, UserRepository userRepository,
                           UserRoleRepository userRoleRepository, SystemConfigRepository configRepository,
                           HolidayRepository holidayRepository) {
        this.roleRepository = roleRepository;
        this.resourceRepository = resourceRepository;
        this.roleResourceRepository = roleResourceRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.configRepository = configRepository;
        this.holidayRepository = holidayRepository;
    }

    /**
     * 应用启动完成后执行（Spring Boot CommandLineRunner 回调）。
     *
     * <p>整体处于同一事务中，任一步骤失败则全部回滚，避免留下「半初始化」的脏数据。
     * 执行顺序有依赖关系：必须先有角色与资源，才能建立绑定，最后再创建管理员账号。
     *
     * @param args 启动参数（本实现不使用）
     */
    @Override
    @Transactional
    public void run(String... args) {
        migrateRolesAndCleanResources();
        seedRoles();
        Map<String, Long> resIds = seedResources();
        seedRoleResources(resIds);
        seedAdmin();
        seedConfig();
        seed2026Holidays();
    }

    /**
     * 升级清理：删除已废弃的 READONLY 角色，同步三个内置角色名称，
     * 并清空资源表/角色资源绑定表以重新生成新菜单树（当前为重构期开发环境，允许全量重建）。
     */
    private void migrateRolesAndCleanResources() {
        // 1. 删除废弃 READONLY 角色
        roleRepository.findByCode("READONLY").ifPresent(r -> roleRepository.delete(r));

        // 2. 更新现有内置角色名称
        updateRoleIfExists(Role.CODE_ADMIN, "管理员", "全部权限");
        updateRoleIfExists(Role.CODE_CLERK, "录入员", "工作台、全员调休查看、加班录入记录、调休使用记录、统计图展示、系统日历、用户管理");
        updateRoleIfExists(Role.CODE_EMPLOYEE, "普通员工", "工作台、加班录入记录、调休使用记录，仅可查看本人数据");

        // 3. 判定资源树是否需要全量重建。
        //    注意判定口径只能是「应当存在的菜单缺失」或「名称与预期不符」，
        //    绝不能写成「某某菜单存在就重建」——那样在正常状态下每次启动都会清空重建。
        boolean hasLegacy = resourceRepository.findByCode("dict:view").isPresent()
                || resourceRepository.findByCode("system:view").isPresent();
        boolean missingNewMenus = !resourceRepository.findByCode("allStaffLeave:view").isPresent()
                || !resourceRepository.findByCode("chartStats:view").isPresent();
        // 菜单改名或误删后自动纠正：只要名称对不上就重建
        boolean staleMenus = resourceRepository.findByCode("report:view").isPresent()
                || resourceRepository.findByCode("portal:view").isPresent()
                || resourceRepository.findByCode("overtime:view").map(r -> !"加班录入记录".equals(r.getName())).orElse(false)
                || resourceRepository.findByCode("holiday:view").map(r -> !"系统日历".equals(r.getName())).orElse(false);
        if (hasLegacy || missingNewMenus || staleMenus) {
            roleResourceRepository.deleteAll();
            resourceRepository.deleteAll();
        }
    }

    /**
     * 若指定编码的角色已存在，则同步其名称与描述（用于老库升级时纠正文案）。
     *
     * @param code        角色编码
     * @param name        角色名称
     * @param description 角色描述
     */
    private void updateRoleIfExists(String code, String name, String description) {
        roleRepository.findByCode(code).ifPresent(r -> {
            r.setName(name);
            r.setDescription(description);
            roleRepository.save(r);
        });
    }

    /**
     * 播种三个内置角色：ADMIN / CLERK / EMPLOYEE。
     *
     * <p>仅在角色表为空时执行，保证重复启动不会重复插入。
     */
    private void seedRoles() {
        if (roleRepository.count() > 0) return;
        saveRole(Role.CODE_ADMIN, "管理员", "全部权限", true);
        saveRole(Role.CODE_CLERK, "录入员", "工作台、加班管理、调休管理、节假日维护", true);
        saveRole(Role.CODE_EMPLOYEE, "普通员工", "员工门户，仅可查看本人数据", true);
    }

    /**
     * 保存一个角色。
     *
     * @param code     角色编码（唯一）
     * @param name     角色名称
     * @param desc     角色描述
     * @param builtin  是否内置角色；内置角色不允许在页面上删除
     */
    private void saveRole(String code, String name, String desc, boolean builtin) {
        Role r = new Role();
        r.setCode(code);
        r.setName(name);
        r.setDescription(desc);
        r.setBuiltin(builtin);
        r.setStatus("ACTIVE");
        roleRepository.save(r);
    }

    /** 资源定义记录：编码、名称、类型、父编码、路由、组件、图标、排序。 */
    record Res(String code, String name, String type, String parent, String path, String component, String icon, int sort) {}

    /**
     * 初始化系统菜单与按钮权限资源树。
     * <p>
     * 若资源树为旧版或缺少新版菜单，则会在 {@link #migrateRolesAndCleanResources()} 中被清空后重新写入。
     */
    private Map<String, Long> seedResources() {
        // 如果已存在新版资源树（以 systemLog:view 为标记），直接返回映射
        if (resourceRepository.findByCode("systemLog:view").isPresent()) {
            Map<String, Long> map = new HashMap<>();
            resourceRepository.findAll().forEach(r -> map.put(r.getCode(), r.getId()));
            return map;
        }
        List<Res> defs = List.of(
                // 一级菜单，共 8 项。依据整改需求 13/19/20/21：
                //  - 移除「统计报表」(report:view) 与「员工门户」(portal:view)，相关源码文件保留但不再挂菜单；
                //  - 「节假日日历」更名为「系统日历」；
                //  - 加班菜单定名为「加班录入记录」（页面标题为「增加调休时长」）；
                //  - 新增「全员调休查看」「统计图展示」两项。
                new Res("dashboard:view", "工作台", "MENU", null, "/dashboard", "views/Dashboard.vue", "Home", 1),
                new Res("allStaffLeave:view", "全员调休查看", "MENU", null, "/all-staff-leave", "views/AllStaffLeave.vue", "PieChart", 2),
                new Res("overtime:view", "加班录入记录", "MENU", null, "/overtime", "views/Overtime.vue", "Clock", 3),
                new Res("leave:view", "调休使用记录", "MENU", null, "/leave", "views/Leave.vue", "Calendar", 4),
                new Res("chartStats:view", "统计图展示", "MENU", null, "/chart-stats", "views/ChartStats.vue", "TrendCharts", 5),
                new Res("holiday:view", "系统日历", "MENU", null, "/holiday", "views/Holiday.vue", "Date", 6),
                new Res("user:view", "用户管理", "MENU", null, "/user", "views/Users.vue", "User", 7),
                new Res("systemLog:view", "系统日志", "MENU", null, "/system-log", "views/SystemLog.vue", "Document", 8),

                // 加班管理按钮
                new Res("overtime:add", "录入", "BUTTON", "overtime:view", null, null, null, 1),
                new Res("overtime:edit", "编辑", "BUTTON", "overtime:view", null, null, null, 2),
                new Res("overtime:delete", "删除", "BUTTON", "overtime:view", null, null, null, 3),
                new Res("overtime:import", "导入", "BUTTON", "overtime:view", null, null, null, 4),
                new Res("overtime:export", "导出", "BUTTON", "overtime:view", null, null, null, 5),

                // 调休使用记录按钮
                new Res("leave:add", "录入", "BUTTON", "leave:view", null, null, null, 1),
                new Res("leave:edit", "编辑", "BUTTON", "leave:view", null, null, null, 2),
                new Res("leave:void", "作废", "BUTTON", "leave:view", null, null, null, 3),
                new Res("leave:import", "导入", "BUTTON", "leave:view", null, null, null, 4),
                new Res("leave:export", "导出", "BUTTON", "leave:view", null, null, null, 5),

                // 系统日历
                new Res("holiday:manage", "维护节假日", "BUTTON", "holiday:view", null, null, null, 1),

                // 用户管理按钮
                new Res("user:add", "新增用户", "BUTTON", "user:view", null, null, null, 1),
                new Res("user:edit", "编辑用户", "BUTTON", "user:view", null, null, null, 2),
                new Res("user:role", "分配角色", "BUTTON", "user:view", null, null, null, 3),
                new Res("user:resetpwd", "重置密码", "BUTTON", "user:view", null, null, null, 4),
                new Res("user:freeze", "冻结/解冻", "BUTTON", "user:view", null, null, null, 5),
                new Res("user:delete", "删除用户", "BUTTON", "user:view", null, null, null, 6),

                // 管理员隐藏页面（BUTTON 类型，不会出现在动态菜单里，但保留路由与权限）
                new Res("role:view", "角色权限", "BUTTON", null, "/system/role", "views/Role.vue", "Role", 1),
                new Res("role:add", "新增角色", "BUTTON", "role:view", null, null, null, 1),
                new Res("role:edit", "编辑角色", "BUTTON", "role:view", null, null, null, 2),
                new Res("role:assign", "分配权限", "BUTTON", "role:view", null, null, null, 3),
                new Res("resource:view", "资源管理", "BUTTON", null, "/system/resource", "views/Resource.vue", "Menu", 2),
                new Res("resource:add", "新增资源", "BUTTON", "resource:view", null, null, null, 1),
                new Res("resource:edit", "编辑资源", "BUTTON", "resource:view", null, null, null, 2),
                new Res("config:view", "系统配置", "BUTTON", null, "/system/config", "views/Config.vue", "Config", 3),
                new Res("config:edit", "编辑配置", "BUTTON", "config:view", null, null, null, 1)
        );
        Map<String, Resource> byCode = new LinkedHashMap<>();
        for (Res d : defs) {
            Resource r = new Resource();
            r.setCode(d.code());
            r.setName(d.name());
            r.setType(d.type());
            r.setPath(d.path());
            r.setComponent(d.component());
            r.setIcon(d.icon());
            r.setSort(d.sort());
            r.setStatus(Resource.STATUS_ACTIVE);
            r = resourceRepository.save(r);
            byCode.put(d.code(), r);
        }
        // 设置父子关系
        for (Res d : defs) {
            if (d.parent() != null) {
                Resource r = byCode.get(d.code());
                r.setParentId(byCode.get(d.parent()).getId());
                resourceRepository.save(r);
            }
        }
        Map<String, Long> map = new HashMap<>();
        byCode.forEach((k, v) -> map.put(k, v.getId()));
        return map;
    }

    /**
     * 为三个内置角色分配资源权限。
     *
     * <p>ADMIN 直接拥有全部资源；CLERK（录入员）覆盖业务操作类菜单；
     * EMPLOYEE（普通员工）仅保留工作台、员工门户与加班 / 调休的查看权限，不授予任何增删改按钮。
     *
     * @param resIds 资源编码 → 资源 ID 的映射，由 {@link #seedResources()} 返回
     */
    private void seedRoleResources(Map<String, Long> resIds) {
        if (roleResourceRepository.count() > 0) return;
        Map<String, List<String>> assigns = new HashMap<>();
        // ADMIN 拥有全部
        assigns.put(Role.CODE_ADMIN, new ArrayList<>(resIds.keySet()));
        assigns.put(Role.CODE_CLERK, List.of(
                "dashboard:view",
                "allStaffLeave:view",
                "overtime:view", "overtime:add", "overtime:edit", "overtime:delete", "overtime:import", "overtime:export",
                "leave:view", "leave:add", "leave:edit", "leave:void", "leave:import", "leave:export",
                "holiday:view", "holiday:manage",
                "chartStats:view",
                "user:view"));
        // 不授予任何增删改按钮，只能查看本人数据（数据过滤由各 Service 的 scopeUserId 保证）
        assigns.put(Role.CODE_EMPLOYEE, List.of(
                "dashboard:view", "overtime:view", "leave:view"));

        for (Map.Entry<String, List<String>> e : assigns.entrySet()) {
            Role role = roleRepository.findByCode(e.getKey()).orElse(null);
            if (role == null) continue;
            for (String code : e.getValue()) {
                Long rid = resIds.get(code);
                if (rid == null) continue;
                RoleResource rr = new RoleResource();
                rr.setRoleId(role.getId());
                rr.setResourceId(rid);
                roleResourceRepository.save(rr);
            }
        }
    }

    /**
     * 播种初始管理员账号 {@code admin}，并绑定 ADMIN 角色。
     *
     * <p>仅在用户表为空时执行（即全新部署）。
     * 初始密码固定且 {@code mustChangePwd = true}，强制首次登录后修改，降低默认口令风险。
     */
    private void seedAdmin() {
        if (userRepository.count() > 0) return;
        User admin = new User();
        admin.setUsername("admin");
        admin.setName("系统管理员");
        admin.setDepartment("管理部");
        admin.setStatus(User.STATUS_ACTIVE);
        admin.setMustChangePwd(true);
        admin.setTokenVersion(1L);
        admin.setPassword(PasswordUtil.encode("Abc_123456"));
        admin = userRepository.save(admin);

        Role adminRole = roleRepository.findByCode(Role.CODE_ADMIN).orElseThrow();
        UserRole ur = new UserRole();
        ur.setUserId(admin.getId());
        ur.setRoleId(adminRole.getId());
        userRoleRepository.save(ur);
    }

    /**
     * 播种默认系统配置项（加班时长封顶、时段时长、折算比例等）。
     *
     * <p>仅在配置表为空时执行；已有配置时保留管理员在页面上修改后的值。
     */
    private void seedConfig() {
        if (configRepository.count() > 0) return;
        saveConfig("system.name", "调休管家", "系统名称");
        saveConfig("overtime.dailyCap", "7.5", "单日有效加班时长封顶（小时）");
        saveConfig("overtime.period.am", "3.5", "上午时段 09:00-12:30 时长");
        saveConfig("overtime.period.pm", "4.0", "下午时段 14:00-18:00 时长");
        saveConfig("leave.ratio.workday", "0.5", "工作日/补班日加班折算比例");
        saveConfig("leave.ratio.rest", "1", "休息日/法定节假日加班折算比例");
    }

    /**
     * 保存一条系统配置。
     *
     * @param key   配置键，如 {@code overtime.dailyCap}
     * @param value 配置值（统一以字符串存储，使用时再按类型转换）
     * @param desc  配置说明，展示在系统配置页面
     */
    private void saveConfig(String key, String value, String desc) {
        SystemConfig c = new SystemConfig();
        c.setKey(key);
        c.setValue(value);
        c.setDescription(desc);
        configRepository.save(c);
    }

    /** 2026 节假日（系统生成；管理员可在「节假日日历」中手动维护覆盖，手动优先级最高）。 */
    private void seed2026Holidays() {
        if (holidayRepository.count() > 0) return;
        // 法定节假日（多日区间）
        addRange("2026-01-01", "2026-01-01", "元旦", Holiday.TYPE_LEGAL);
        addRange("2026-02-17", "2026-02-23", "春节", Holiday.TYPE_LEGAL);
        addRange("2026-04-04", "2026-04-06", "清明节", Holiday.TYPE_LEGAL);
        addRange("2026-05-01", "2026-05-05", "劳动节", Holiday.TYPE_LEGAL);
        addRange("2026-06-19", "2026-06-21", "端午节", Holiday.TYPE_LEGAL);
        addRange("2026-09-25", "2026-09-27", "中秋节", Holiday.TYPE_LEGAL);
        addRange("2026-10-01", "2026-10-07", "国庆节", Holiday.TYPE_LEGAL);
    }

    /**
     * 按日期区间逐日写入节假日记录。
     *
     * <p>节假日按「天」存储而非区间存储，是为了让工作日判定、加班折算比例计算
     * 可以直接用日期做等值查询，无需区间展开逻辑。
     *
     * @param start 起始日期（含），格式 yyyy-MM-dd
     * @param end   结束日期（含），格式 yyyy-MM-dd
     * @param name  节假日名称
     * @param type  节假日类型，见 Holiday.TYPE_*
     */
    private void addRange(String start, String end, String name, String type) {
        LocalDate s = LocalDate.parse(start);
        LocalDate e = LocalDate.parse(end);
        for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
            Holiday h = new Holiday();
            h.setDate(d);
            h.setName(name);
            h.setType(type);
            h.setAuto(true);
            holidayRepository.save(h);
        }
    }
}

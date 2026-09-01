package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.PageResult;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 系统日志：记录登录、增删改、导入导出、法定节假日强制变更等关键操作，仅管理员可查看。
 *
 * <p>统一路由前缀：{@code /api/system-logs}；整体权限要求：所有接口均需登录且仅 ADMIN 可访问。
 */
@Tag(name = "系统日志", description = "关键操作留痕查询，仅管理员可见")
@RestController
@RequestMapping("/api/system-logs")
public class SystemLogController {

    private final AuditLogService auditLogService;

    /**
     * 构造器注入。
     *
     * @param auditLogService 审计日志服务
     */
    public SystemLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 校验管理员权限：仅 ADMIN 可查看系统日志。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员可查看系统日志」
     */
    private void requireAdmin() {
        if (!SecurityUtil.hasRole("ADMIN")) throw new BizException("无权限：仅管理员可查看系统日志");
    }

    /**
     * 多条件分页查询系统日志。
     *
     * <p>请求路径：{@code GET /api/system-logs}；所需权限：ADMIN。
     *
     * @param module  业务模块（认证/用户管理/加班管理/调休使用记录/节假日日历/统计报表/系统）
     * @param action  操作类型（新增/编辑/删除/作废/导入/导出/登录/刷新）
     * @param level   日志级别 INFO / WARN / ERROR
     * @param keyword 关键字（操作人、目标、详情模糊匹配）
     * @param from    起始日期
     * @param to      结束日期
     * @param page    页码（1 起始）
     * @param size    每页条数
     * @return 成功返回分页日志数据（按创建时间倒序）；失败抛出「无权限：仅管理员可查看系统日志」
     */
    @Operation(summary = "系统日志列表", description = "按模块/操作类型/级别/关键字/日期范围组合查询，分页返回")
    @GetMapping
    public PageResult<AuditLog> list(
            @Parameter(description = "业务模块") @RequestParam(required = false) String module,
            @Parameter(description = "操作类型") @RequestParam(required = false) String action,
            @Parameter(description = "日志级别") @RequestParam(required = false) String level,
            @Parameter(description = "关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "起始日期") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "结束日期") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int size) {
        requireAdmin();
        Page<AuditLog> p = auditLogService.query(module, action, level, keyword, from, to, page - 1, size);
        return PageResult.of(p);
    }

    /**
     * 获取所有业务模块（供前端下拉筛选）。
     *
     * <p>请求路径：{@code GET /api/system-logs/modules}；所需权限：ADMIN。
     *
     * @return 成功返回 code=0 及模块名称列表；失败抛出「无权限：仅管理员可查看系统日志」
     */
    @Operation(summary = "业务模块下拉", description = "返回系统日志可筛选的全部业务模块")
    @GetMapping("/modules")
    public Result<List<String>> modules() {
        requireAdmin();
        return Result.ok(List.of(
                AuditLog.MODULE_AUTH, AuditLog.MODULE_USER, AuditLog.MODULE_OVERTIME,
                AuditLog.MODULE_LEAVE, AuditLog.MODULE_HOLIDAY, AuditLog.MODULE_REPORT, AuditLog.MODULE_SYSTEM
        ));
    }
}

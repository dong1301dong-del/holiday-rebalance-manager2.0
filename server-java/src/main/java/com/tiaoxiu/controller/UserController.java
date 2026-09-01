package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.UserDto;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理接口：列表 / 详情 / 新增 / 编辑 / 删除 / 分配角色 / 重置密码 / 冻结解冻。
 *
 * <p>统一路由前缀：{@code /api/users}。
 *
 * <p>整体权限要求：所有接口均需登录；列表与详情 ADMIN / CLERK 可见（普通员工仅可查看本人详情），
 * 其余全部写操作仅 ADMIN 可操作。
 */
@Tag(name = "用户管理", description = "用户 CRUD、角色分配、改密与冻结，仅管理员可写")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * 构造器注入。
     *
     * @param userService 用户服务
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 校验管理员权限：仅 ADMIN 可进行用户写操作。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员可操作」
     */
    private void requireAdmin() {
        if (!SecurityUtil.hasRole("ADMIN")) throw new BizException("无权限：仅管理员可操作");
    }

    /**
     * 查询全部有效用户（管理员 / 录入员可见）。
     *
     * <p>请求路径：{@code GET /api/users}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 code=0 及用户列表（含角色编码与实时调休余额）；失败抛出「无权限」
     */
    @Operation(summary = "用户列表", description = "返回全部非删除用户，含角色与实时调休余额")
    @GetMapping
    public Result<List<UserDto.UserResponse>> list() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return Result.ok(userService.listResponses());
    }

    /**
     * 查询单个用户详情。
     *
     * <p>请求路径：{@code GET /api/users/{id}}；所需权限：ADMIN / CLERK，或普通员工查看本人。
     *
     * @param id 用户 ID
     * @return 成功返回 code=0 及用户详情；失败抛出「无权限」或「成员不存在」
     */
    @Operation(summary = "用户详情", description = "管理员/录入员可见全部；普通员工仅可查看本人")
    @GetMapping("/{id}")
    public Result<UserDto.UserResponse> get(@Parameter(description = "用户 ID") @PathVariable Long id) {
        if (!(SecurityUtil.hasAnyRole("ADMIN", "CLERK") || SecurityUtil.getUserId().equals(id)))
            throw new BizException("无权限");
        return Result.ok(userService.getResponse(id));
    }

    /**
     * 新增用户（姓名/账号/部门/职位/角色均必填）。
     *
     * <p>请求路径：{@code POST /api/users}；所需权限：ADMIN。
     *
     * @param req 用户创建请求
     * @return 成功返回 code=0 及新建的用户实体；失败抛出「无权限」「用户名已存在」或密码强度不足
     */
    @Operation(summary = "新增用户", description = "管理员新增用户，默认密码由系统配置下发")
    @PostMapping
    public Result<User> create(@RequestBody UserDto.CreateRequest req) {
        requireAdmin();
        return Result.ok(userService.create(req, SecurityUtil.getUserId()));
    }

    /**
     * 编辑用户，支持改账号/姓名/部门/职位/角色。
     *
     * <p>请求路径：{@code PUT /api/users/{id}}；所需权限：ADMIN。
     *
     * @param id  用户 ID
     * @param req 更新内容（未传字段保持原值）
     * @return 成功返回 code=0 及更新后的用户实体；失败抛出「无权限」「成员不存在」或「用户名已存在」
     */
    @Operation(summary = "编辑用户", description = "支持修改账号、姓名、部门、职位与角色；变更会写系统日志")
    @PutMapping("/{id}")
    public Result<User> update(@Parameter(description = "用户 ID") @PathVariable Long id, @RequestBody UserDto.UpdateRequest req) {
        requireAdmin();
        return Result.ok(userService.update(id, req));
    }

    /**
     * 逻辑删除用户（状态置 DELETED，物理数据保留）。
     *
     * <p>请求路径：{@code DELETE /api/users/{id}}；所需权限：ADMIN。
     *
     * @param id 用户 ID
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「成员不存在」
     */
    @Operation(summary = "删除用户", description = "逻辑删除（status=DELETED），物理数据保留")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "用户 ID") @PathVariable Long id) {
        requireAdmin();
        userService.delete(id);
        return Result.ok();
    }

    /**
     * 分配角色（整体覆盖该用户原有角色）。
     *
     * <p>请求路径：{@code POST /api/users/{id}/roles}；所需权限：ADMIN。
     *
     * @param id  用户 ID
     * @param req 角色编码列表
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「成员不存在」
     */
    @Operation(summary = "分配角色", description = "覆盖式分配角色（ADMIN / CLERK / EMPLOYEE）")
    @PostMapping("/{id}/roles")
    public Result<Void> assignRoles(@Parameter(description = "用户 ID") @PathVariable Long id, @RequestBody UserDto.AssignRolesRequest req) {
        requireAdmin();
        userService.assignRoles(id, req.getRoleCodes());
        return Result.ok();
    }

    /**
     * 重置用户密码（重置后强制该用户下次登录改密，并使其旧会话失效）。
     *
     * <p>请求路径：{@code POST /api/users/{id}/reset-password}；所需权限：ADMIN。
     *
     * @param id  用户 ID
     * @param req 新密码
     * @return 成功返回 code=0 空数据；失败抛出「无权限」「成员不存在」或密码强度不足
     */
    @Operation(summary = "重置密码", description = "管理员强制重置用户密码，用户下次登录需改密")
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@Parameter(description = "用户 ID") @PathVariable Long id, @RequestBody UserDto.ResetPasswordRequest req) {
        requireAdmin();
        userService.resetPassword(id, req.getNewPassword());
        return Result.ok();
    }

    /**
     * 冻结 / 解冻用户。
     *
     * <p>请求路径：{@code POST /api/users/{id}/status?status=FROZEN}；所需权限：ADMIN。
     *
     * @param id     用户 ID
     * @param status ACTIVE 正常 / FROZEN 冻结
     * @return 成功返回 code=0 空数据；失败抛出「无权限」「成员不存在」或「非法状态」
     */
    @Operation(summary = "冻结/解冻", description = "status 传 ACTIVE 解冻、FROZEN 冻结")
    @PostMapping("/{id}/status")
    public Result<Void> setStatus(@Parameter(description = "用户 ID") @PathVariable Long id,
                                  @Parameter(description = "ACTIVE 正常 / FROZEN 冻结") @RequestParam String status) {
        requireAdmin();
        userService.setStatus(id, status);
        return Result.ok();
    }
}

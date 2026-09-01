package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.RoleDto;
import com.tiaoxiu.entity.Role;
import com.tiaoxiu.service.RoleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色管理接口：角色列表 / 新增 / 编辑 / 删除，以及角色与资源（菜单按钮权限）的绑定。
 *
 * <p>统一路由前缀：{@code /api/roles}。
 *
 * <p>整体权限要求：所有接口均需登录；列表查询 ADMIN / CLERK 可见，
 * 新增、编辑、删除、分配权限与查询已分配权限均仅 ADMIN 可操作。
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;

    /**
     * 构造器注入。
     *
     * @param roleService 角色服务
     */
    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * 校验管理员权限：仅 ADMIN 可进行角色写操作。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员可操作」
     */
    private void requireAdmin() {
        if (!SecurityUtil.hasRole("ADMIN")) throw new BizException("无权限：仅管理员可操作");
    }

    /**
     * 查询全部角色。
     *
     * <p>请求路径：{@code GET /api/roles}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 code=0 及角色列表；失败抛出「无权限」
     */
    @GetMapping
    public Result<List<Role>> list() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return Result.ok(roleService.list());
    }

    /**
     * 新增角色。
     *
     * <p>请求路径：{@code POST /api/roles}；所需权限：ADMIN。
     *
     * @param req 角色创建请求（编码、名称、描述、状态）
     * @return 成功返回 code=0 及新建的角色；失败抛出「无权限」或「角色编码已存在」
     */
    @PostMapping
    public Result<Role> create(@RequestBody RoleDto.CreateRequest req) {
        requireAdmin();
        return Result.ok(roleService.create(req));
    }

    /**
     * 编辑角色（编码不可修改）。
     *
     * <p>请求路径：{@code PUT /api/roles/{id}}；所需权限：ADMIN。
     *
     * @param id  角色 ID
     * @param req 更新内容（未传字段保持原值）
     * @return 成功返回 code=0 及更新后的角色；失败抛出「无权限」或「角色不存在」
     */
    @PutMapping("/{id}")
    public Result<Role> update(@PathVariable Long id, @RequestBody RoleDto.UpdateRequest req) {
        requireAdmin();
        return Result.ok(roleService.update(id, req));
    }

    /**
     * 删除角色（内置角色不可删除）。
     *
     * <p>请求路径：{@code DELETE /api/roles/{id}}；所需权限：ADMIN。
     *
     * @param id 角色 ID
     * @return 成功返回 code=0 空数据；失败抛出「无权限」「角色不存在」或「内置角色不可删除」
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireAdmin();
        roleService.delete(id);
        return Result.ok();
    }

    /**
     * 为角色分配资源权限（整体覆盖原有绑定）。
     *
     * <p>请求路径：{@code POST /api/roles/{id}/resources}；所需权限：ADMIN。
     *
     * @param id  角色 ID
     * @param req 资源 ID 列表
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「角色不存在」
     */
    @PostMapping("/{id}/resources")
    public Result<Void> assignResources(@PathVariable Long id, @RequestBody RoleDto.AssignResourcesRequest req) {
        requireAdmin();
        roleService.assignResources(id, req.getResourceIds());
        return Result.ok();
    }

    /**
     * 查询角色已绑定的资源 ID 列表（用于权限分配树回显）。
     *
     * <p>请求路径：{@code GET /api/roles/{id}/resources}；所需权限：ADMIN。
     *
     * @param id 角色 ID
     * @return 成功返回 code=0 及资源 ID 列表；失败抛出「无权限」
     */
    @GetMapping("/{id}/resources")
    public Result<List<Long>> resources(@PathVariable Long id) {
        requireAdmin();
        return Result.ok(roleService.resourceIdsOf(id));
    }
}

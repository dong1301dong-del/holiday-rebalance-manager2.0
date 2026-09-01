package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.ResourceDto;
import com.tiaoxiu.entity.Resource;
import com.tiaoxiu.service.ResourceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 资源（菜单 / 按钮 / 接口权限）管理接口。
 *
 * <p>统一路由前缀：{@code /api/resources}。
 *
 * <p>整体权限要求：所有接口均需登录；列表查询 ADMIN / CLERK 可见，新增与修改仅 ADMIN 可操作。
 * 注意本控制器未开放删除接口，权限编码一旦投入使用不建议删除。
 */
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceService resourceService;

    /**
     * 构造器注入。
     *
     * @param resourceService 资源服务
     */
    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    /**
     * 校验管理员权限：仅 ADMIN 可进行资源写操作。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员可操作」
     */
    private void requireAdmin() {
        if (!SecurityUtil.hasRole("ADMIN")) throw new BizException("无权限：仅管理员可操作");
    }

    /**
     * 查询全部资源（含已停用的），供管理端维护与权限分配树使用。
     *
     * <p>请求路径：{@code GET /api/resources}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 code=0 及资源列表；失败抛出「无权限」
     */
    @GetMapping
    public Result<List<Resource>> list() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return Result.ok(resourceService.listAllIncludingDisabled());
    }

    /**
     * 新增资源。
     *
     * <p>请求路径：{@code POST /api/resources}；所需权限：ADMIN。
     *
     * @param req 资源创建请求（编码、名称、类型、路由、组件等）
     * @return 成功返回 code=0 及新建的资源；失败抛出「无权限」或「权限编码已存在」
     */
    @PostMapping
    public Result<Resource> create(@RequestBody ResourceDto.CreateRequest req) {
        requireAdmin();
        return Result.ok(resourceService.create(req));
    }

    /**
     * 修改资源（权限编码不可修改）。
     *
     * <p>请求路径：{@code PUT /api/resources/{id}}；所需权限：ADMIN。
     *
     * @param id  资源 ID
     * @param req 更新内容（未传字段保持原值）
     * @return 成功返回 code=0 及更新后的资源；失败抛出「无权限」或「资源不存在」
     */
    @PutMapping("/{id}")
    public Result<Resource> update(@PathVariable Long id, @RequestBody ResourceDto.UpdateRequest req) {
        requireAdmin();
        return Result.ok(resourceService.update(id, req));
    }
}

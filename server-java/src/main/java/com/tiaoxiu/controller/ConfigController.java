package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.ConfigDto;
import com.tiaoxiu.entity.SystemConfig;
import com.tiaoxiu.service.ConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 系统配置接口：键值对的查询与设置（如单日加班时长封顶、折算比例等）。
 *
 * <p>统一路由前缀：{@code /api/config}。
 *
 * <p>整体权限要求：所有接口均需登录；查询需 ADMIN / CLERK，修改仅 ADMIN 可操作。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    /**
     * 构造器注入。
     *
     * @param configService 系统配置服务
     */
    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 查询全部系统配置项。
     *
     * <p>请求路径：{@code GET /api/config}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 code=0 及配置项列表；失败抛出「无权限」
     */
    @GetMapping
    public Result<List<SystemConfig>> list() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return Result.ok(configService.list());
    }

    /**
     * 按配置键查询单条配置。
     *
     * <p>请求路径：{@code GET /api/config/{key}}；所需权限：ADMIN / CLERK。
     *
     * @param key 配置键
     * @return 成功返回 code=0 及配置（不存在时 data 为空 Optional）；失败抛出「无权限」
     */
    @GetMapping("/{key}")
    public Result<Optional<SystemConfig>> get(@PathVariable String key) {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return Result.ok(configService.get(key));
    }

    /**
     * 新增或修改配置（按键 upsert）。
     *
     * <p>请求路径：{@code POST /api/config}；所需权限：ADMIN。
     *
     * @param req 配置请求（键、值、描述）
     * @return 成功返回 code=0 及保存后的配置；失败抛出「无权限：仅管理员可配置」或「配置键不能为空」
     */
    @PostMapping
    public Result<SystemConfig> set(@RequestBody ConfigDto.UpsertRequest req) {
        if (!SecurityUtil.hasRole("ADMIN")) throw new BizException("无权限：仅管理员可配置");
        return Result.ok(configService.set(req));
    }
}

package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.dto.DictDto;
import com.tiaoxiu.entity.DictData;
import com.tiaoxiu.entity.DictType;
import com.tiaoxiu.service.DictService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据字典接口：字典类型与字典项的查询与维护。
 *
 * <p>统一路由前缀：{@code /api/dict}。
 *
 * <p>整体权限要求：所有接口均需登录；查询类 ADMIN / CLERK / EMPLOYEE 均可访问，
 * 新增与删除需 ADMIN 或 CLERK。
 */
@RestController
@RequestMapping("/api/dict")
public class DictController {

    private final DictService dictService;

    /**
     * 构造器注入。
     *
     * @param dictService 字典服务
     */
    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    /**
     * 查询全部字典类型。
     *
     * <p>请求路径：{@code GET /api/dict/types}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @return 成功返回 code=0 及字典类型列表；失败抛出「无权限」
     */
    @GetMapping("/types")
    public Result<List<DictType>> types() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK", "EMPLOYEE")) throw new BizException("无权限");
        return Result.ok(dictService.listTypes());
    }

    /**
     * 新增字典类型。
     *
     * <p>请求路径：{@code POST /api/dict/types}；所需权限：ADMIN / CLERK。
     *
     * @param req 类型创建请求（编码、名称）
     * @return 成功返回 code=0 及新建的字典类型；失败抛出「无权限」或「字典类型编码已存在」
     */
    @PostMapping("/types")
    public Result<DictType> createType(@RequestBody DictDto.TypeCreateRequest req) {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return Result.ok(dictService.createType(req));
    }

    /**
     * 查询某字典类型下的全部选项。
     *
     * <p>请求路径：{@code GET /api/dict/data?type=xxx}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @param type 字典类型编码
     * @return 成功返回 code=0 及字典项列表（按排序号升序）；失败抛出「无权限」
     */
    @GetMapping("/data")
    public Result<List<DictData>> data(@RequestParam String type) {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK", "EMPLOYEE")) throw new BizException("无权限");
        return Result.ok(dictService.listData(type));
    }

    /**
     * 新增字典项。
     *
     * <p>请求路径：{@code POST /api/dict/data}；所需权限：ADMIN / CLERK。
     *
     * @param req 字典项请求（所属类型、值、显示标签、排序、状态）
     * @return 成功返回 code=0 及新建的字典项；失败抛出「无权限」或「字典类型不存在」
     */
    @PostMapping("/data")
    public Result<DictData> upsertData(@RequestBody DictDto.DataUpsertRequest req) {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return Result.ok(dictService.upsertData(req));
    }

    /**
     * 删除字典项。
     *
     * <p>请求路径：{@code DELETE /api/dict/data/{id}}；所需权限：ADMIN / CLERK。
     *
     * @param id 字典项 ID
     * @return 成功返回 code=0 空数据；失败抛出「无权限」
     */
    @DeleteMapping("/data/{id}")
    public Result<Void> deleteData(@PathVariable Long id) {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        dictService.deleteData(id);
        return Result.ok();
    }
}

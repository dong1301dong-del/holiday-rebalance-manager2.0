package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.PageResult;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.common.UploadValidator;
import com.tiaoxiu.dto.OvertimeDto;
import com.tiaoxiu.entity.OvertimeRecord;
import com.tiaoxiu.service.OvertimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 加班转调休录入管理（前端菜单「加班录入」、页面标题「增加调休时长」）。
 * <p>
 * 时长规则：
 * <ul>
 *   <li>加班时长 = 结束时间 − 开始时间（30 分钟一跳，不扣时段、不封顶）；</li>
 *   <li>转调休时长 = 加班时长 × 系数（法定工作日 0.5，休息日 / 法定节假日 1，由节假日日历自动判定）；</li>
 *   <li>「其他转休」模式不填起止时间，直接填写转休时长与必填备注。</li>
 * </ul>
 * 数据权限：ADMIN / CLERK 可查看与操作全员；EMPLOYEE 仅能查看本人。
 * <p>
 * 统一路由前缀：{@code /api/overtime}；整体权限要求：所有接口均需登录，
 * 查询类需 ADMIN / CLERK / EMPLOYEE 任一角色，增删改与导入导出需 ADMIN 或 CLERK。
 */
@Tag(name = "加班转调休录入", description = "加班录入（增加调休时长）：列表、录入、编辑、删除、导入导出")
@RestController
@RequestMapping("/api/overtime")
public class OvertimeController {

    private static final String EXPORT_FILENAME = "加班转调休录入记录.xlsx";
    private static final String TEMPLATE_FILENAME = "加班转调休录入导入模板.xlsx";

    private final OvertimeService overtimeService;
    private final UploadValidator uploadValidator;

    /**
     * 构造器注入。
     *
     * @param overtimeService 加班服务
     * @param uploadValidator 上传文件校验（空文件/大小/类型/行数）
     */
    public OvertimeController(OvertimeService overtimeService, UploadValidator uploadValidator) {
        this.overtimeService = overtimeService;
        this.uploadValidator = uploadValidator;
    }

    /**
     * 校验查看权限：ADMIN / CLERK / EMPLOYEE 任一角色均可。
     *
     * @throws BizException 角色不符时抛出「无权限」
     */
    private void requireView() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK", "EMPLOYEE")) throw new BizException("无权限");
    }

    /**
     * 校验管理权限：仅 ADMIN / CLERK 可进行数据变更操作。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员/录入员可操作」
     */
    private void requireManage() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限：仅管理员/录入员可操作");
    }

    /**
     * 数据权限收敛：ADMIN / CLERK 按请求参数查（可看全员），EMPLOYEE 强制收敛为本人 ID。
     *
     * @param requested 请求中传入的 userId，可为 null
     * @return 实际用于查询的 userId
     */
    private Long scopeUserId(Long requested) {
        if (SecurityUtil.hasAnyRole("ADMIN", "CLERK")) return requested;
        return SecurityUtil.getUserId();
    }

    /**
     * 分页列表，支持日期区间 / 姓名 / 部门 / userId 自由组合查询。
     *
     * <p>请求路径：{@code GET /api/overtime}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @param q 查询条件
     * @return 成功返回分页结果（EMPLOYEE 自动收敛为本人数据）；失败抛出「无权限」
     */
    @Operation(summary = "加班记录列表", description = "分页查询，支持日期区间/姓名/部门组合筛选；员工自动只看本人")
    @GetMapping
    public PageResult<OvertimeDto.OvertimeResponse> list(OvertimeDto.QueryRequest q) {
        requireView();
        if (q == null) q = new OvertimeDto.QueryRequest();
        q.setUserId(scopeUserId(q.getUserId()));
        return overtimeService.query(q);
    }

    /**
     * 根据日期自动判定日类型与转调休系数。
     *
     * <p>请求路径：{@code GET /api/overtime/resolve?date=yyyy-MM-dd}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @param date 日期（yyyy-MM-dd）
     * @return 成功返回日类型、系数、命中的节假日名称；失败抛出「无权限」或日期为空提示
     */
    @Operation(summary = "日期类型解析", description = "按节假日日历判定日类型与转调休系数（人工维护 > 官方规则 > 周末兜底）")
    @GetMapping("/resolve")
    public Result<OvertimeDto.DayResolve> resolve(
            @Parameter(description = "日期 yyyy-MM-dd", example = "2026-01-04") @RequestParam("date") String date) {
        requireView();
        return Result.ok(overtimeService.resolveDay(LocalDate.parse(date)));
    }

    /**
     * 批量录入：同一弹窗固定一名员工，可一次追加多天加班。
     *
     * <p>请求路径：{@code POST /api/overtime/batch}；所需权限：ADMIN / CLERK。
     *
     * @param req 记录列表（每条含模式、日期、起止时间、打卡时间、备注）
     * @return 成功返回 code=0 及已写入的记录列表；失败抛出「无权限」或带行号的校验错误（整批回滚）
     */
    @Operation(summary = "批量录入加班", description = "同一弹窗仅允许为一名员工录入多天；支持加班转休与其他转休两种模式")
    @PostMapping("/batch")
    public Result<List<OvertimeRecord>> createBatch(@RequestBody OvertimeDto.BatchRequest req) {
        requireManage();
        if (req == null || req.getRecords() == null || req.getRecords().isEmpty()) {
            throw new BizException("请至少录入一条加班记录");
        }
        return Result.ok(overtimeService.createBatch(req.getRecords(), SecurityUtil.getUserId(),
                OvertimeRecord.SOURCE_MANUAL));
    }

    /**
     * 单条录入（ExcelService 等内部复用同一规则）。
     *
     * <p>请求路径：{@code POST /api/overtime}；所需权限：ADMIN / CLERK。
     *
     * @param req 单条记录
     * @return 成功返回 code=0 及写入的记录实体（含服务端重算的时长与系数）；失败抛出「无权限」或参数校验错误
     */
    @Operation(summary = "单条录入加班", description = "录入一条加班/其他转休记录，自动重算时长并累加调休余额")
    @PostMapping
    public Result<OvertimeRecord> create(@RequestBody OvertimeDto.RecordInput req) {
        requireManage();
        return Result.ok(overtimeService.create(req, SecurityUtil.getUserId()));
    }

    /**
     * 编辑单条记录：重算时长与系数，按差额调整余额。
     *
     * <p>请求路径：{@code PUT /api/overtime/{id}}；所需权限：ADMIN / CLERK。
     *
     * @param id  记录 ID
     * @param req 更新内容
     * @return 成功返回 code=0 及更新后的记录；失败抛出「无权限」「加班记录不存在」或「已作废的记录不可编辑」
     */
    @Operation(summary = "编辑加班记录", description = "重算加班时长与转休时长，按差额调整余额并写系统日志")
    @PutMapping("/{id}")
    public Result<OvertimeRecord> update(@Parameter(description = "记录 ID") @PathVariable Long id,
                                         @RequestBody OvertimeDto.UpdateRequest req) {
        requireManage();
        return Result.ok(overtimeService.update(id, req, SecurityUtil.getUserId()));
    }

    /**
     * 删除记录：冲减对应调休余额（仅管理员 / 录入员）。
     *
     * <p>请求路径：{@code DELETE /api/overtime/{id}}；所需权限：ADMIN / CLERK。
     *
     * @param id 记录 ID
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「加班记录不存在」
     */
    @Operation(summary = "删除加班记录", description = "删除并冲减已折算的调休时长，写系统日志")
    @DeleteMapping("/{id}")
    public Result<Void> remove(@Parameter(description = "记录 ID") @PathVariable Long id) {
        requireManage();
        overtimeService.remove(id, SecurityUtil.getUserId());
        return Result.ok();
    }

    /**
     * Excel 批量导入。
     *
     * <p>请求路径：{@code POST /api/overtime/import}（multipart/form-data）；所需权限：ADMIN / CLERK。
     *
     * @param file Excel 文件
     * @return 成功返回 code=0 及导入成功 / 失败条数与逐行错误明细（失败行不影响成功行入库）；
     *         失败抛出「无权限」「请选择要导入的文件」或 Excel 解析错误
     */
    @Operation(summary = "Excel 批量导入", description = "按模板导入加班/其他转休记录，返回成功与失败明细")
    @PostMapping("/import")
    public Result<OvertimeDto.ImportResult> importFile(@RequestParam("file") MultipartFile file) {
        requireManage();
        this.uploadValidator.validate(file);
        return Result.ok(overtimeService.importExcel(file, SecurityUtil.getUserId()));
    }

    /**
     * 导出 Excel：查询条件全空则导出全部；结果为空返回 400。
     *
     * <p>请求路径：{@code GET /api/overtime/export}；所需权限：ADMIN / CLERK。
     *
     * @param q 查询条件
     * @return 成功返回 xlsx 文件流（Content-Disposition 已做 UTF-8 编码）；
     *         无数据时返回 HTTP 400 且 body 为 code≠0 的「当前查询结果无数据可导出」
     */
    @Operation(summary = "导出加班记录", description = "按当前查询条件导出；无数据时提示「当前查询结果无数据可导出」")
    @GetMapping("/export")
    public ResponseEntity<?> export(OvertimeDto.QueryRequest q) {
        requireManage();
        if (q == null) q = new OvertimeDto.QueryRequest();
        q.setUserId(scopeUserId(q.getUserId()));
        List<OvertimeDto.OvertimeResponse> rows = overtimeService.queryForExport(q);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Result.fail("当前查询结果无数据可导出"));
        }
        byte[] data = overtimeService.exportExcel(rows);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; " + encodedFilename(EXPORT_FILENAME));
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }

    /**
     * 下载导入模板。
     *
     * <p>请求路径：{@code GET /api/overtime/template}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 xlsx 模板文件流；失败抛出「无权限」
     */
    @Operation(summary = "下载导入模板", description = "下载加班/其他转休录入 Excel 模板")
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        requireManage();
        byte[] data = overtimeService.template();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; " + encodedFilename(TEMPLATE_FILENAME));
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }

    /**
     * 生成符合 RFC 5987 的文件名下载头，解决中文文件名在部分浏览器下乱码的问题。
     *
     * @param filename 原始文件名
     * @return 用于 Content-Disposition 的 filename 片段
     */
    private static String encodedFilename(String filename) {
        try {
            return "filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return "filename=\"" + filename + "\"";
        }
    }
}

package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.PageResult;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.common.UploadValidator;
import com.tiaoxiu.dto.LeaveDto;
import com.tiaoxiu.entity.AuditLog;
import com.tiaoxiu.service.AuditLogService;
import com.tiaoxiu.service.LeaveExcelService;
import com.tiaoxiu.service.LeaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 调休使用记录。<b>无审批流程</b>：录入即扣减调休余额，允许透支并给管理员/录入员推送余额预警。
 * <p>
 * 作废仅置 status=VOID 并恢复余额，<b>不做物理删除</b>。
 * <p>
 * 统一路由前缀：{@code /api/leave}；整体权限要求：所有接口均需登录，
 * 查询 / 导出需 ADMIN / CLERK / EMPLOYEE 任一角色，录入、编辑、作废、删除与模板下载需 ADMIN 或 CLERK。
 */
@Tag(name = "调休使用记录", description = "调休使用录入、编辑、作废、余额查询与透支预警")
@RestController
@RequestMapping("/api/leave")
public class LeaveController {

    private final LeaveService leaveService;
    private final LeaveExcelService leaveExcelService;
    private final AuditLogService auditLogService;
    private final UploadValidator uploadValidator;

    /**
     * 构造器注入。
     *
     * @param leaveService      调休使用服务
     * @param leaveExcelService 调休使用 Excel 导入导出服务
     * @param auditLogService   审计日志服务（导入导出留痕）
     * @param uploadValidator   上传文件校验（空文件/大小/类型/行数）
     */
    public LeaveController(LeaveService leaveService, LeaveExcelService leaveExcelService,
                           AuditLogService auditLogService, UploadValidator uploadValidator) {
        this.leaveService = leaveService;
        this.leaveExcelService = leaveExcelService;
        this.auditLogService = auditLogService;
        this.uploadValidator = uploadValidator;
    }

    /**
     * 判断当前用户是否具备管理权限（ADMIN 或 CLERK）。
     *
     * @return 具备管理权限返回 true
     */
    private boolean canManage() {
        return SecurityUtil.hasAnyRole("ADMIN", "CLERK");
    }

    /**
     * 校验管理权限。
     *
     * @throws BizException 角色不符时抛出「无权限：仅管理员/录入员可操作」
     */
    private void requireManage() {
        if (!canManage()) throw new BizException("无权限：仅管理员/录入员可操作");
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
     * 数据权限收敛：ADMIN / CLERK 按请求参数查（可看全员），EMPLOYEE 强制收敛为本人 ID。
     *
     * @param requested 请求中传入的 userId，可为 null
     * @return 实际用于查询的 userId
     */
    private Long scopeUserId(Long requested) {
        if (canManage()) return requested;
        return SecurityUtil.getUserId();
    }

    // ==================== 列表 ====================

    /**
     * 分页 + 组合查询调休使用记录。
     *
     * @param from        起始日期
     * @param to          结束日期
     * @param name        姓名（模糊）
     * @param department  部门（模糊）
     * @param userId      用户 ID
     * @param includeVoid 是否包含已作废记录（默认 false）
     * @param page        页码
     * @param size        每页条数
     * @return 成功返回分页结果（EMPLOYEE 自动收敛为本人数据）；失败抛出「无权限」
     */
    @Operation(summary = "调休使用记录列表", description = "按日期/姓名/部门组合查询；默认过滤已作废记录，可勾选显示")
    @GetMapping
    public PageResult<LeaveDto.LeaveUsageResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "false") Boolean includeVoid,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireView();
        LeaveDto.QueryRequest q = new LeaveDto.QueryRequest();
        q.setFrom(from);
        q.setTo(to);
        q.setName(name);
        q.setDepartment(department);
        q.setUserId(scopeUserId(userId));
        q.setIncludeVoid(includeVoid);
        q.setPage(page);
        q.setSize(size);
        return PageResult.of(leaveService.query(q));
    }

    // ==================== 录入 ====================

    /**
     * 批量录入（同一人可一次录多条），逐条扣减余额。
     * <p>
     * 余额不足且未确认透支时返回提示文案，由前端弹窗二次确认后重提。
     *
     * <p>请求路径：{@code POST /api/leave/batch}；所需权限：ADMIN / CLERK。
     *
     * @param req 批量录入请求（含是否允许透支标志）
     * @return 成功返回 code=0 及录入条数与透支员工姓名列表；
     *         失败抛出「无权限」或「余额不足，当前余额 X 小时，本次使用 Y 小时，是否继续？」（供前端二次确认）
     */
    @Operation(summary = "批量录入调休使用", description = "录入即扣减余额；余额不足返回提示，确认透支后生成预警消息")
    @PostMapping("/batch")
    public Result<LeaveDto.BatchResult> createBatch(@RequestBody LeaveDto.BatchCreateRequest req) {
        requireManage();
        return Result.ok(leaveService.createBatch(req));
    }

    /**
     * 单条录入（兼容保留）。
     *
     * <p>请求路径：{@code POST /api/leave}；所需权限：ADMIN / CLERK。
     *
     * @param req 单条使用记录
     * @return 成功返回 code=0 及写入的记录；失败抛出「无权限」或余额不足 / 时间校验错误
     */
    @Operation(summary = "单条录入调休使用", description = "录入一条调休使用记录，立即扣减余额")
    @PostMapping
    public Result<LeaveDto.LeaveUsageResponse> create(@RequestBody LeaveDto.LeaveUsageItem req) {
        requireManage();
        return Result.ok(leaveService.createOne(req, false));
    }

    // ==================== 编辑 / 作废 / 删除 ====================

    /**
     * 编辑记录：按差额调整余额。
     *
     * <p>请求路径：{@code PUT /api/leave/{id}}；所需权限：ADMIN / CLERK。
     *
     * @param id  记录 ID
     * @param req 更新内容
     * @return 成功返回 code=0 及更新后的记录；失败抛出「无权限」「调休使用记录不存在」或「已作废的记录不可编辑」
     */
    @Operation(summary = "编辑调休使用记录", description = "按新时长与旧时长的差额调整余额，并写系统日志留痕")
    @PutMapping("/{id}")
    public Result<LeaveDto.LeaveUsageResponse> update(@Parameter(description = "记录 ID") @PathVariable Long id,
                                                      @RequestBody LeaveDto.UpdateRequest req) {
        requireManage();
        return Result.ok(leaveService.update(id, req));
    }

    /**
     * 作废记录：status=VOID，使用时长恢复至余额，物理数据保留。
     *
     * <p>请求路径：{@code POST /api/leave/{id}/void}；所需权限：ADMIN / CLERK。
     *
     * @param id  记录 ID
     * @param req 作废原因（可选，为空时记为「未填写」）
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「该记录已作废，不可重复作废」
     */
    @Operation(summary = "作废调休使用记录", description = "二次确认后置 VOID 并恢复余额，记录本身保留不删")
    @PostMapping("/{id}/void")
    public Result<Void> voidRecord(@Parameter(description = "记录 ID") @PathVariable Long id,
                                   @RequestBody(required = false) LeaveDto.VoidRequest req) {
        requireManage();
        leaveService.voidRecord(id, req == null ? null : req.getReason());
        return Result.ok();
    }

    /**
     * 物理删除（兼容保留）：冲减余额。
     *
     * <p>请求路径：{@code DELETE /api/leave/{id}}；所需权限：ADMIN / CLERK。
     *
     * @param id 记录 ID
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「调休使用记录不存在」
     */
    @Operation(summary = "删除调休使用记录", description = "物理删除并冲减余额；业务上推荐用作废代替删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "记录 ID") @PathVariable Long id) {
        requireManage();
        leaveService.delete(id);
        return Result.ok();
    }

    // ==================== 导入 / 导出 / 模板 ====================

    /**
     * 导入 Excel（列顺序：员工姓名、使用日期、开始时间、结束时间、备注）。
     *
     * <p>请求路径：{@code POST /api/leave/import}（multipart/form-data）；所需权限：ADMIN / CLERK。
     *
     * @param file Excel 文件
     * @return 成功返回 code=0 及导入成功 / 失败条数与逐行错误明细（失败行不影响成功行入库）；
     *         失败抛出「无权限」「请选择要导入的文件」或 Excel 解析错误
     */
    @Operation(summary = "Excel 导入调休使用记录", description = "按模板批量导入，返回成功与失败明细并写系统日志")
    @PostMapping("/import")
    public Result<LeaveDto.ImportResult> importFile(@RequestParam("file") MultipartFile file) {
        requireManage();
        this.uploadValidator.validate(file);
        LeaveDto.ImportResult res = leaveExcelService.importLeaveUsage(file);
        auditLogService.log(AuditLog.MODULE_LEAVE, "导入调休使用记录", "Excel：" + file.getOriginalFilename(),
                String.format("成功 %d 条，失败 %d 条", res.getSuccess(), res.getFailed()));
        return Result.ok(res);
    }

    /**
     * 导出 Excel：查询条件为空则导出全部；查询结果为空返回 400。
     *
     * @param from        起始日期
     * @param to          结束日期
     * @param name        姓名
     * @param department  部门
     * @param userId      用户 ID
     * @param includeVoid 是否含已作废
     * @return 成功返回 xlsx 文件流；无数据时返回 HTTP 400 且 body 为 code≠0 的「当前查询结果无数据可导出」
     */
    @Operation(summary = "导出调休使用记录", description = "按查询条件导出；无数据时提示「当前查询结果无数据可导出」")
    @GetMapping("/export")
    public ResponseEntity<Object> exportFile(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "false") Boolean includeVoid) {
        requireView();
        LeaveDto.QueryRequest q = new LeaveDto.QueryRequest();
        q.setFrom(from);
        q.setTo(to);
        q.setName(name);
        q.setDepartment(department);
        q.setUserId(scopeUserId(userId));
        q.setIncludeVoid(includeVoid);
        List<LeaveDto.LeaveUsageResponse> rows = leaveService.queryAll(q);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Result.fail("当前查询结果无数据可导出"));
        }
        byte[] data = leaveExcelService.exportLeaveUsage(rows);
        auditLogService.log(AuditLog.MODULE_LEAVE, "导出调休使用记录", "调休使用记录.xlsx",
                String.format("共 %d 条", rows.size()));
        return ResponseEntity.ok().headers(downloadHeaders("调休使用记录.xlsx")).body(data);
    }

    /**
     * 下载导入模板。
     *
     * <p>请求路径：{@code GET /api/leave/template}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 xlsx 模板文件流；失败抛出「无权限」
     */
    @Operation(summary = "下载调休使用导入模板", description = "下载调休使用记录 Excel 导入模板")
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        requireManage();
        return ResponseEntity.ok()
                .headers(downloadHeaders("调休使用记录导入模板.xlsx"))
                .body(leaveExcelService.template());
    }

    // ==================== 余额 / 消息 ====================

    /**
     * 查询指定用户当前调休余额。
     *
     * <p>请求路径：{@code GET /api/leave/balance?userId=}；所需权限：ADMIN / CLERK / EMPLOYEE。
     *
     * @param userId 用户 ID（可空，默认当前登录者；EMPLOYEE 强制查本人）
     * @return 成功返回 code=0 及当前余额与透支标志；失败抛出「无权限」「成员不存在」或「请先选择员工」
     */
    @Operation(summary = "查询调休余额", description = "管理员/录入员可查任意用户；普通员工强制查本人")
    @GetMapping("/balance")
    public Result<LeaveDto.BalanceResponse> balance(@Parameter(description = "用户 ID") @RequestParam(required = false) Long userId) {
        requireView();
        Long target = scopeUserId(userId);
        if (target == null) target = SecurityUtil.getUserId();
        if (target == null) throw new BizException("请先选择员工");
        return Result.ok(leaveService.balance(target));
    }

    /**
     * 未处理的余额透支提醒（管理员/录入员可见）。
     * <p>
     * 当透支员工余额恢复为 &gt;= 0 时，服务端自动置为已处理，消息自动消失。
     *
     * <p>请求路径：{@code GET /api/leave/messages}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 code=0 及未处理的预警消息列表；失败抛出「无权限」
     */
    @Operation(summary = "余额透支预警消息", description = "返回未处理的透支提醒；余额恢复后消息自动消失")
    @GetMapping("/messages")
    public Result<List<LeaveDto.MessageResponse>> messages() {
        requireManage();
        return Result.ok(leaveService.messages());
    }

    /**
     * 标记预警消息为已读（不会让消息消失，余额恢复后才会自动置为已处理）。
     *
     * <p>请求路径：{@code POST /api/leave/messages/{id}/read}；所需权限：ADMIN / CLERK。
     *
     * @param id 消息 ID
     * @return 成功返回 code=0 空数据；失败抛出「无权限」或「消息不存在」
     */
    @Operation(summary = "标记消息已读", description = "将指定透支预警消息标记为已读")
    @PostMapping("/messages/{id}/read")
    public Result<Void> markRead(@Parameter(description = "消息 ID") @PathVariable Long id) {
        requireManage();
        leaveService.markRead(id);
        return Result.ok();
    }

    /**
     * 构造文件下载响应头。
     *
     * <p>要点：文件名做 UTF-8 百分号编码（并把 + 还原为 %20 以符合 URL 编码规范），
     * 同时通过 {@code Access-Control-Expose-Headers} 暴露 Content-Disposition，
     * 否则跨域场景下前端 JS 读不到该头、拿不到文件名。
     *
     * @param filename 下载时显示的文件名（可含中文）
     * @return 组装好的响应头
     */
    private static HttpHeaders downloadHeaders(String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            encoded = filename;
        }
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        headers.setAccessControlExposeHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
        return headers;
    }
}

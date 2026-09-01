package com.tiaoxiu.controller;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.SecurityUtil;
import com.tiaoxiu.entity.LeaveRequest;
import com.tiaoxiu.entity.OvertimeRecord;
import com.tiaoxiu.repository.LeaveRepository;
import com.tiaoxiu.repository.OvertimeRepository;
import com.tiaoxiu.service.ExcelService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 通用导入导出接口（早期版本实现，基于 EasyExcel，不带查询条件）。
 *
 * <p>统一路由前缀：{@code /api}；整体权限要求：所有接口均需登录且需 ADMIN 或 CLERK 角色。
 *
 * <p>说明：加班模块新增了带查询条件的导入导出（见 {@link OvertimeController} 的
 * {@code /api/overtime/import}、{@code /api/overtime/export}、{@code /api/overtime/template}），
 * 本控制器保留仅为兼容历史前端调用与导出历史遗留的 leave_requests 数据。
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    private final ExcelService excelService;
    private final OvertimeRepository overtimeRepository;
    private final LeaveRepository leaveRepository;

    /**
     * 构造器注入。
     *
     * @param excelService       Excel 导入导出服务
     * @param overtimeRepository 加班记录仓储（导出全量）
     * @param leaveRepository    调休申请（历史遗留）仓储
     */
    public UploadController(ExcelService excelService, OvertimeRepository overtimeRepository, LeaveRepository leaveRepository) {
        this.excelService = excelService;
        this.overtimeRepository = overtimeRepository;
        this.leaveRepository = leaveRepository;
    }

    /**
     * 导入加班明细（不带查询条件的旧版导入）。
     *
     * <p>请求路径：{@code POST /api/import/overtime}（multipart/form-data）；所需权限：ADMIN / CLERK。
     *
     * @param file Excel 文件
     * @return 成功返回 code=0 及导入结果（success 成功数、failed 失败数、errors 错误明细）；
     *         失败抛出「无权限：仅管理员/录入员可导入」或「文件为空」
     */
    @PostMapping("/import/overtime")
    public Result<Map<String, Object>> importOvertime(@RequestParam("file") MultipartFile file) {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限：仅管理员/录入员可导入");
        if (file == null || file.isEmpty()) throw new BizException("文件为空");
        return Result.ok(excelService.importOvertime(file));
    }

    /**
     * 下载加班导入模板（列与 {@code /api/import/overtime} 一致）。
     *
     * <p>请求路径：{@code GET /api/template/overtime}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 xlsx 模板文件流；失败抛出「无权限」
     */
    @GetMapping("/template/overtime")
    public ResponseEntity<byte[]> templateOvertime() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        return buildResponse(excelService.overtimeTemplate(), "调休管家_加班导入模板.xlsx");
    }

    /**
     * 导出全部加班明细（不支持按条件筛选）。
     *
     * <p>请求路径：{@code GET /api/export/overtime}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 xlsx 文件流；失败抛出「无权限」
     */
    @GetMapping("/export/overtime")
    public ResponseEntity<byte[]> exportOvertime() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        List<OvertimeRecord> list = overtimeRepository.findAll();
        byte[] data = excelService.exportOvertime(list);
        return buildResponse(data, "调休管家_加班明细.xlsx");
    }

    /**
     * 导出全部历史调休申请记录（leave_requests 表，已废弃但保留导出能力）。
     *
     * <p>请求路径：{@code GET /api/export/leave}；所需权限：ADMIN / CLERK。
     *
     * @return 成功返回 xlsx 文件流；失败抛出「无权限」
     */
    @GetMapping("/export/leave")
    public ResponseEntity<byte[]> exportLeave() {
        if (!SecurityUtil.hasAnyRole("ADMIN", "CLERK")) throw new BizException("无权限");
        List<LeaveRequest> list = leaveRepository.findAll();
        byte[] data = excelService.exportLeave(list);
        return buildResponse(data, "调休管家_调休记录.xlsx");
    }

    /**
     * 构造文件下载响应。
     *
     * <p>这里不能用 {@code setContentDispositionFormData}：它会生成
     * {@code form-data; name="attachment"; filename="中文.xlsx"}，既不是合法的下载头，
     * 又因 HTTP 头只接受 ISO-8859-1 而被 Tomcat 整条丢弃（日志里表现为
     * “The Unicode character [调] … cannot be encoded”），前端因此拿不到文件名。
     * 必须改用 RFC 5987 的 {@code filename*=UTF-8''<百分号编码>} 写法。
     *
     * @param data     文件字节流
     * @param filename 下载时显示的文件名（支持中文）
     * @return 组装好的响应实体
     */
    private ResponseEntity<byte[]> buildResponse(byte[] data, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; " + encodedFilename(filename));
        // 跨域场景下前端需要读取 Content-Disposition 才能拿到文件名，必须显式暴露
        headers.setAccessControlExposeHeaders(java.util.List.of(HttpHeaders.CONTENT_DISPOSITION));
        return ResponseEntity.ok().headers(headers).body(data);
    }

    /** 按 RFC 5987 编码中文文件名：{@code filename*=UTF-8''%E8%B0%83...} */
    private static String encodedFilename(String filename) {
        try {
            return "filename*=UTF-8''" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            return "filename=\"" + filename + "\"";
        }
    }
}

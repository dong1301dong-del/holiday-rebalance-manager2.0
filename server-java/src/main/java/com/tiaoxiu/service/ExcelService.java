package com.tiaoxiu.service;

import com.alibaba.excel.EasyExcel;
import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.OvertimeDto;
import com.tiaoxiu.entity.LeaveRequest;
import com.tiaoxiu.entity.OvertimeRecord;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.repository.LeaveRepository;
import com.tiaoxiu.repository.OvertimeRepository;
import com.tiaoxiu.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Excel 导入导出（加班明细导入、加班/调休记录导出）。 */
@Service
public class ExcelService {

    private final UserRepository userRepository;
    private final OvertimeService overtimeService;
    private final OvertimeRepository overtimeRepository;
    private final LeaveRepository leaveRepository;

    /**
     * 构造器注入。
     *
     * <p>注意：本类基于 EasyExcel 实现，是早期版本的导入导出实现；
     * 加班模块新的导入导出请优先使用 {@link OvertimeService} 中基于 Apache POI 的方法。
     *
     * @param userRepository     用户仓储（按账号反查员工）
     * @param overtimeService    加班服务（复用其录入逻辑与模式解析）
     * @param overtimeRepository 加班记录仓储
     * @param leaveRepository    调休申请（历史遗留）仓储
     */
    public ExcelService(UserRepository userRepository, OvertimeService overtimeService,
                        OvertimeRepository overtimeRepository, LeaveRepository leaveRepository) {
        this.userRepository = userRepository;
        this.overtimeService = overtimeService;
        this.overtimeRepository = overtimeRepository;
        this.leaveRepository = leaveRepository;
    }

    /**
     * 导入加班明细：列 = 员工姓名/工号,加班日期,模式,开始时间,结束时间,打卡时间,转休时长,备注。
     * 模式为「其他转调休」时只需员工、日期、模式、转休时长、备注；缺省为「加班转调休」。
     *
     * @param file 上传的 Excel 文件
     * @return 导入结果 Map：success 成功数、failed/fail 失败数（两个键同义，兼容不同前端调用）、errors 错误明细
     * @throws BizException Excel 解析失败时抛出
     */
    public Map<String, Object> importOvertime(MultipartFile file) {
        List<Map<Integer, String>> rows;
        try {
            rows = (List) EasyExcel.read(file.getInputStream()).sheet().doReadSync();
        } catch (Exception e) {
            throw new BizException("Excel 解析失败：" + e.getMessage());
        }
        int success = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<Integer, String> row = rows.get(i);
            // 跳过表头（首行含"用户名"或"日期"）
            if (i == 0 && isHeader(row)) continue;
            try {
                String username = cell(row, 0);
                String date = cell(row, 1);
                String mode = cell(row, 2);
                String start = cell(row, 3);
                String end = cell(row, 4);
                String clockIn = cell(row, 5);
                String convertedHours = cell(row, 6);
                String remark = cell(row, 7);
                if (username == null || username.isBlank()) continue;
                User u = userRepository.findByUsername(username.trim())
                        .orElseThrow(() -> new BizException("成员不存在：" + username));
                OvertimeDto.RecordInput req = new OvertimeDto.RecordInput();
                req.setUserId(u.getId());
                req.setDate(LocalDate.parse(date.trim(), DateTimeFormatter.ISO_LOCAL_DATE));
                req.setMode(OvertimeService.parseMode(mode));
                req.setStartTime(blankToNull(start) == null ? null : LocalTime.parse(start.trim()));
                req.setEndTime(blankToNull(end) == null ? null : LocalTime.parse(end.trim()));
                req.setClockInTime(blankToNull(clockIn) == null ? null : LocalTime.parse(clockIn.trim()));
                req.setConvertedHours(blankToNull(convertedHours) == null ? null : new BigDecimal(convertedHours.trim()));
                req.setRemark(remark);
                req.setSource("import");
                overtimeService.create(req, u.getId());
                success++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行：" + e.getMessage());
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("success", success);
        // failed 与 fail 同时返回，兼容历史前端两种字段命名，避免改前端
        res.put("failed", errors.size());
        res.put("fail", errors.size());
        res.put("errors", errors);
        return res;
    }

    /**
     * 生成加班导入模板：表头 + 两行示例（加班转调休 / 其他转调休），列与 {@link #importOvertime} 一致。
     *
     * @return xlsx 文件字节流
     */
    public byte[] overtimeTemplate() {
        List<List<String>> head = List.of(
                List.of("员工姓名/工号"), List.of("加班日期"), List.of("模式"), List.of("开始时间"),
                List.of("结束时间"), List.of("打卡时间"), List.of("转休时长"), List.of("备注"));
        List<List<Object>> data = new ArrayList<>();
        data.add(Arrays.<Object>asList("zhangsan", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "加班转调休", "18:00", "20:30", "09:00", "", "项目赶工"));
        data.add(Arrays.<Object>asList("lisi", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
                "其他转调休", "", "", "", "4", "历史结转补休"));
        return write(head, data);
    }

    /**
     * 导出加班记录。
     *
     * @param list 待导出的加班记录列表
     * @return xlsx 文件字节流
     */
    public byte[] exportOvertime(List<OvertimeRecord> list) {
        List<List<String>> head = List.of(
                List.of("ID"), List.of("成员ID"), List.of("日期"), List.of("模式"), List.of("日类型"),
                List.of("开始"), List.of("结束"), List.of("打卡时间"),
                List.of("加班时长"), List.of("比例"), List.of("转调休时长"), List.of("状态"));
        List<List<Object>> data = new ArrayList<>();
        for (OvertimeRecord r : list) {
            // 打卡时间、比例等在其他转休模式下可为空，故不能用 List.of（不允许 null）
            // 时间类型统一转字符串：EasyExcel 对 java.time.LocalTime 无内置转换器，直接写对象会抛
            // ExcelDataConvertException，进而导致 SXSSF 在 finishOnException 时 dispose 失败（Stream closed）
            data.add(Arrays.<Object>asList(str(r.getId()), str(r.getUserId()), str(r.getDate()),
                    OvertimeService.modeLabel(r.getRecordMode()), r.getDayType(),
                    str(r.getStartTime()), str(r.getEndTime()), str(r.getClockInTime()),
                    str(r.getHours()), str(r.getRatio()), str(r.getConvertedHours()), r.getStatus()));
        }
        return write(head, data);
    }

    /**
     * 导出调休申请记录（历史遗留表 leave_requests 的数据）。
     *
     * @param list 待导出的调休申请列表
     * @return xlsx 文件字节流
     */
    public byte[] exportLeave(List<LeaveRequest> list) {
        List<List<String>> head = List.of(
                List.of("申请单号"), List.of("成员ID"), List.of("开始日期"), List.of("开始时段"),
                List.of("结束日期"), List.of("结束时段"), List.of("时长(h)"), List.of("天数"), List.of("状态"), List.of("原因"));
        List<List<Object>> data = new ArrayList<>();
        for (LeaveRequest r : list) {
            // 历史数据里 requestNo / reason 等字段可能为 null，List.of 不允许 null 会直接 NPE，
            // 因此统一用 Arrays.asList 并做空值转换
            data.add(Arrays.<Object>asList(str(r.getRequestNo()), str(r.getUserId()), str(r.getStartDate()),
                    r.getStartPeriod(), str(r.getEndDate()), r.getEndPeriod(),
                    str(r.getHours()), str(r.getDays()), r.getStatus(), r.getReason()));
        }
        return write(head, data);
    }

    /**
     * 单元格取值：null 转空串，并把 EasyExcel 不支持的时间类型转成可读字符串。
     *
     * <p>EasyExcel 没有 {@code java.time.LocalTime} 的 Converter，直接写对象会抛
     * {@code Can not find 'Converter' support class LocalTime}（LocalDate 同理保险起见一并处理）。
     * 数字与 BigDecimal 保持原类型，这样导出的时长在 Excel 里仍可直接求和。
     *
     * @param v 原始字段值
     * @return 可安全写入单元格的值
     */
    private static Object str(Object v) {
        if (v == null) return "";
        if (v instanceof java.time.LocalTime t) return TIME_FMT.format(t);
        if (v instanceof java.time.LocalDate d) return DateTimeFormatter.ISO_LOCAL_DATE.format(d);
        if (v instanceof java.time.LocalDateTime dt) return DateTimeFormatter.ISO_LOCAL_DATE.format(dt.toLocalDate());
        return v;
    }

    /** 时间显示格式：HH:mm */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 用 EasyExcel 写出工作表。
     *
     * @param head 表头（每列一个单元素列表）
     * @param data 数据行
     * @return xlsx 文件字节流
     */
    private byte[] write(List<List<String>> head, List<List<Object>> data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out).head(head).sheet("导出").doWrite(data);
        return out.toByteArray();
    }

    /**
     * 判断是否为表头行：首列包含「用户名 / 日期 / 姓名 / 工号 / 员工」之一即认为是表头。
     *
     * @param row 行数据
     * @return 是表头返回 true
     */
    private boolean isHeader(Map<Integer, String> row) {
        String c0 = cell(row, 0);
        return c0 != null && (c0.contains("用户名") || c0.contains("日期")
                || c0.contains("姓名") || c0.contains("工号") || c0.contains("员工"));
    }

    /**
     * 安全取单元格值并去空格。
     *
     * @param row 行数据
     * @param idx 列下标
     * @return 单元格文本；不存在或为 null 时返回 null
     */
    private String cell(Map<Integer, String> row, int idx) {
        String v = row.get(idx);
        return v == null ? null : v.trim();
    }

    /**
     * 空白字符串转 null，便于用「== null」统一判断「该列未填写」。
     *
     * @param v 原始值
     * @return 去空格后的值；为空则返回 null
     */
    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}

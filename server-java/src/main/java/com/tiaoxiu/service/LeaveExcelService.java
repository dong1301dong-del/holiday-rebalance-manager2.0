package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.LeaveDto;
import com.tiaoxiu.entity.User;
import com.tiaoxiu.repository.UserRepository;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 调休使用记录的 Excel 导入 / 导出 / 模板下载。
 * <p>
 * 独立于 ExcelService，避免与加班模块的导入导出互相影响。
 * 模板列顺序：<b>员工姓名、使用日期、开始时间、结束时间、备注</b>。
 * 生成 .xlsx 时使用 Apache POI，便于控制列宽、表头样式与多工作表。
 */
@Service
public class LeaveExcelService {

    private static final String[] HEAD = {"员工姓名", "使用日期", "开始时间", "结束时间", "备注"};
    private static final Pattern DATE_SEP = Pattern.compile("[/.]");

    private final UserRepository userRepository;
    private final LeaveService leaveService;

    /**
     * 构造器注入。
     *
     * @param userRepository 用户仓储（按姓名反查员工）
     * @param leaveService   调休使用服务（复用其单条录入逻辑）
     */
    public LeaveExcelService(UserRepository userRepository, LeaveService leaveService) {
        this.userRepository = userRepository;
        this.leaveService = leaveService;
    }

    /**
     * 批量导入。逐行校验，单行失败不影响其余行。
     * 导入场景下不阻断透支（allowOverdraft=true），透支时同样生成余额预警消息。
     *
     * @param file 上传的 Excel 文件
     * @return 导入结果（成功数、失败数、逐行错误）
     * @throws BizException Excel 解析失败时抛出
     */
    public LeaveDto.ImportResult importLeaveUsage(MultipartFile file) {
        List<List<String>> rows;
        try (org.apache.poi.ss.usermodel.Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            rows = readRows(wb);
        } catch (IOException e) {
            throw new BizException("Excel 读取失败：" + e.getMessage());
        } catch (Exception e) {
            throw new BizException("Excel 解析失败：" + e.getMessage());
        }

        LeaveDto.ImportResult result = new LeaveDto.ImportResult();
        if (rows == null || rows.isEmpty()) {
            result.setErrors(List.of("文件为空或未读取到任何数据"));
            return result;
        }

        Map<String, User> nameIndex = new LinkedHashMap<>();
        Map<String, Integer> duplicated = new LinkedHashMap<>();
        for (User u : userRepository.findByStatusNot(User.STATUS_DELETED)) {
            if (u.getName() == null) continue;
            String key = u.getName().trim();
            // 记录重名：后续遇到同名时提示改用手工录入，避免扣错人的余额
            if (nameIndex.containsKey(key)) duplicated.put(key, 1);
            else nameIndex.put(key, u);
        }

        int success = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int lineNo = i + 1;
            if (i == 0 && isHeader(row)) continue;
            String name = cell(row, 0);
            if (name == null || name.isBlank()) continue; // 空行跳过
            try {
                User u = nameIndex.get(name.trim());
                if (u == null) {
                    if (duplicated.containsKey(name.trim())) {
                        throw new BizException("存在同名员工，请改用手工录入：" + name);
                    }
                    throw new BizException("员工不存在：" + name);
                }
                LeaveDto.LeaveUsageItem item = new LeaveDto.LeaveUsageItem();
                item.setUserId(u.getId());
                item.setDate(parseDate(cell(row, 1)));
                item.setStartTime(parseTime(cell(row, 2)));
                item.setEndTime(parseTime(cell(row, 3)));
                item.setRemark(cell(row, 4));
                leaveService.createOne(item, true);
                success++;
            } catch (BizException e) {
                errors.add("第" + lineNo + "行：" + e.getMessage());
            } catch (Exception e) {
                errors.add("第" + lineNo + "行：" + String.valueOf(e.getMessage()));
            }
        }
        // 一条都没读到（既无成功也无失败）说明表里没有可识别的数据行，
        // 必须给出明确原因，否则前端只会显示「成功 0 条，失败 0 条」让人无从排查。
        if (success == 0 && errors.isEmpty()) {
            throw new BizException("未读取到任何数据行：请确认数据填写在第一个工作表「导入模板」中，"
                    + "第一列为员工姓名且不能为空；「填写说明」工作表仅供参考，填在那里不会被导入。");
        }
        result.setSuccess(success);
        result.setFailed(errors.size());
        result.setErrors(errors);
        return result;
    }

    /**
     * 导出调休使用记录（列与模板一致）。
     *
     * @param list 待导出的记录列表
     * @return xlsx 文件字节流
     */
    public byte[] exportLeaveUsage(List<LeaveDto.LeaveUsageResponse> list) {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("调休使用记录");
            CellStyle headStyle = headStyle(wb);
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEAD.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(HEAD[i]);
                c.setCellStyle(headStyle);
            }
            for (int i = 0; i < list.size(); i++) {
                LeaveDto.LeaveUsageResponse r = list.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(r.getUserName() == null ? "" : r.getUserName());
                row.createCell(1).setCellValue(r.getDate() == null ? "" : r.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                row.createCell(2).setCellValue(fmtTime(r.getStartTime()));
                row.createCell(3).setCellValue(fmtTime(r.getEndTime()));
                row.createCell(4).setCellValue(r.getRemark() == null ? "" : r.getRemark());
            }
            for (int i = 0; i < HEAD.length; i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 3840) sheet.setColumnWidth(i, 3840);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("导出失败：" + e.getMessage());
        }
    }

    /**
     * 生成导入模板：表头 + 一行示例 + 「填写说明」工作表。
     *
     * @return xlsx 文件字节流
     */
    public byte[] template() {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headStyle = headStyle(wb);

            // 模板主表
            Sheet sheet = wb.createSheet("导入模板");
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEAD.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(HEAD[i]);
                c.setCellStyle(headStyle);
            }
            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("张三");
            sample.createCell(1).setCellValue("2026-01-01");
            sample.createCell(2).setCellValue("09:00");
            sample.createCell(3).setCellValue("12:00");
            sample.createCell(4).setCellValue("示例（请删除本行）");
            for (int i = 0; i < HEAD.length; i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 3840) sheet.setColumnWidth(i, 3840);
            }

            // 填写说明
            Sheet guide = wb.createSheet("填写说明");
            String[] guideHead = {"列名", "必填", "格式说明"};
            Row gHead = guide.createRow(0);
            for (int i = 0; i < guideHead.length; i++) {
                Cell c = gHead.createCell(i);
                c.setCellValue(guideHead[i]);
                c.setCellStyle(headStyle);
            }
            String[][] guideRows = {
                    {"员工姓名", "是", "与系统用户管理中的姓名完全一致；同名员工请改用手工录入"},
                    {"使用日期", "是", "yyyy-MM-dd，例如 2026-01-01"},
                    {"开始时间", "是", "HH:mm，例如 09:00（须为整点或半点）"},
                    {"结束时间", "是", "HH:mm，必须晚于开始时间"},
                    {"备注", "否", "选填，长度不超过 200 字"}
            };
            for (int i = 0; i < guideRows.length; i++) {
                Row r = guide.createRow(i + 1);
                for (int j = 0; j < guideRows[i].length; j++) r.createCell(j).setCellValue(guideRows[i][j]);
            }
            guide.autoSizeColumn(0);
            guide.autoSizeColumn(1);
            guide.autoSizeColumn(2);
            if (guide.getColumnWidth(0) < 3840) guide.setColumnWidth(0, 3840);
            if (guide.getColumnWidth(1) < 2560) guide.setColumnWidth(1, 2560);
            if (guide.getColumnWidth(2) < 7680) guide.setColumnWidth(2, 7680);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException("模板生成失败：" + e.getMessage());
        }
    }

    private CellStyle headStyle(org.apache.poi.ss.usermodel.Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
     * 读取第一个工作表的全部行，每格统一格式化为字符串。
     *
     * @param wb 工作簿
     * @return 行数据（每行是一个单元格字符串列表）
     */
    private List<List<String>> readRows(org.apache.poi.ss.usermodel.Workbook wb) {
        Sheet sheet = wb.getNumberOfSheets() > 0 ? wb.getSheetAt(0) : wb.createSheet();
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            List<String> cells = new ArrayList<>();
            for (int j = 0; j < HEAD.length; j++) {
                Cell cell = row.getCell(j);
                cells.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    /**
     * 判断是否为表头行：首列含「姓名」或「员工」即认为是表头。
     *
     * @param row 行数据
     * @return 是表头返回 true
     */
    private static boolean isHeader(List<String> row) {
        String c0 = cell(row, 0);
        return c0 != null && (c0.contains("姓名") || c0.contains("员工"));
    }

    /**
     * 安全取单元格值并去空格。
     *
     * @param row 行数据
     * @param idx 列下标
     * @return 单元格文本；不存在或为 null 时返回 null
     */
    private static String cell(List<String> row, int idx) {
        if (idx >= row.size()) return null;
        String v = row.get(idx);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 时间格式化为 HH:mm；为 null 时返回空串。
     *
     * @param t 时间，可为 null
     * @return 格式化后的文本
     */
    private static String fmtTime(LocalTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /**
     * 解析日期：先归一化中文年月日与 . / 分隔符，再按 yyyy-MM-dd 解析。
     *
     * @param raw 原始文本
     * @return 解析后的日期
     * @throws BizException 为空或格式错误时抛出
     */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) throw new BizException("使用日期不能为空");
        String s = raw.trim().replace("年", "-").replace("月", "-").replace("日", "");
        s = DATE_SEP.matcher(s).replaceAll("-");
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new BizException("使用日期格式错误（应为 2026-01-01）：" + raw);
        }
    }

    /**
     * 解析时间：兼容中文全角冒号、只写小时（如「9」）与 HH:mm 三种写法。
     *
     * @param raw 原始文本
     * @return 解析后的时间
     * @throws BizException 为空或格式错误时抛出
     */
    static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) throw new BizException("开始/结束时间不能为空");
        String s = raw.trim().replace("：", ":");
        // 只填了小时（如「9」「18」）时补充分钟为 00
        if (s.matches("^\\d{1,2}$")) {
            int hour = Integer.parseInt(s);
            if (hour > 23) throw new BizException("时间格式错误：" + raw);
            return LocalTime.of(hour, 0);
        }
        String[] parts = s.split(":");
        if (parts.length < 2) throw new BizException("时间格式错误（应为 09:00）：" + raw);
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            return LocalTime.of(h, m);
        } catch (Exception e) {
            throw new BizException("时间格式错误（应为 09:00）：" + raw);
        }
    }
}

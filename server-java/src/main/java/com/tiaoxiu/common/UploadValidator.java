package com.tiaoxiu.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 上传文件校验：非空、扩展名白名单与行数上限。
 *
 * <p>把 Excel 等批量导入文件的常见非法情况在校验阶段就拦下，避免脏数据进入业务解析，
 * 报错文案直接返回给前端，便于用户自助修正后重试。
 *
 * <p>扩展名白名单与最大行数均可通过配置项调整：
 * <ul>
 *   <li>{@code app.upload.allowed-extensions}：允许的扩展名，逗号分隔，默认 {@code xlsx,xls}；</li>
 *   <li>{@code app.upload.max-rows}：单次导入最大数据行数，默认 5000。</li>
 * </ul>
 */
@Component
public class UploadValidator {

    /** 允许的扩展名白名单，逗号分隔，默认 xlsx,xls */
    @Value("${app.upload.allowed-extensions:xlsx,xls}")
    private String allowedExtensions;

    /** 单次导入允许的最大数据行数，默认 5000 */
    @Value("${app.upload.max-rows:5000}")
    private int maxRows;

    /**
     * 校验上传文件是否非空且为允许的扩展名。
     *
     * @param file 上传的 MultipartFile
     * @throws BizException 文件为空、无文件名或扩展名不在白名单时抛出
     */
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的文件");
        }
        String name = file.getOriginalFilename();
        if (!StringUtils.hasText(name)) {
            throw new BizException("文件名为空，无法识别文件类型");
        }
        if (!isAllowedExtension(name)) {
            throw new BizException("不支持的文件类型，仅支持 " + allowedListText() + " 格式");
        }
    }

    /**
     * 校验数据行数是否超过上限。
     *
     * @param rowCount 实际数据行数（不含表头）
     * @throws BizException 超过 {@link #maxRows} 时抛出
     */
    public void requireRowCountWithinLimit(int rowCount) {
        if (rowCount > maxRows) {
            throw new BizException("文件数据行数过多（" + rowCount + " 行），单次最多导入 " + maxRows + " 行，请拆分后重试");
        }
    }

    /**
     * 判断文件名是否落在允许的扩展名白名单内（大小写不敏感）。
     *
     * @param filename 原始文件名
     * @return true 表示允许导入
     */
    public boolean isAllowedExtension(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        String ext = filename.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
        return allowedExtensionList().contains(ext);
    }

    private List<String> allowedExtensionList() {
        return Arrays.stream(StringUtils.hasText(allowedExtensions) ? allowedExtensions.split(",") : new String[0])
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private String allowedListText() {
        return allowedExtensionList().stream().map(e -> "." + e).collect(Collectors.joining(" / "));
    }

    /** @return 单次导入允许的最大数据行数 */
    public int getMaxRows() {
        return maxRows;
    }
}

package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.ConfigDto;
import com.tiaoxiu.entity.SystemConfig;
import com.tiaoxiu.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 系统配置（键值对）。 */
@Service
public class ConfigService {

    /** 法定工作日折算比例配置键 */
    public static final String KEY_RATIO_WORKDAY = "leave.ratio.workday";
    /** 休息日（含法定节假日）折算比例配置键 */
    public static final String KEY_RATIO_REST = "leave.ratio.rest";
    /** 法定工作日默认折算比例：0.5 */
    public static final BigDecimal DEFAULT_RATIO_WORKDAY = new BigDecimal("0.5");
    /** 休息日默认折算比例：1 */
    public static final BigDecimal DEFAULT_RATIO_REST = BigDecimal.ONE;

    private final SystemConfigRepository configRepository;
    /** 全量配置缓存；set 后失效，snapshot() 懒加载重建。 */
    private volatile Map<String, String> cache;

    /**
     * 构造器注入。
     *
     * @param configRepository 系统配置仓储
     */
    public ConfigService(SystemConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /**
     * 查询全部系统配置项。
     *
     * @return 配置项列表
     */
    public List<SystemConfig> list() {
        return configRepository.findAll();
    }

    /**
     * 按配置键查询配置。
     *
     * @param key 配置键
     * @return 命中的配置；不存在时返回 {@link Optional#empty()}
     */
    public Optional<SystemConfig> get(String key) {
        return configRepository.findByKey(key);
    }

    /**
     * 新增或修改配置（按键 upsert：存在则更新值与描述，不存在则新建）。
     *
     * @param req 配置请求
     * @return 保存后的配置
     * @throws BizException 配置键为空时抛出
     */
    @Transactional
    public SystemConfig set(ConfigDto.UpsertRequest req) {
        if (!StringUtils.hasText(req.getKey())) throw new BizException("配置键不能为空");
        SystemConfig cfg = configRepository.findByKey(req.getKey()).orElseGet(() -> {
            SystemConfig c = new SystemConfig();
            c.setKey(req.getKey());
            return c;
        });
        cfg.setValue(req.getValue());
        cfg.setDescription(req.getDescription());
        SystemConfig saved = configRepository.save(cfg);
        this.cache = null;
        return saved;
    }

    /**
     * 读取配置字符串，缺失或为空时返回默认值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    public String getString(String key, String defaultValue) {
        String v = snapshot().get(key);
        return StringUtils.hasText(v) ? v : defaultValue;
    }

    /**
     * 读取配置为 BigDecimal，解析失败时返回默认值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 解析出的数值或默认值
     */
    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String v = snapshot().get(key);
        if (!StringUtils.hasText(v)) return defaultValue;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 读取配置为 int，解析失败时返回默认值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 解析出的整数或默认值
     */
    public int getInt(String key, int defaultValue) {
        String v = snapshot().get(key);
        if (!StringUtils.hasText(v)) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** @return 法定工作日折算比例（可配置，默认 0.5）。 */
    public BigDecimal ratioWorkday() {
        return getDecimal(KEY_RATIO_WORKDAY, DEFAULT_RATIO_WORKDAY);
    }

    /** @return 休息日折算比例（可配置，默认 1）。 */
    public BigDecimal ratioRest() {
        return getDecimal(KEY_RATIO_REST, DEFAULT_RATIO_REST);
    }

    /**
     * 懒加载全量配置为 Map 并缓存；缓存失效（set 后置 null）时重建。
     *
     * @return 配置键到值的映射
     */
    private Map<String, String> snapshot() {
        Map<String, String> c = this.cache;
        if (c == null) {
            HashMap<String, String> built = new HashMap<>();
            for (SystemConfig sc : configRepository.findAll()) {
                built.put(sc.getKey(), sc.getValue());
            }
            this.cache = c = built;
        }
        return c;
    }
}

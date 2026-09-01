package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.ConfigDto;
import com.tiaoxiu.entity.SystemConfig;
import com.tiaoxiu.repository.SystemConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/** 系统配置（键值对）。 */
@Service
public class ConfigService {

    private final SystemConfigRepository configRepository;

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
        return configRepository.save(cfg);
    }
}

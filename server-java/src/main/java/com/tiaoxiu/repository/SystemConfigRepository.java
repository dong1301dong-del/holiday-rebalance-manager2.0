package com.tiaoxiu.repository;

import com.tiaoxiu.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 系统配置的数据访问接口：操作表 {@code system_config}。
 *
 * <p>提供的查询能力：按配置键精确查询（配置键全局唯一）。
 * 业务层读取配置时通常在此之上再包一层「找不到就返回默认值」的逻辑，
 * 使系统即使缺少某条配置也能正常启动。
 */
@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {

    /**
     * 按配置键精确查询。
     *
     * @param key 配置键，如 {@code overtime.dailyCap}
     * @return 命中则返回配置；未配置返回 {@link Optional#empty()}
     */
    Optional<SystemConfig> findByKey(String key);
}

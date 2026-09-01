package com.tiaoxiu.repository;

import com.tiaoxiu.entity.DictType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 数据字典类型的数据访问接口：操作表 {@code dict_types}。
 *
 * <p>提供的查询能力：按编码精确查、判断编码是否已存在（新增查重）、按状态查全量类型列表。
 */
@Repository
public interface DictTypeRepository extends JpaRepository<DictType, Long> {

    /**
     * 按字典类型编码精确查询。
     *
     * @param code 字典类型编码
     * @return 命中则返回类型；不存在返回 {@link Optional#empty()}
     */
    Optional<DictType> findByCode(String code);

    /**
     * 判断字典类型编码是否已存在。
     *
     * @param code 字典类型编码
     * @return 已存在返回 true
     */
    boolean existsByCode(String code);

    /**
     * 按状态查询字典类型列表，按 ID 升序。
     *
     * @param status 状态，如 {@code ACTIVE}
     * @return 匹配的类型列表
     */
    List<DictType> findByStatusOrderByIdAsc(String status);
}

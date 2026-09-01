package com.tiaoxiu.repository;

import com.tiaoxiu.entity.DictData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 数据字典项的数据访问接口：操作表 {@code dict_data}。
 *
 * <p>提供的查询能力：按字典类型编码取全部选项（不限状态，管理端维护时用）、
 * 按类型编码 + 状态取选项（业务端只取启用项），两者都按排序号升序。
 */
@Repository
public interface DictDataRepository extends JpaRepository<DictData, Long> {

    /**
     * 查询某字典类型下的全部选项，按排序号升序（不限状态）。
     *
     * @param typeCode 字典类型编码
     * @return 该类型下的字典项列表
     */
    List<DictData> findByTypeCodeOrderBySortAsc(String typeCode);

    /**
     * 查询某字典类型下指定状态的选项，按排序号升序。
     *
     * @param typeCode 字典类型编码
     * @param status   状态，如 {@code ACTIVE}
     * @return 匹配的字典项列表
     */
    List<DictData> findByTypeCodeAndStatusOrderBySortAsc(String typeCode, String status);
}

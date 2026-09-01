package com.tiaoxiu.repository;

import com.tiaoxiu.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 资源（菜单 / 按钮 / 接口权限）的数据访问接口：操作表 {@code resources}。
 *
 * <p>提供的查询能力：按类型 + 状态查（如取所有启用中的菜单）、按父节点 + 状态查（构建资源树时逐层下钻）、
 * 按状态查全量（一次性拉全表再在内存中组装树）、按权限编码精确查（后端鉴权与初始化时的存在性判断）。
 *
 * <p>所有查询都按 {@code sort} 升序，保证菜单展示顺序稳定。
 */
@Repository
public interface ResourceRepository extends JpaRepository<Resource, Long> {

    /**
     * 按资源类型与状态查询，按排序号升序。
     *
     * @param type   资源类型，见 Resource.TYPE_*
     * @param status 状态，见 Resource.STATUS_*
     * @return 匹配的资源列表
     */
    List<Resource> findByTypeAndStatusOrderBySortAsc(String type, String status);

    /**
     * 按父节点与状态查询子节点，按排序号升序。
     *
     * @param parentId 父资源 ID；顶层资源的父 ID 为 null
     * @param status   状态，见 Resource.STATUS_*
     * @return 该父节点下的子资源列表
     */
    List<Resource> findByParentIdAndStatusOrderBySortAsc(Long parentId, String status);

    /**
     * 按状态查询全部资源，按排序号升序（用于在内存中一次性组装完整资源树）。
     *
     * @param status 状态，见 Resource.STATUS_*
     * @return 匹配的资源列表
     */
    List<Resource> findByStatusOrderBySortAsc(String status);

    /**
     * 按权限编码精确查询资源。
     *
     * @param code 权限编码，如 {@code overtime:add}
     * @return 命中则返回资源；不存在返回 {@link java.util.Optional#empty()}
     */
    java.util.Optional<Resource> findByCode(String code);
}

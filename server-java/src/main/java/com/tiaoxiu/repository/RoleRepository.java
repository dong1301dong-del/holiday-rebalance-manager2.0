package com.tiaoxiu.repository;

import com.tiaoxiu.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 角色的数据访问接口：操作表 {@code roles}。
 *
 * <p>提供的查询能力：按角色编码精确查（鉴权与初始化时最常用）、按编码集合批量查（一次取出某用户的全部角色）、
 * 判断编码是否已存在（新增角色查重）。
 *
 * <p>说明：角色目前只通过逻辑状态字段区分启停用，未做物理删除，因此没有额外的状态过滤方法。
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    /**
     * 按角色编码精确查询。
     *
     * @param code 角色编码，如 {@code ADMIN}
     * @return 命中则返回角色；不存在返回 {@link Optional#empty()}
     */
    Optional<Role> findByCode(String code);

    /**
     * 按角色编码集合批量查询，避免循环单查。
     *
     * @param codes 角色编码列表
     * @return 命中的角色列表
     */
    List<Role> findByCodeIn(List<String> codes);

    /**
     * 判断角色编码是否已存在。
     *
     * @param code 角色编码
     * @return 已存在返回 true
     */
    boolean existsByCode(String code);
}

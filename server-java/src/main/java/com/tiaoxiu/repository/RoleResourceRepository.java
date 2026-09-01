package com.tiaoxiu.repository;

import com.tiaoxiu.entity.RoleResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 角色与资源绑定关系的数据访问接口：操作表 {@code role_resources}。
 *
 * <p>提供的查询能力：按角色查其拥有的全部资源、按角色集合批量查（一次取出多角色的权限并集）、
 * 按角色删除其全部绑定（「分配权限」时先清空再批量重写）。
 */
@Repository
public interface RoleResourceRepository extends JpaRepository<RoleResource, Long> {

    /**
     * 查询某角色已绑定的全部资源。
     *
     * @param roleId 角色 ID
     * @return 该角色的资源绑定列表
     */
    List<RoleResource> findByRoleId(Long roleId);

    /**
     * 批量查询多个角色的资源绑定，避免循环单查。
     *
     * @param roleIds 角色 ID 列表
     * @return 命中的绑定关系列表
     */
    List<RoleResource> findByRoleIdIn(List<Long> roleIds);

    /**
     * 删除某角色的全部资源绑定（重新分配权限前的清库动作）。
     *
     * <p>同样必须走 {@code @Modifying} 批量 DELETE：派生删除只是把实体逐个
     * {@code em.remove()} 排队，而 Hibernate flush 顺序是「insert → update → delete」，
     * 会使 {@code RoleService.assignResources} 里紧随其后的 INSERT 先于 DELETE 执行，
     * 撞上唯一键冲突。批量 DELETE 在调用时立即下发 SQL，保证先删后插。
     *
     * @param roleId 角色 ID
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RoleResource rr where rr.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") Long roleId);
}

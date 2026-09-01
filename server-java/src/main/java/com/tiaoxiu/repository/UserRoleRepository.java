package com.tiaoxiu.repository;

import com.tiaoxiu.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户与角色绑定关系的数据访问接口：操作表 {@code user_roles}。
 *
 * <p>提供的查询能力：按用户查其全部角色、按角色查其下全部用户、
 * 按用户或按角色删除绑定（「分配角色」时先清空再重写）、判断某用户是否已拥有某角色。
 */
@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    /**
     * 查询某用户被授予的全部角色。
     *
     * @param userId 用户 ID
     * @return 该用户的角色绑定列表
     */
    List<UserRole> findByUserId(Long userId);

    /**
     * 查询拥有某角色的全部用户绑定关系（用于判断该角色是否还有人使用）。
     *
     * @param roleId 角色 ID
     * @return 命中该角色的绑定关系列表
     */
    List<UserRole> findByRoleId(Long roleId);

    /**
     * 删除某用户的全部角色绑定（重新分配角色前的清库动作）。
     *
     * <p>必须用 {@code @Modifying} 的批量 DELETE，不能用 Spring Data 的派生删除：
     * 派生删除会把实体逐个 {@code em.remove()}，删除语句只是排队进持久化上下文，
     * 而 Hibernate 的 flush 顺序是「insert → update → delete」，
     * 于是 {@code assignRoles} 里紧随其后的 INSERT 会排在 DELETE 之前执行，
     * 直接撞上 user_roles 的 (user_id, role_id) 唯一键，报
     * {@code Duplicate entry '1-1' for key 'user_roles.UK...'}。
     * 批量 DELETE 会在调用时立刻下发 SQL，从而保证先删后插。
     * {@code flushAutomatically / clearAutomatically} 用于让后续查询看到最新结果。
     *
     * @param userId 用户 ID
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /**
     * 删除某角色与所有用户的绑定关系（删除角色前的清库动作）。
     *
     * <p>同样使用批量 DELETE，理由同 {@link #deleteByUserId(Long)}。
     *
     * @param roleId 角色 ID
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserRole ur where ur.roleId = :roleId")
    void deleteByRoleId(@Param("roleId") Long roleId);

    /**
     * 判断某用户是否已被授予某角色。
     *
     * @param userId 用户 ID
     * @param roleId 角色 ID
     * @return 已绑定返回 true
     */
    boolean existsByUserIdAndRoleId(Long userId, Long roleId);
}

package com.tiaoxiu.repository;

import com.tiaoxiu.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户的数据访问接口：操作表 {@code users}。
 *
 * <p>提供的查询能力：按登录账号精确查（登录鉴权）、判断账号是否已存在（新增用户查重）、
 * 按状态取反查（排除已删除用户）、按部门 + 状态查（部门维度的统计与导出）。
 *
 * <p>注意：由于删除是逻辑删除（status = DELETED），业务查询必须显式排除 DELETED，
 * 不能直接用 {@code findAll()}。
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 按登录账号查询用户（登录、账号查重时调用）。
     *
     * @param username 登录账号
     * @return 命中则返回用户；账号不存在返回 {@link Optional#empty()}
     */
    Optional<User> findByUsername(String username);

    /**
     * 判断登录账号是否已存在。
     *
     * @param username 登录账号
     * @return 已存在返回 true
     */
    boolean existsByUsername(String username);

    /**
     * 查询「状态不等于指定值」的用户，典型用法是排除已逻辑删除的账号。
     *
     * @param status 要排除的状态，通常传 User.STATUS_DELETED
     * @return 符合条件的用户列表
     */
    List<User> findByStatusNot(String status);

    /**
     * 查询指定部门下指定状态的用户。
     *
     * @param department 部门名称
     * @param status     用户状态，如 User.STATUS_ACTIVE
     * @return 符合条件的用户列表
     */
    List<User> findByDepartmentAndStatus(String department, String status);

    /**
     * 查询系统内置账号（builtin 标记为 true）。
     *
     * @return 命中则返回用户；不存在返回 {@link Optional#empty()}
     */
    Optional<User> findByBuiltinTrue();
}

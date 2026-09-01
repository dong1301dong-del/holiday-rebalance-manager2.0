package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.RoleDto;
import com.tiaoxiu.entity.Role;
import com.tiaoxiu.entity.RoleResource;
import com.tiaoxiu.repository.RoleRepository;
import com.tiaoxiu.repository.RoleResourceRepository;
import com.tiaoxiu.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 角色管理：角色与资源的绑定（权限不直接绑定用户，而绑定角色）。 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final RoleResourceRepository roleResourceRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * 构造器注入。
     *
     * @param roleRepository         角色仓储
     * @param roleResourceRepository 角色资源绑定仓储
     * @param userRoleRepository     用户角色绑定仓储（删除角色时需一并清理）
     */
    public RoleService(RoleRepository roleRepository, RoleResourceRepository roleResourceRepository,
                      UserRoleRepository userRoleRepository) {
        this.roleRepository = roleRepository;
        this.roleResourceRepository = roleResourceRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * 查询全部角色。
     *
     * @return 角色列表
     */
    public List<Role> list() {
        return roleRepository.findAll();
    }

    /**
     * 新增角色（新建角色一律为非内置，可被删除；状态缺省为 ACTIVE）。
     *
     * @param req 新增请求
     * @return 保存后的角色
     * @throws BizException 角色编码已存在时抛出
     */
    @Transactional
    public Role create(RoleDto.CreateRequest req) {
        if (roleRepository.existsByCode(req.getCode())) throw new BizException("角色编码已存在");
        Role r = new Role();
        r.setCode(req.getCode());
        r.setName(req.getName());
        r.setDescription(req.getDescription());
        r.setStatus(StringUtils.hasText(req.getStatus()) ? req.getStatus() : "ACTIVE");
        r.setBuiltin(false);
        return roleRepository.save(r);
    }

    /**
     * 修改角色：仅覆盖传入的非空字段；角色编码不允许修改。
     *
     * @param id  角色 ID
     * @param req 修改请求
     * @return 保存后的角色
     * @throws BizException 角色不存在时抛出
     */
    @Transactional
    public Role update(Long id, RoleDto.UpdateRequest req) {
        Role r = roleRepository.findById(id).orElseThrow(() -> new BizException("角色不存在"));
        if (StringUtils.hasText(req.getName())) r.setName(req.getName());
        if (StringUtils.hasText(req.getDescription())) r.setDescription(req.getDescription());
        if (StringUtils.hasText(req.getStatus())) r.setStatus(req.getStatus());
        return roleRepository.save(r);
    }

    /**
     * 删除角色：内置角色禁止删除；删除前先清理该角色的资源绑定与用户绑定，避免留下孤儿数据。
     *
     * @param id 角色 ID
     * @throws BizException 角色不存在，或该角色为内置角色时抛出
     */
    @Transactional
    public void delete(Long id) {
        Role r = roleRepository.findById(id).orElseThrow(() -> new BizException("角色不存在"));
        // 内置角色（ADMIN/CLERK/EMPLOYEE）被系统逻辑依赖，删掉会导致系统不可用
        if (Boolean.TRUE.equals(r.getBuiltin())) throw new BizException("内置角色不可删除");
        roleResourceRepository.deleteByRoleId(id);
        userRoleRepository.deleteByRoleId(id);
        roleRepository.delete(r);
    }

    /**
     * 为角色分配资源（菜单 / 按钮 / 接口权限）：先清空原有绑定，再按传入 ID 批量重写。
     *
     * @param id          角色 ID
     * @param resourceIds 资源 ID 列表；传空列表表示清空该角色全部权限
     * @throws BizException 角色不存在时抛出
     */
    @Transactional
    public void assignResources(Long id, List<Long> resourceIds) {
        if (!roleRepository.existsById(id)) throw new BizException("角色不存在");
        // 全量覆盖：删除旧绑定再写新绑定，避免逐条 diff 遗漏
        roleResourceRepository.deleteByRoleId(id);
        List<RoleResource> list = new ArrayList<>();
        for (Long rid : resourceIds) {
            RoleResource rr = new RoleResource();
            rr.setRoleId(id);
            rr.setResourceId(rid);
            list.add(rr);
        }
        roleResourceRepository.saveAll(list);
    }

    /**
     * 查询某角色已绑定的资源 ID 列表。
     *
     * @param id 角色 ID
     * @return 资源 ID 列表；无权限时为空列表
     */
    public List<Long> resourceIdsOf(Long id) {
        List<Long> ids = new ArrayList<>();
        roleResourceRepository.findByRoleId(id).forEach(rr -> ids.add(rr.getResourceId()));
        return ids;
    }
}

package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.dto.AuthDto;
import com.tiaoxiu.dto.ResourceDto;
import com.tiaoxiu.entity.Resource;
import com.tiaoxiu.entity.RoleResource;
import com.tiaoxiu.repository.ResourceRepository;
import com.tiaoxiu.repository.RoleRepository;
import com.tiaoxiu.repository.RoleResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 资源（菜单/按钮/接口）与动态菜单树。
 * 权限不直接绑定用户，而绑定角色；登录后按角色聚合资源，动态生成菜单与权限集合。
 */
@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final RoleRepository roleRepository;
    private final RoleResourceRepository roleResourceRepository;

    /**
     * 构造器注入。
     *
     * @param resourceRepository     资源仓储
     * @param roleRepository         角色仓储（按编码反查角色 ID）
     * @param roleResourceRepository 角色资源绑定仓储
     */
    public ResourceService(ResourceRepository resourceRepository, RoleRepository roleRepository,
                           RoleResourceRepository roleResourceRepository) {
        this.resourceRepository = resourceRepository;
        this.roleRepository = roleRepository;
        this.roleResourceRepository = roleResourceRepository;
    }

    /**
     * 根据角色编码集合，构建该用户可见的菜单树与拥有的权限编码集合。
     *
     * <p>权限取「所有角色资源的并集」；只有启用状态的资源会生效。
     * 权限编码集合包含菜单与按钮，用于前端控制按钮显隐（后端另有接口级校验）。
     *
     * @param roleCodes 角色编码列表
     * @return 菜单树与权限编码集合
     */
    public MenuResult buildMenu(List<String> roleCodes) {
        List<Long> roleIds = roleRepository.findByCodeIn(roleCodes).stream().map(r -> r.getId()).collect(Collectors.toList());
        Set<Long> resourceIds = new HashSet<>();
        roleResourceRepository.findByRoleIdIn(roleIds).forEach(rr -> resourceIds.add(rr.getResourceId()));

        List<Resource> resources = resourceRepository.findAllById(resourceIds).stream()
                .filter(r -> Resource.STATUS_ACTIVE.equals(r.getStatus()))
                .collect(Collectors.toList());

        List<String> permissions = resources.stream().map(Resource::getCode).collect(Collectors.toList());

        List<AuthDto.MenuNode> menus = buildTree(resources);
        return new MenuResult(menus, permissions);
    }

    /** 菜单构建结果：菜单树 + 权限编码集合 */
    public static class MenuResult {
        /** 该用户可见的菜单树 */
        public final List<AuthDto.MenuNode> menus;
        /** 该用户拥有的全部权限编码（含菜单与按钮） */
        public final List<String> permissions;

        /**
         * 构造菜单构建结果。
         *
         * @param menus       菜单树
         * @param permissions 权限编码集合
         */
        public MenuResult(List<AuthDto.MenuNode> menus, List<String> permissions) {
            this.menus = menus; this.permissions = permissions;
        }
    }

    /**
     * 把扁平的资源列表组装成菜单树。
     *
     * <p>只取 MENU 类型的资源（按钮不出现在菜单里）；父节点不在可见集合内的节点会被提升为根节点，
     * 避免出现「有子菜单却看不到父菜单」导致的菜单丢失。
     *
     * @param resources 该用户可见的资源列表
     * @return 菜单树根节点列表，按排序号升序
     */
    private List<AuthDto.MenuNode> buildTree(List<Resource> resources) {
        Map<Long, AuthDto.MenuNode> nodeMap = new LinkedHashMap<>();
        for (Resource r : resources) {
            if (!Resource.TYPE_MENU.equals(r.getType())) continue;
            nodeMap.put(r.getId(), toNode(r));
        }
        List<AuthDto.MenuNode> roots = new ArrayList<>();
        for (Resource r : resources) {
            if (!Resource.TYPE_MENU.equals(r.getType())) continue;
            AuthDto.MenuNode node = nodeMap.get(r.getId());
            if (r.getParentId() != null && nodeMap.containsKey(r.getParentId())) {
                nodeMap.get(r.getParentId()).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        sortRecursive(roots);
        return roots;
    }

    /**
     * 递归按排序号升序排列菜单节点（sort 为 null 时按 0 处理）。
     *
     * @param nodes 当前层级的节点列表
     */
    private void sortRecursive(List<AuthDto.MenuNode> nodes) {
        nodes.sort(Comparator.comparingInt(n -> n.getSort() == null ? 0 : n.getSort()));
        for (AuthDto.MenuNode n : nodes) sortRecursive(n.getChildren());
    }

    /**
     * 资源实体转菜单节点（children 初始化为空列表，便于上层直接 add）。
     *
     * @param r 资源实体
     * @return 菜单节点
     */
    private AuthDto.MenuNode toNode(Resource r) {
        AuthDto.MenuNode n = new AuthDto.MenuNode();
        n.setId(r.getId());
        n.setParentId(r.getParentId());
        n.setName(r.getName());
        n.setPath(r.getPath());
        n.setComponent(r.getComponent());
        n.setIcon(r.getIcon());
        n.setSort(r.getSort());
        n.setCode(r.getCode());
        n.setChildren(new ArrayList<>());
        return n;
    }

    /**
     * 查询全部启用中的资源，按排序号升序（用于角色分配权限时的树形展示）。
     *
     * @return 启用中的资源列表
     */
    public List<Resource> listAll() {
        return resourceRepository.findByStatusOrderBySortAsc(Resource.STATUS_ACTIVE);
    }

    /**
     * 查询全部资源（含已停用的），供管理员维护页面使用。
     *
     * @return 全部资源列表
     */
    public List<Resource> listAllIncludingDisabled() {
        return resourceRepository.findAll();
    }

    /**
     * 新增资源。
     *
     * @param req 新增请求
     * @return 保存后的资源
     * @throws BizException 权限编码已存在时抛出
     */
    @Transactional
    public Resource create(ResourceDto.CreateRequest req) {
        if (resourceRepository.findByCode(req.getCode()).isPresent()) throw new BizException("权限编码已存在");
        Resource r = new Resource();
        apply(r, req);
        return resourceRepository.save(r);
    }

    /**
     * 修改资源：仅覆盖传入的非空字段；权限编码（code）不允许修改。
     *
     * @param id  资源 ID
     * @param req 修改请求
     * @return 保存后的资源
     * @throws BizException 资源不存在时抛出
     */
    @Transactional
    public Resource update(Long id, ResourceDto.UpdateRequest req) {
        Resource r = resourceRepository.findById(id).orElseThrow(() -> new BizException("资源不存在"));
        if (req.getParentId() != null) r.setParentId(req.getParentId());
        if (StringUtils.hasText(req.getType())) r.setType(req.getType());
        if (StringUtils.hasText(req.getName())) r.setName(req.getName());
        if (req.getPath() != null) r.setPath(req.getPath());
        if (req.getComponent() != null) r.setComponent(req.getComponent());
        if (req.getIcon() != null) r.setIcon(req.getIcon());
        if (req.getSort() != null) r.setSort(req.getSort());
        if (req.getPermission() != null) r.setPermission(req.getPermission());
        if (StringUtils.hasText(req.getStatus())) r.setStatus(req.getStatus());
        return resourceRepository.save(r);
    }

    /**
     * 把新增请求的属性写入资源实体，并补齐排序号与启用状态。
     *
     * @param r   目标资源实体
     * @param req 新增请求
     */
    private void apply(Resource r, ResourceDto.CreateRequest req) {
        r.setParentId(req.getParentId());
        r.setType(req.getType());
        r.setCode(req.getCode());
        r.setName(req.getName());
        r.setPath(req.getPath());
        r.setComponent(req.getComponent());
        r.setIcon(req.getIcon());
        r.setSort(req.getSort() == null ? 0 : req.getSort());
        r.setPermission(req.getPermission());
        r.setStatus(Resource.STATUS_ACTIVE);
    }
}

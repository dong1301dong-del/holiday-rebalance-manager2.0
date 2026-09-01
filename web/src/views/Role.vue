<script setup>
/**
 * 角色权限（路由 /system/role，权限码 role:view；新增 role:add、编辑 role:edit、分配 role:assign）
 * 职责：维护角色（增删改），并为角色勾选菜单/按钮/接口资源，实现 RBAC 动态鉴权。
 * 管理员隐藏页面：不在动态菜单里，需直接输地址访问。
 */
import { ref, onMounted, computed } from 'vue';
import { roleApi, resourceApi } from '../api';
import { ok, err } from '../toast';

const list = ref([]);      // 角色列表
const resources = ref([]); // 全部资源（用于权限树）
const loading = ref(true);

const TYPE_LABEL = { MENU: '菜单', BUTTON: '按钮', API: '接口' };
const TYPE_TAG = { MENU: 'tag-pink', BUTTON: 'tag-orange', API: 'tag-blue' };

/** 并行加载角色与资源，减少一次往返等待 */
async function load() {
  loading.value = true;
  try {
    const [r1, r2] = await Promise.all([roleApi.list(), resourceApi.list()]);
    list.value = r1.data || [];
    resources.value = r2.data || [];
  } catch (e) { err(e.message); }
  finally { loading.value = false; }
}
onMounted(load);

// 资源树：parentId 为空为根（两级，与 Resource.vue 口径一致）
const tree = computed(() => {
  const all = resources.value;
  const roots = all.filter((r) => !r.parentId);
  const kids = (pid) => all.filter((r) => r.parentId === pid);
  return roots.map((r) => ({ ...r, children: kids(r.id) }));
});

// ---------- 新增 / 编辑 ----------
const showForm = ref(false);
const isEdit = ref(false); // true=编辑，false=新增
const form = ref({ id: null, code: '', name: '', description: '', status: 'ACTIVE' });

/** 打开新增弹窗：code 可填；编辑态下 code 不可改（后端不接受改编码） */
function openCreate() {
  isEdit.value = false;
  form.value = { id: null, code: '', name: '', description: '', status: 'ACTIVE' };
  showForm.value = true;
}
function openEdit(r) {
  isEdit.value = true;
  form.value = { id: r.id, code: r.code, name: r.name, description: r.description || '', status: r.status };
  showForm.value = true;
}

/**
 * 提交角色表单
 * 编辑时只提交 name/description/status（code 不变）；新增时才带 code
 */
async function submitForm() {
  const f = form.value;
  if (!f.code || !f.name) return err('请填写角色编码与名称');
  try {
    if (isEdit.value) await roleApi.update(f.id, { name: f.name, description: f.description, status: f.status });
    else await roleApi.create({ code: f.code, name: f.name, description: f.description, status: f.status });
    ok(isEdit.value ? '已保存' : '角色已创建');
    showForm.value = false;
    load();
  } catch (e) { err(e.message); }
}

// ---------- 分配权限 ----------
const showAssign = ref(false);
const assignTarget = ref(null);   // 当前被分配权限的角色
const checked = ref(new Set());   // 已勾选的资源 id 集合
const loadingRes = ref(false);

/** 打开分配弹窗：先置空再拉该角色已持有的资源 id，避免闪现上一角色的勾选 */
async function openAssign(r) {
  assignTarget.value = r;
  checked.value = new Set();
  showAssign.value = true;
  loadingRes.value = true;
  try {
    const res = await roleApi.resources(r.id);
    checked.value = new Set(res.data || []);
  } catch (e) { err(e.message); }
  finally { loadingRes.value = false; }
}

/** 勾选/取消：复制一份新 Set 再赋值，保证 Vue 能感知到引用变化触发更新 */
function toggle(id, on) {
  const s = new Set(checked.value);
  if (on) s.add(id); else s.delete(id);
  checked.value = s;
}

/** 保存权限：POST /api/roles/{id}/resources，全量覆盖提交 */
async function submitAssign() {
  try {
    await roleApi.assignResources(assignTarget.value.id, [...checked.value]);
    ok('权限已保存');
    showAssign.value = false;
  } catch (e) { err(e.message); }
}

// ---------- 删除 ----------
/** 删除角色：内置角色（builtin）在前端先拦截，避免请求后端报错 */
async function remove(r) {
  if (r.builtin) return err('内置角色不可删除');
  if (!confirm(`确定删除角色「${r.name}」吗？`)) return;
  try { await roleApi.remove(r.id); ok('已删除'); load(); } catch (e) { err(e.message); }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">角色权限</div>
        <div class="page-sub">为角色分配菜单、按钮与接口权限；角色再绑定到用户，实现 RBAC 动态鉴权</div>
      </div>
      <button v-permission="'role:add'" class="btn btn-primary" @click="openCreate">＋ 新增角色</button>
    </div>

    <div class="card">
      <div v-if="loading" style="padding:40px"><div class="spinner"></div></div>
      <div v-else-if="list.length === 0" class="empty"><div class="emoji">🔑</div>暂无角色</div>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr><th>角色名称</th><th>编码</th><th>描述</th><th>状态</th><th>类型</th><th style="width:220px">操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id">
              <td style="font-weight:600">{{ r.name }}</td>
              <td style="font-family:monospace">{{ r.code }}</td>
              <td style="color:var(--text-sub)">{{ r.description || '-' }}</td>
              <td><span class="tag" :class="r.status === 'ACTIVE' ? 'tag-green' : 'tag-gray'">{{ r.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
              <td>{{ r.builtin ? '内置' : '自定义' }}</td>
              <td style="white-space:nowrap">
                <button v-permission="'role:assign'" class="btn-text btn-sm" @click="openAssign(r)">分配权限</button>
                <button v-permission="'role:edit'" class="btn-text btn-sm" @click="openEdit(r)">编辑</button>
                <button v-permission="'role:edit'" v-if="!r.builtin" class="btn-text danger btn-sm" @click="remove(r)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新增 / 编辑 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width:min(480px,94vw)">
        <div class="modal-header">{{ isEdit ? '编辑角色' : '新增角色' }}</div>
        <div class="modal-body">
          <div class="form-item"><label class="form-label">角色编码</label>
            <input v-model="form.code" class="input" :disabled="isEdit || form.builtin" placeholder="如 CLERK" />
          </div>
          <div class="form-item"><label class="form-label">角色名称</label>
            <input v-model="form.name" class="input" placeholder="如 录入员" />
          </div>
          <div class="form-item"><label class="form-label">描述</label>
            <input v-model="form.description" class="input" placeholder="角色职责说明" />
          </div>
          <div class="form-item" style="margin-bottom:0"><label class="form-label">状态</label>
            <select v-model="form.status" class="select">
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
            </select>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showForm = false">取消</button>
          <button class="btn btn-primary" @click="submitForm">保存</button>
        </div>
      </div>
    </div>

    <!-- 分配权限 -->
    <div v-if="showAssign" class="modal-mask" @click.self="showAssign = false">
      <div class="modal" style="width:min(620px,94vw)">
        <div class="modal-header">分配权限 - {{ assignTarget?.name }}</div>
        <div class="modal-body">
          <div v-if="loadingRes" style="padding:20px"><div class="spinner"></div></div>
          <div v-else class="perm-tree">
            <div v-for="g in tree" :key="g.id" class="perm-group">
              <label class="perm-node root">
                <input type="checkbox" :checked="checked.has(g.id)" @change="toggle(g.id, $event.target.checked)" />
                <span class="perm-name">{{ g.name }}</span>
                <span class="tag" :class="TYPE_TAG[g.type]">{{ TYPE_LABEL[g.type] }}</span>
              </label>
              <div class="perm-children" v-if="g.children.length">
                <label v-for="c in g.children" :key="c.id" class="perm-node child">
                  <input type="checkbox" :checked="checked.has(c.id)" @change="toggle(c.id, $event.target.checked)" />
                  <span class="perm-name">{{ c.name }}</span>
                  <span class="tag" :class="TYPE_TAG[c.type]">{{ TYPE_LABEL[c.type] }}</span>
                  <span class="perm-code">{{ c.code }}</span>
                </label>
              </div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showAssign = false">取消</button>
          <button class="btn btn-primary" @click="submitAssign">保存权限</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.perm-tree { max-height: 56vh; overflow-y: auto; padding-right: 6px; }
.perm-group { margin-bottom: 10px; }
.perm-node { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px; cursor: pointer; }
.perm-node.root { background: var(--primary-bg); font-weight: 600; }
.perm-children { padding-left: 26px; margin-top: 4px; }
.perm-children .perm-node:hover { background: #fff0f6; }
.perm-name { flex: 1; }
.perm-code { font-size: 11px; color: var(--text-light); font-family: monospace; }
</style>

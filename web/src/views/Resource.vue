<script setup>
/**
 * 资源管理（路由 /system/resource，权限码 resource:view；新增需 resource:add，编辑需 resource:edit）
 * 职责：维护系统的菜单 / 按钮 / 接口资源树。资源分配给角色后，前端据此渲染菜单与控制按钮显隐。
 * 管理员隐藏页面：不在动态菜单里，需直接输地址访问。
 */
import { ref, onMounted, computed } from 'vue';
import { resourceApi } from '../api';
import { ok, err } from '../toast';

const TYPE_LABEL = { MENU: '菜单', BUTTON: '按钮', API: '接口' };
const TYPE_TAG = { MENU: 'tag-pink', BUTTON: 'tag-orange', API: 'tag-blue' };

const list = ref([]);
const loading = ref(true);

/** 加载资源列表：GET /api/resources */
async function load() {
  loading.value = true;
  try {
    const res = await resourceApi.list();
    list.value = res.data || [];
  } catch (e) { err(e.message); }
  finally { loading.value = false; }
}
onMounted(load);

/** 按 sort 升序排序（sort 可能缺失，用 ?? 0 兜底，避免 NaN 打乱顺序） */
const sorted = (arr) => [...arr].sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0));

// 组装成两级树：parentId 为空的是根，其余挂到对应根下（只支持两级，同 MainLayout 的菜单渲染口径）
const tree = computed(() => {
  const all = list.value;
  const roots = sorted(all.filter((r) => !r.parentId));
  const kids = (pid) => sorted(all.filter((r) => r.parentId === pid));
  return roots.map((r) => ({ ...r, children: kids(r.id) }));
});
// 可作为父级的只能是菜单（按钮/接口不应再有子级）
const menuOptions = computed(() => list.value.filter((r) => r.type === 'MENU'));

// ---------- 新增 / 编辑 ----------
const showForm = ref(false);
const isEdit = ref(false); // true=编辑，false=新增
const form = ref({ id: null, parentId: '', type: 'MENU', code: '', name: '', path: '', component: '', icon: '', sort: 0, permission: '', status: 'ACTIVE' });

/** 重置表单为新增态默认值 */
function resetForm() {
  form.value = { id: null, parentId: '', type: 'MENU', code: '', name: '', path: '', component: '', icon: '', sort: 0, permission: '', status: 'ACTIVE' };
}
function openCreate() { isEdit.value = false; resetForm(); showForm.value = true; }

/** 打开编辑弹窗：回填整行字段，空值统一转成空串/0，避免受控组件收到 null */
function openEdit(r) {
  isEdit.value = true;
  form.value = { id: r.id, parentId: r.parentId ?? '', type: r.type, code: r.code, name: r.name, path: r.path || '', component: r.component || '', icon: r.icon || '', sort: r.sort ?? 0, permission: r.permission || '', status: r.status };
  showForm.value = true;
}

/**
 * 提交表单：新增走 POST /api/resources，编辑走 PUT /api/resources/{id}
 * 空字符串统一转 null，避免后端收到 '' 导致唯一键或路径判断异常
 */
async function submitForm() {
  const f = form.value;
  if (!f.code || !f.name) return err('请填写编码与名称');
  const body = {
    parentId: f.parentId ? Number(f.parentId) : null,
    type: f.type, code: f.code, name: f.name,
    path: f.path || null, component: f.component || null, icon: f.icon || null,
    sort: Number(f.sort) || 0, permission: f.permission || null, status: f.status,
  };
  try {
    if (isEdit.value) await resourceApi.update(f.id, body);
    else await resourceApi.create(body);
    ok(isEdit.value ? '已保存' : '资源已创建');
    showForm.value = false;
    load();
  } catch (e) { err(e.message); }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">资源管理</div>
        <div class="page-sub">系统菜单、按钮与接口资源；资源分配给角色后，前端动态渲染菜单、后端接口鉴权才会放行</div>
      </div>
      <button v-permission="'resource:add'" class="btn btn-primary" @click="openCreate">＋ 新增资源</button>
    </div>

    <div class="card">
      <div v-if="loading" style="padding:40px"><div class="spinner"></div></div>
      <div v-else-if="tree.length === 0" class="empty"><div class="emoji">🧩</div>暂无资源</div>
      <div v-else class="table-wrap">
        <table class="table">
          <thead>
            <tr><th>名称</th><th>类型</th><th>编码 / 权限</th><th>路径 / 组件</th><th class="num">排序</th><th>状态</th><th style="width:120px">操作</th></tr>
          </thead>
          <tbody>
            <template v-for="g in tree" :key="g.id">
              <tr>
                <td style="font-weight:700">{{ g.name }}</td>
                <td><span class="tag" :class="TYPE_TAG[g.type]">{{ TYPE_LABEL[g.type] }}</span></td>
                <td><code class="code">{{ g.code }}</code><div class="muted">{{ g.permission || '-' }}</div></td>
                <td class="muted">{{ g.path || '-' }} <span v-if="g.component">· {{ g.component }}</span></td>
                <td class="num">{{ g.sort }}</td>
                <td><span class="tag" :class="g.status === 'ACTIVE' ? 'tag-green' : 'tag-gray'">{{ g.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
                <td>
                  <button v-permission="'resource:edit'" class="btn-text btn-sm" @click="openEdit(g)">编辑</button>
                </td>
              </tr>
              <tr v-for="c in g.children" :key="c.id" class="child-row">
                <td style="padding-left:34px;color:var(--text-sub)">{{ c.name }}</td>
                <td><span class="tag" :class="TYPE_TAG[c.type]">{{ TYPE_LABEL[c.type] }}</span></td>
                <td><code class="code">{{ c.code }}</code><div class="muted">{{ c.permission || '-' }}</div></td>
                <td class="muted">{{ c.path || '-' }} <span v-if="c.component">· {{ c.component }}</span></td>
                <td class="num">{{ c.sort }}</td>
                <td><span class="tag" :class="c.status === 'ACTIVE' ? 'tag-green' : 'tag-gray'">{{ c.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
                <td>
                  <button v-permission="'resource:edit'" class="btn-text btn-sm" @click="openEdit(c)">编辑</button>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 新增 / 编辑 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width:min(560px,94vw)">
        <div class="modal-header">{{ isEdit ? '编辑资源' : '新增资源' }}</div>
        <div class="modal-body">
          <div class="form-row">
            <div class="form-item"><label class="form-label">类型</label>
              <select v-model="form.type" class="select"><option value="MENU">菜单</option><option value="BUTTON">按钮</option><option value="API">接口</option></select>
            </div>
            <div class="form-item"><label class="form-label">父级</label>
              <select v-model="form.parentId" class="select">
                <option value="">（顶级）</option>
                <option v-for="m in menuOptions" :key="m.id" :value="m.id">{{ m.name }}</option>
              </select>
            </div>
          </div>
          <div class="form-row">
            <div class="form-item"><label class="form-label">编码 *</label><input v-model="form.code" class="input" placeholder="全局唯一，如 overtime:add" /></div>
            <div class="form-item"><label class="form-label">名称 *</label><input v-model="form.name" class="input" placeholder="显示名称" /></div>
          </div>
          <div class="form-row">
            <div class="form-item"><label class="form-label">路径</label><input v-model="form.path" class="input" placeholder="路由 path，如 /system/user" /></div>
            <div class="form-item"><label class="form-label">组件</label><input v-model="form.component" class="input" placeholder="如 views/SystemUser.vue" /></div>
          </div>
          <div class="form-row">
            <div class="form-item"><label class="form-label">图标</label><input v-model="form.icon" class="input" placeholder="图标名" /></div>
            <div class="form-item"><label class="form-label">排序</label><input v-model.number="form.sort" type="number" class="input" /></div>
          </div>
          <div class="form-row">
            <div class="form-item"><label class="form-label">权限标识</label><input v-model="form.permission" class="input" placeholder="与编码通常相同" /></div>
            <div class="form-item" v-if="isEdit"><label class="form-label">状态</label>
              <select v-model="form.status" class="select"><option value="ACTIVE">启用</option><option value="DISABLED">停用</option></select>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showForm = false">取消</button>
          <button class="btn btn-primary" @click="submitForm">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.code { font-family: monospace; font-size: 12.5px; color: var(--primary); background: var(--primary-bg); padding: 2px 6px; border-radius: 4px; }
.muted { color: var(--text-light); font-size: 12px; margin-top: 2px; }
.child-row td { background: #fffafc; }
</style>

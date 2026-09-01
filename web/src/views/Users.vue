<script setup>
/**
 * 用户管理（路由 /user，权限码 user:view）
 * 职责：维护成员账号、部门、职位与角色；支持新增、编辑、分配角色、重置密码、冻结/解冻、删除。
 * 按钮权限：user:add（新增）、user:edit（编辑/角色）、user:resetpwd（改密）、user:freeze（冻结）、user:delete（删除）。
 * 筛选为纯前端过滤（用户量不大，一次拉全后在本地按姓名/账号、部门、角色过滤）。
 */
import { ref, reactive, onMounted, computed } from 'vue';
import { userApi, roleApi } from '../api';
import { ok, err } from '../toast';
import { ElMessage, ElMessageBox } from 'element-plus';

const ROLE_LABEL = { ADMIN: '管理员', CLERK: '录入员', EMPLOYEE: '普通员工' };
/** 强密码规则：8-20 位，含小写、大写、数字与非字母数字字符 */
const STRONG_RE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,20}$/;

const list = ref([]);   // 全部用户
const roles = ref([]);  // 角色下拉选项
const loading = ref(true);

const filters = reactive({ name: '', department: '', role: '' });

/** 部门下拉选项：从用户列表去重（用 Set 去重，空部门不进下拉） */
const departments = computed(() => {
  const s = new Set();
  list.value.forEach((u) => u.department && s.add(u.department));
  return [...s];
});

/** 前端过滤：姓名支持姓名或账号模糊匹配，部门为精确匹配，角色为包含匹配 */
const filtered = computed(() =>
  list.value.filter((u) => {
    const okName = !filters.name || (u.name || '').includes(filters.name) || (u.username || '').includes(filters.name);
    const okDept = !filters.department || (u.department || '') === filters.department;
    const okRole = !filters.role || (u.roles || []).includes(filters.role);
    return okName && okDept && okRole;
  })
);

/** 加载角色选项：GET /api/roles；失败仅降级为空下拉 */
async function loadRoles() {
  try {
    const res = await roleApi.list();
    roles.value = res.data || [];
  } catch { /* 忽略 */ }
}

/** 加载用户列表：GET /api/users */
async function load() {
  loading.value = true;
  try {
    const res = await userApi.list();
    list.value = res.data || [];
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}

/** 重置筛选条件（不重新请求，filtered 会自动重算） */
function resetFilter() {
  filters.name = '';
  filters.department = '';
  filters.role = '';
}

onMounted(() => {
  load();
  loadRoles();
});

/** 角色码数组转中文文案，多角色用 ' / ' 连接 */
function roleTags(rs) {
  if (!rs || !rs.length) return '未分配';
  return rs.map((r) => ROLE_LABEL[r] || r).join(' / ');
}

// ---------- 新增 / 编辑 ----------
const showForm = ref(false);
const isEdit = ref(false); // true=编辑，false=新增
const form = ref({});

/** 打开新增弹窗：默认给 EMPLOYEE 角色，密码留空由后端下发默认密码 */
function openCreate() {
  isEdit.value = false;
  form.value = { id: null, username: '', name: '', department: '', position: '', roleCodes: ['EMPLOYEE'], password: '' };
  showForm.value = true;
}

/** 打开编辑弹窗：roleCodes 复制一份，避免直接改到表格行数据 */
function openEdit(u) {
  isEdit.value = true;
  form.value = {
    id: u.id,
    username: u.username,
    name: u.name,
    department: u.department || '',
    position: u.position || '',
    roleCodes: [...(u.roles || [])],
    status: u.status,
  };
  showForm.value = true;
}

/**
 * 提交用户表单
 * 编辑走 PUT /api/users/{id}；新增走 POST /api/users，且仅当填写了密码时才校验强度
 */
async function submitForm() {
  const f = form.value;
  if (!f.username || !f.name || !f.department || !f.position || !f.roleCodes?.length) {
    return err('姓名、账号、部门、职位、角色均为必填项');
  }
  try {
    if (isEdit.value) {
      await userApi.update(f.id, {
        username: f.username,
        name: f.name,
        department: f.department,
        position: f.position,
        roleCodes: f.roleCodes,
        status: f.status,
      });
      ok('已保存');
    } else {
      // 密码可选：留空则由后端使用默认密码 Abc_123456，因此只在有值时校验
      if (f.password && !STRONG_RE.test(f.password)) {
        return err('密码需 8-20 位，含大小写字母、数字与特殊字符');
      }
      await userApi.create({
        username: f.username,
        name: f.name,
        department: f.department,
        position: f.position,
        roleCodes: f.roleCodes,
        password: f.password || undefined,
      });
      ok('用户创建成功');
    }
    showForm.value = false;
    load();
  } catch (e) {
    err(e.message);
  }
}

// ---------- 分配角色 ----------
const showRoles = ref(false);
const roleTarget = ref(null);   // 当前被分配角色的用户
const pickedRoles = ref([]);    // 已选角色码

/** 打开角色弹窗：以该用户现有角色为初始值 */
function openRoles(u) {
  roleTarget.value = u;
  pickedRoles.value = [...(u.roles || [])];
  showRoles.value = true;
}

/** 保存角色：POST /api/users/{id}/roles */
async function submitRoles() {
  if (!pickedRoles.value.length) return err('请至少选择一个角色');
  try {
    await userApi.assignRoles(roleTarget.value.id, pickedRoles.value);
    ok('角色已更新');
    showRoles.value = false;
    load();
  } catch (e) {
    err(e.message);
  }
}

// ---------- 改密 ----------
const showPwd = ref(false);
const pwdTarget = ref(null); // 当前被重置密码的用户
const newPwd = ref('');

function openPwd(u) {
  pwdTarget.value = u;
  newPwd.value = '';
  showPwd.value = true;
}

/** 管理员重置他人密码：POST /api/users/{id}/reset-password，对方下次登录需强制改密 */
async function submitPwd() {
  if (!STRONG_RE.test(newPwd.value)) return err('密码需 8-20 位，含大小写字母、数字与特殊字符');
  try {
    await userApi.resetPassword(pwdTarget.value.id, newPwd.value);
    ok('密码已重置，对方下次登录需改密');
    showPwd.value = false;
    load();
  } catch (e) {
    err(e.message);
  }
}

// ---------- 冻结 / 解冻、删除 ----------
/**
 * 冻结/解冻：先二次确认，再 POST /api/users/{id}/status?status=ACTIVE|FROZEN
 * @param {object} u 用户行
 */
async function toggleStatus(u) {
  const isFrozen = u.status === 'FROZEN';
  const action = isFrozen ? '启用' : '冻结';
  try {
    await ElMessageBox.confirm(`确定${action}「${u.name}」吗？`, `${action}确认`, { type: 'warning' });
  } catch {
    return; // 用户取消
  }
  try {
    await userApi.setStatus(u.id, isFrozen ? 'ACTIVE' : 'FROZEN');
    ok(`已${action}`);
    load();
  } catch (e) {
    err(e.message);
  }
}

/** 删除用户：不可恢复，确认框文案单独强化提示 */
async function remove(u) {
  try {
    await ElMessageBox.confirm(`确定删除用户「${u.name}」吗？该操作不可恢复！`, '删除确认', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
    });
  } catch {
    return;
  }
  try {
    await userApi.remove(u.id);
    ok('已删除');
    load();
  } catch (e) {
    err(e.message);
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">用户管理</div>
        <div class="page-sub">维护成员账号、所属部门、职位与角色</div>
      </div>
      <el-button v-permission="'user:add'" type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>新增用户
      </el-button>
    </div>

    <div class="card">
      <el-form :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="姓名">
          <el-input v-model="filters.name" placeholder="姓名/账号" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="filters.department" placeholder="全部部门" clearable style="width: 150px">
            <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="filters.role" placeholder="全部角色" clearable style="width: 140px">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="录入员" value="CLERK" />
            <el-option label="普通员工" value="EMPLOYEE" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="filtered" border stripe style="width: 100%">
        <el-table-column type="index" label="序号" width="70" align="center" />
        <el-table-column label="姓名 / 账号" min-width="180">
          <template #default="{ row }">
            <div class="name-cell">
              <span class="avatar-dot">{{ (row.name || '?').slice(0, 1) }}</span>
              <div class="name-meta">
                <div class="name">{{ row.name }}</div>
                <div class="account">{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="department" label="部门" min-width="120" />
        <el-table-column prop="position" label="职位" min-width="120" />
        <el-table-column label="角色" min-width="120">
          <template #default="{ row }">
            <el-tag v-for="r in row.roles" :key="r" size="small" effect="light" class="role-tag">
              {{ ROLE_LABEL[r] || r }}
            </el-tag>
            <span v-if="!row.roles || !row.roles.length" class="sub-text">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small" effect="plain">
              {{ row.status === 'ACTIVE' ? '在职' : '冻结' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-tooltip content="编辑" placement="top">
              <el-button v-permission="'user:edit'" link type="primary" :icon="'Edit'" @click="openEdit(row)" />
            </el-tooltip>
            <el-tooltip content="角色" placement="top">
              <el-button v-permission="'user:edit'" link type="primary" :icon="'User'" @click="openRoles(row)" />
            </el-tooltip>
            <el-tooltip content="改密" placement="top">
              <el-button v-permission="'user:resetpwd'" link type="warning" :icon="'Key'" @click="openPwd(row)" />
            </el-tooltip>
            <el-tooltip :content="row.status === 'ACTIVE' ? '冻结' : '启用'" placement="top">
              <el-button
                v-permission="'user:freeze'"
                link
                :type="row.status === 'ACTIVE' ? 'info' : 'success'"
                :icon="row.status === 'ACTIVE' ? 'Lock' : 'Unlock'"
                @click="toggleStatus(row)"
              />
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button v-permission="'user:delete'" link type="danger" :icon="'Delete'" @click="remove(row)" />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-foot">
        <span class="total-text">共计 {{ filtered.length }} 人</span>
      </div>
    </div>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="showForm" :title="isEdit ? '编辑用户' : '新增用户'" width="560px">
      <el-form label-width="80px">
        <el-form-item label="账号" required>
          <el-input v-model="form.username" placeholder="登录账号" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" placeholder="员工姓名" />
        </el-form-item>
        <el-form-item label="部门" required>
          <el-input v-model="form.department" placeholder="如：软件研发部" />
        </el-form-item>
        <el-form-item label="职位" required>
          <el-input v-model="form.position" placeholder="如：前端工程师" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roleCodes" multiple placeholder="请选择角色" style="width: 100%">
            <el-option v-for="r in roles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!isEdit" label="初始密码">
          <el-input v-model="form.password" placeholder="留空则使用默认密码 Abc_123456" />
        </el-form-item>
        <el-form-item v-else label="状态">
          <el-radio-group v-model="form.status">
            <el-radio label="ACTIVE">在职</el-radio>
            <el-radio label="FROZEN">冻结</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" @click="submitForm">{{ isEdit ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色 -->
    <el-dialog v-model="showRoles" :title="`分配角色 - ${roleTarget?.name}`" width="420px">
      <el-select v-model="pickedRoles" multiple placeholder="请选择角色" style="width: 100%">
        <el-option v-for="r in roles" :key="r.code" :label="`${r.name}（${r.code}）`" :value="r.code" />
      </el-select>
      <template #footer>
        <el-button @click="showRoles = false">取消</el-button>
        <el-button type="primary" @click="submitRoles">保存角色</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="showPwd" :title="`重置密码 - ${pwdTarget?.name}`" width="460px">
      <el-form label-width="80px">
        <el-form-item label="新密码">
          <el-input v-model="newPwd" placeholder="8-20位，含大小写/数字/特殊字符" />
          <div class="hint">示例：Abc_123456。重置后该成员下次登录需强制改密。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showPwd = false">取消</el-button>
        <el-button type="primary" @click="submitPwd">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.page-title { font-size: 20px; font-weight: 800; color: #c0356b; }
.page-sub { font-size: 13px; color: #9a7287; margin-top: 4px; }
.card {
  background: #fff; border-radius: 12px; box-shadow: 0 2px 10px rgba(255, 111, 165, 0.1);
  padding: 18px; border: 1px solid #ffe6f1;
}
.search-form { margin-bottom: 4px; }
.name-cell { display: flex; align-items: center; gap: 8px; }
.avatar-dot {
  display: inline-flex; width: 28px; height: 28px; align-items: center; justify-content: center;
  border-radius: 50%; background: #fff0f6; color: #ff6fa5; font-size: 12px; font-weight: 700;
}
.name { font-weight: 600; color: #4a2f3c; line-height: 1.2; }
.account { font-size: 12px; color: #c39ab2; font-family: monospace; }
.role-tag { margin-right: 4px; }
.sub-text { color: #c39ab2; font-size: 12px; }
.table-foot { padding-top: 12px; }
.total-text { font-size: 13px; color: #9a7287; }
.hint { margin-top: 8px; padding: 8px 10px; background: #fff0f6; border-radius: 8px; font-size: 12.5px; color: #9a7287; }
</style>

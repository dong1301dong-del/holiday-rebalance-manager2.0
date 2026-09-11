<script setup>
/**
 * 调休使用记录（路由 /leave，权限码 leave:view）
 * 职责：调休使用记录的查询、批量录入、编辑、作废、导入与导出。
 * 按钮权限：leave:add（录入）、leave:edit（编辑）、leave:void（作废）、leave:import（导入）、leave:export（导出）。
 *
 * 业务要点：
 *  - 作废不做物理删除，仅标记 VOID 并把使用时长退回余额；默认列表隐藏，勾选「显示已作废」才出现并置灰展示。
 *  - 录入时若余额不足，后端返回「余额不足」，前端二次确认后带 allowOverdraft=true 重提，透支会产生预警消息。
 *  - 编辑保存后，后端按新旧时长差额自动调整余额；管理员/录入员还可在编辑弹窗改归属员工，
 *    换人时原员工退回本次时长、新员工扣减本次时长。
 *
 * 员工视角（非 ADMIN / CLERK）：
 *  - 隐藏「姓名」「部门」输入框与「显示已作废」，列表固定只查本人（请求带 userId）。
 */
import { ref, reactive, onMounted, computed } from 'vue';
import { Edit, Delete, Plus, Upload, Download, Search, Refresh, CircleClose } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { leaveApi, userApi, api } from '../api';
import { auth, canManage } from '../store';
import { ok, err } from '../toast';

// ---------------- 列表 ----------------
const loading = ref(false);
const rows = ref([]);
const total = ref(0);
const query = reactive({
  name: '',
  department: '',
  from: '',
  to: '',
  // 是否包含已作废记录（作废不做物理删除，默认隐藏、可勾选查看，已作废行置灰）
  includeVoid: false,
  page: 1,
  size: 20,
});

/**
 * 员工视角：非管理员/录入员时只展示本人数据
 * 后端对 EMPLOYEE 也会强制收敛为本人，这里同步收起无关筛选项，避免误导
 */
const selfOnly = computed(() => !canManage());

/** 加载列表：GET /api/leave；空条件转 undefined 避免传空串 */
async function load() {
  loading.value = true;
  try {
    const res = await leaveApi.list({
      // 员工视角固定带本人 userId，无需也不允许查他人
      userId: selfOnly.value ? auth.user?.id : undefined,
      name: query.name || undefined,
      department: query.department || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      includeVoid: query.includeVoid,
      page: query.page,
      size: query.size,
    });
    rows.value = res.data || [];
    total.value = res.total || 0;
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}

/** 点击「查询」或「显示已作废」勾选框时触发；条件变化须回到第 1 页 */
function handleSearch() {
  query.page = 1;
  load();
}
/** 点击「重置」时触发：清空全部条件并回到第 1 页 */
function handleReset() {
  query.name = '';
  query.department = '';
  query.from = '';
  query.to = '';
  query.includeVoid = false;
  query.page = 1;
  load();
}
/** 翻页：@current-change 触发 */
function onPageChange(p) {
  query.page = p;
  load();
}
/** 改每页条数：@size-change 触发；必须回到第 1 页，否则页码会越界 */
function onSizeChange(s) {
  query.size = s;
  query.page = 1;
  load();
}

// ---------------- 员工 ----------------
const users = ref([]);

/**
 * 加载员工下拉：GET /api/users
 * 普通员工无权限调用，直接跳过（录入时固定为本人）
 */
async function loadUsers() {
  if (!canManage()) return;
  try {
    const res = await userApi.list();
    // 排除已删除人员，以及管理员/录入员：调休使用记录只面向普通员工，后端同样会拒绝录入 ADMIN/CLERK
    users.value = (res.data || []).filter(
      (u) => u.status !== 'DELETED' && !(u.roles || []).some((r) => r === 'ADMIN' || r === 'CLERK')
    );
  } catch { /* 无权限忽略 */ }
}

/** 时间格式化：'HH:mm:ss' → 'HH:mm' */
function fmtTime(t) {
  if (!t) return '-';
  return String(t).slice(0, 5);
}
/** 时长格式化：统一保留两位小数 */
function fmtHours(h) {
  return Number(h || 0).toFixed(2);
}
/** 表格行样式：已作废行加 row-void 类（置灰 + 删除线，见 style） */
function rowClass({ row }) {
  return row.status === 'VOID' ? 'row-void' : '';
}
/** 跨页连续序号：本页起始序号 = (页码-1) × 每页条数 + 行索引 + 1 */
function indexOf($index) {
  return (query.page - 1) * query.size + $index + 1;
}

// ---------------- 录入 ----------------
const showEntry = ref(false);
const saving = ref(false);
const entryRows = ref([]); // 录入弹窗的多行数据

/**
 * 生成一行空白录入数据
 * 管理员/录入员默认选中员工列表第一人，普通员工固定为本人
 */
function blankRow() {
  return {
    userId: canManage() ? (users.value[0]?.id ?? null) : (auth.user?.id ?? null),
    date: '',
    startTime: '09:00',
    endTime: '12:00',
    remark: '',
    balance: null, // 该员工当前余额，用于录入时提示是否够用
  };
}
/** 打开录入弹窗：初始一行，并立即拉该员工余额 */
function openEntry() {
  entryRows.value = [blankRow()];
  showEntry.value = true;
  if (entryRows.value[0].userId) fetchBalance(entryRows.value[0]);
}
/** 追加一行：继承上一行的员工、日期与余额，便于同一人多天连续录入 */
function addRow() {
  const prev = entryRows.value[entryRows.value.length - 1];
  const row = blankRow();
  if (prev) {
    row.userId = prev.userId;
    row.date = prev.date;
    row.balance = prev.balance;
  }
  entryRows.value.push(row);
}
/** 删除一行：至少保留一行 */
function removeRow(i) {
  if (entryRows.value.length <= 1) return;
  entryRows.value.splice(i, 1);
}
/** 切换员工：清空旧余额再重新拉取，避免短暂显示上一个员工的数字 */
function onUserChange(row) {
  row.balance = null;
  fetchBalance(row);
}
/** 拉取某员工当前余额：GET /api/leave/balance?userId= */
async function fetchBalance(row) {
  if (!row.userId) return;
  try {
    const res = await leaveApi.balance(row.userId);
    row.balance = res.data?.balance ?? 0;
  } catch { /* 忽略 */ }
}

/**
 * 计算一行的使用时长（小时，两位小数）
 * 直接按时分相减，不扣时段、不封顶；结束早于开始时返回 '0.00'
 * @param {{startTime:string, endTime:string}} row
 * @returns {string}
 */
function rowHours(row) {
  if (!row.startTime || !row.endTime) return '0.00';
  const toMin = (s) => {
    const [h, m] = String(s).split(':').map(Number);
    return h * 60 + (m || 0);
  };
  const diff = toMin(row.endTime) - toMin(row.startTime);
  return diff > 0 ? (diff / 60).toFixed(2) : '0.00';
}

/**
 * 提交录入：POST /api/leave/batch
 * @param {boolean} [allowOverdraft=false] 余额不足时是否强制录入
 * @param {boolean} [allowRestDay=false] 所选日期属于休息日时是否放行（二次确认后带 true 重提）
 * 二次确认链路（互不冲突，可先后触发，且每次重提都会带上已确认的标志位避免重复弹窗/死循环）：
 *  - 余额不足：首次不带 allowOverdraft → 后端报「余额不足」→ 弹确认 → 带 true 重提
 *  - 休息日：首次不带 allowRestDay → 后端报「所选日期属于休息日…」→ 弹确认 → 带 true 重提
 */
async function submitEntry(allowOverdraft = false, allowRestDay = false) {
  const records = [];
  for (let i = 0; i < entryRows.value.length; i++) {
    const r = entryRows.value[i];
    if (!r.userId) return err(`第 ${i + 1} 行：请选择员工`);
    if (!r.date) return err(`第 ${i + 1} 行：请选择使用日期`);
    if (!r.startTime || !r.endTime) return err(`第 ${i + 1} 行：请选择开始/结束时间`);
    if (Number(rowHours(r)) <= 0) return err(`第 ${i + 1} 行：结束时间必须晚于开始时间`);
    // 时间选择器给出 'HH:mm'，后端要求 'HH:mm:ss'
    records.push({
      userId: r.userId,
      date: r.date,
      startTime: `${r.startTime}:00`,
      endTime: `${r.endTime}:00`,
      remark: r.remark || '',
    });
  }
  saving.value = true;
  try {
    // leaveApi.createBatch 不支持 allowOverdraft / allowRestDay 透传，此处直接用 api.post 携带两个放行标志位
    const res = await api.post('/api/leave/batch', { records, allowOverdraft, allowRestDay });
    // overdraftNames 非空说明产生了透支，后端已生成预警消息
    const warned = (res.data?.overdraftNames || []).length > 0;
    ok(warned ? '录入成功，已生成余额预警提醒' : '录入成功');
    showEntry.value = false;
    load();
  } catch (e) {
    const msg = e.message || '录入失败';
    // 休息日提醒：所选日期属于休息日时，确认后带 allowRestDay=true 重提（不影响余额不足逻辑）
    if (!allowRestDay && msg.includes('休息日')) {
      try {
        await ElMessageBox.confirm(msg, '休息日提醒', {
          type: 'warning',
          confirmButtonText: '确认继续',
          cancelButtonText: '取消',
        });
        // 带上已确认的 allowRestDay，余额不足标志位沿用当前值（避免重复处理）
        await submitEntry(allowOverdraft, true);
      } catch { /* 用户取消，不发请求 */ }
      return;
    }
    if (!allowOverdraft && msg.includes('余额不足')) {
      try {
        await ElMessageBox.confirm(msg, '余额不足', {
          type: 'warning',
          confirmButtonText: '确认继续',
          cancelButtonText: '取消',
        });
        // 带上已确认的 allowOverdraft，休息日标志位沿用当前值（避免重复处理）
        await submitEntry(true, allowRestDay);
      } catch { /* 用户取消 */ }
      return;
    }
    err(msg);
  } finally {
    saving.value = false;
  }
}

// ---------------- 编辑 ----------------
const showEdit = ref(false);
const editSaving = ref(false);
const editForm = ref({ id: null, userName: '', userId: null, date: '', startTime: '', endTime: '', remark: '', balance: null });

/**
 * 打开编辑弹窗：时间截取成 'HH:mm' 供 time-select 使用
 * @param {object} r 表格行
 */
function openEdit(r) {
  editForm.value = {
    id: r.id,
    userName: r.userName,
    userId: r.userId,
    originUserId: r.userId, // 原归属员工，用于判断是否换人
    date: r.date,
    startTime: fmtTime(r.startTime),
    endTime: fmtTime(r.endTime),
    remark: r.remark || '',
    balance: null,
  };
  showEdit.value = true;
  fetchEditBalance(r.userId);
}
/** 拉取编辑对象的当前余额，用于提示「保存后按差额调整」 */
async function fetchEditBalance(userId) {
  if (!userId) return;
  try {
    const res = await leaveApi.balance(userId);
    editForm.value.balance = res.data?.balance ?? 0;
  } catch { /* 忽略 */ }
}
/** 编辑弹窗实时计算的使用时长 */
const editHours = computed(() => rowHours(editForm.value));

/**
 * 提交编辑：PUT /api/leave/{id}；后端按新旧时长差额自动调余额
 * 带上 userId：与原归属不同即视为换人，后端会退还原员工时长并扣减新员工时长
 */
async function submitEdit() {
  const f = editForm.value;
  if (!f.date) return err('请选择使用日期');
  if (!f.startTime || !f.endTime) return err('请选择开始/结束时间');
  if (Number(editHours.value) <= 0) return err('结束时间必须晚于开始时间');
  if (!f.userId) return err('请选择员工');
  // 记录是否换人，用于给出准确的成功提示
  const userChanged = f.originUserId != null && f.userId !== f.originUserId;
  editSaving.value = true;
  try {
    await leaveApi.update(f.id, {
      userId: f.userId,
      date: f.date,
      startTime: `${f.startTime}:00`,
      endTime: `${f.endTime}:00`,
      remark: f.remark || '',
    });
    ok(userChanged ? '编辑成功，原员工余额已退回、新员工余额已扣减' : '编辑成功，余额已按差额调整');
    showEdit.value = false;
    load();
  } catch (e) {
    err(e.message);
  } finally {
    editSaving.value = false;
  }
}

// ---------------- 作废 ----------------
/**
 * 作废记录：二次确认后 POST /api/leave/{id}/void
 * 逻辑作废不删数据，使用时长退回余额，列表中该行转为「已作废」并置灰
 */
async function voidRec(r) {
  try {
    await ElMessageBox.confirm(
      '确认作废该条调休使用记录？作废后使用时长将恢复至调休余额。',
      '作废确认',
      { type: 'warning', confirmButtonText: '确认作废', cancelButtonText: '取消' }
    );
  } catch { return; }
  try {
    await leaveApi.void(r.id, '');
    ok('已作废，使用时长已恢复至调休余额');
    load();
  } catch (e) {
    err(e.message);
  }
}

// ---------------- 导入 ----------------
const showImport = ref(false);
const uploadRef = ref(null);
const fileList = ref([]);
const importing = ref(false);
const importResult = ref(null); // { success, failed, errors }

/** 打开导入弹窗：清空上次的文件与结果 */
function openImport() {
  fileList.value = [];
  importResult.value = null;
  showImport.value = true;
}
/** 提交导入：POST /api/leave/import（multipart）；完成后刷新列表 */
async function submitImport() {
  const file = fileList.value[0]?.raw;
  if (!file) return err('请选择要导入的 Excel 文件');
  importing.value = true;
  importResult.value = null;
  try {
    const res = await leaveApi.importFile(file);
    importResult.value = res.data || {};
    ok(`导入完成：成功 ${importResult.value.success || 0} 条，失败 ${importResult.value.failed || 0} 条`);
    load();
  } catch (e) {
    err(e.message);
  } finally {
    importing.value = false;
  }
}
/** 下载导入模板：GET /api/leave/template */
async function downloadTemplate() {
  try {
    await leaveApi.downloadTemplate();
  } catch (e) {
    err(e.message);
  }
}

// ---------------- 导出 ----------------
const exporting = ref(false);
/** 导出：GET /api/leave/export，复用当前查询条件；无数据时后端会返回明确提示，这里降级为 warning */
async function exportFile() {
  exporting.value = true;
  try {
    await leaveApi.exportFile({
      userId: selfOnly.value ? auth.user?.id : undefined,
      name: query.name || undefined,
      department: query.department || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      includeVoid: query.includeVoid,
    });
    ok('导出成功');
  } catch (e) {
    const msg = e.message || '导出失败';
    if (msg.includes('当前查询结果无数据可导出')) ElMessage.warning(msg);
    else err(msg);
  } finally {
    exporting.value = false;
  }
}

onMounted(() => {
  load();
  loadUsers();
});

/** 员工下拉选项（录入弹窗与编辑弹窗共用）：姓名后带部门，便于区分同名 */
const userOptions = computed(() =>
  users.value.map((u) => ({ id: u.id, label: `${u.name}（${u.department || '未分组'}）` }))
);
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div class="page-title">调休使用记录</div>
      <div class="page-actions">
        <el-button
          v-permission="'leave:import'"
          :icon="Upload"
          @click="openImport"
        >导入</el-button>
        <el-button
          v-permission="'leave:export'"
          :icon="Download"
          :loading="exporting"
          @click="exportFile"
        >导出</el-button>
        <el-button
          v-permission="'leave:add'"
          type="primary"
          :icon="Plus"
          @click="openEntry"
        >录入调休使用记录</el-button>
      </div>
    </div>

    <div class="card">
      <el-form :inline="true" class="search-form" @submit.prevent>
        <el-form-item v-if="!selfOnly" label="姓名">
          <el-input v-model="query.name" placeholder="请输入姓名" clearable style="width: 160px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item v-if="!selfOnly" label="部门">
          <el-input v-model="query.department" placeholder="请输入部门" clearable style="width: 160px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="使用日期">
          <el-date-picker
            v-model="query.from"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="开始日期"
            style="width: 160px"
          />
          <span class="range-sep">~</span>
          <el-date-picker
            v-model="query.to"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="结束日期"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item v-if="!selfOnly">
          <el-checkbox v-model="query.includeVoid" @change="handleSearch">显示已作废</el-checkbox>
        </el-form-item>
        <el-form-item class="search-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table
        v-loading="loading"
        :data="rows"
        border
        stripe
        :row-class-name="rowClass"
        style="width: 100%"
      >
        <el-table-column label="序号" width="70" align="center">
          <template #default="{ $index }">{{ indexOf($index) }}</template>
        </el-table-column>
        <el-table-column prop="userName" label="员工姓名" min-width="110" show-overflow-tooltip />
        <el-table-column prop="department" label="部门" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>
        <el-table-column prop="date" label="日期" width="120" align="center" />
        <el-table-column label="开始时间" width="100" align="center">
          <template #default="{ row }">{{ fmtTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="100" align="center">
          <template #default="{ row }">{{ fmtTime(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="使用时长" width="110" align="center">
          <template #default="{ row }">
            <span class="hours">{{ fmtHours(row.hours) }} 小时</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <!-- 操作列仅管理员/录入员可见：普通员工只读本人记录 -->
        <el-table-column v-if="canManage()" label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'VOID'">
              <el-tag type="info" size="small" effect="plain">已作废</el-tag>
            </template>
            <template v-else>
              <el-tooltip content="编辑" placement="top">
                <el-button
                  v-permission="'leave:edit'"
                  link
                  type="primary"
                  :icon="Edit"
                  @click="openEdit(row)"
                />
              </el-tooltip>
              <el-tooltip content="作废" placement="top">
                <el-button
                  v-permission="'leave:void'"
                  link
                  type="danger"
                  :icon="CircleClose"
                  @click="voidRec(row)"
                />
              </el-tooltip>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div class="table-foot">
        <span class="total-text">共计 {{ total }} 条数据</span>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- 录入弹窗 -->
    <el-dialog v-model="showEntry" title="录入调休使用记录" width="900px" :close-on-click-modal="false">
      <div class="entry-head">
        <span class="col-user">员工姓名</span>
        <span class="col-date">日期</span>
        <span class="col-time">开始时间</span>
        <span class="col-time">结束时间</span>
        <span class="col-hours">使用时长</span>
        <span class="col-remark">备注</span>
        <span class="col-op"></span>
      </div>
      <div v-for="(r, i) in entryRows" :key="i" class="entry-row">
        <div class="col-user">
          <el-select
            v-model="r.userId"
            filterable
            placeholder="搜索姓名"
            class="w100"
            @change="onUserChange(r)"
          >
            <el-option v-for="u in userOptions" :key="u.id" :label="u.label" :value="u.id" />
          </el-select>
          <div v-if="r.balance !== null" class="balance-hint" :class="{ negative: Number(r.balance) < 0 }">
            剩余余额 {{ Number(r.balance).toFixed(2) }} 小时
          </div>
        </div>
        <div class="col-date">
          <el-date-picker v-model="r.date" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" class="w100" />
        </div>
        <div class="col-time">
          <el-time-select v-model="r.startTime" start="00:00" step="00:30" end="23:30" placeholder="开始" class="w100" />
        </div>
        <div class="col-time">
          <el-time-select v-model="r.endTime" start="00:30" step="00:30" end="23:59" placeholder="结束" class="w100" />
        </div>
        <div class="col-hours">
          <el-input :model-value="rowHours(r)" readonly class="w100 hours-input" />
        </div>
        <div class="col-remark">
          <el-input v-model="r.remark" placeholder="备注（可选）" class="w100" />
        </div>
        <div class="col-op">
          <el-button
            link
            type="danger"
            :icon="Delete"
            :disabled="entryRows.length <= 1"
            @click="removeRow(i)"
          />
        </div>
      </div>

      <div class="entry-add">
        <el-button link type="primary" :icon="Plus" @click="addRow">添加一行</el-button>
      </div>

      <template #footer>
        <el-button @click="showEntry = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEntry(false)">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="showEdit" title="编辑调休使用记录" width="560px" :close-on-click-modal="false">
      <el-form label-width="90px">
        <el-form-item label="员工姓名">
          <!-- 管理员/录入员可用下拉改归属员工；普通员工只能看自己，保持只读 -->
          <el-select
            v-if="canManage()"
            v-model="editForm.userId"
            filterable
            placeholder="搜索姓名"
            style="width: 100%"
            @change="(v) => fetchEditBalance(v)"
          >
            <el-option v-for="u in userOptions" :key="u.id" :label="u.label" :value="u.id" />
          </el-select>
          <el-input v-else :model-value="editForm.userName || '-'" disabled />
        </el-form-item>
        <el-form-item label="使用日期">
          <el-date-picker
            v-model="editForm.date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-time-select
            v-model="editForm.startTime"
            start="00:00"
            step="00:30"
            end="23:30"
            placeholder="开始"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-time-select
            v-model="editForm.endTime"
            start="00:30"
            step="00:30"
            end="23:59"
            placeholder="结束"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="使用时长">
          <el-input :model-value="`${editHours} 小时`" readonly />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" placeholder="备注（可选）" />
        </el-form-item>
      </el-form>

      <div v-if="editForm.balance !== null" class="balance-hint" :class="{ negative: Number(editForm.balance) < 0 }">
        当前剩余余额 {{ Number(editForm.balance).toFixed(2) }} 小时（保存后按差额自动调整）
      </div>

      <div v-if="canManage()" class="edit-tip">
        更换归属员工后，原员工退回本次使用时长、新员工扣减本次使用时长，两边余额即刻同步。
      </div>

      <template #footer>
        <el-button @click="showEdit = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 导入弹窗 -->
    <el-dialog v-model="showImport" title="导入调休使用记录" width="560px" :close-on-click-modal="false">
      <div class="import-tip">
        模板列顺序：<b>员工姓名、使用日期、开始时间、结束时间、备注</b>
        <el-button link type="primary" @click="downloadTemplate">下载模板</el-button>
      </div>
      <el-upload
        ref="uploadRef"
        v-model:file-list="fileList"
        :auto-upload="false"
        :limit="1"
        accept=".xlsx,.xls"
      >
        <template #trigger>
          <el-button type="primary">选择文件</el-button>
        </template>
        <template #tip>
          <div class="el-upload__tip">仅支持 .xlsx / .xls，单次最多 1 个文件</div>
        </template>
      </el-upload>

      <div v-if="importResult" class="import-result">
        <div>
          成功 <b class="ok-text">{{ importResult.success || 0 }}</b> 条，
          失败 <b class="err-text">{{ importResult.failed || 0 }}</b> 条
        </div>
        <ul v-if="(importResult.errors || []).length" class="error-list">
          <li v-for="(e, i) in importResult.errors" :key="i">{{ e }}</li>
        </ul>
      </div>

      <template #footer>
        <el-button @click="showImport = false">关闭</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 12px;
  flex-wrap: wrap;
}
.page-title { font-size: 20px; font-weight: 800; color: #c0356b; }
.page-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.card {
  background: #fff; border-radius: 12px; box-shadow: 0 2px 10px rgba(255, 111, 165, 0.1);
  padding: 18px; border: 1px solid #ffe6f1;
}
/* 搜索区：条件与查询/重置按钮固定在同一行，宽度不足时横向滚动而非换行 */
.search-form {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 0 12px;
  overflow-x: auto;
  padding-bottom: 4px;
  margin-bottom: 4px;
}
.search-form :deep(.el-form-item) { margin-bottom: 0; margin-right: 0; }
.search-form :deep(.el-form-item__label) { padding-right: 8px; }
/* 按钮组靠右，条件数量变化（员工视角只剩日期）也不会跑位 */
.search-actions { margin-left: auto; flex: none; }
.range-sep { margin: 0 8px; color: #c39ab2; }
.table-foot {
  display: flex; align-items: center; justify-content: space-between;
  padding-top: 12px; flex-wrap: wrap; gap: 8px;
}
.total-text { font-size: 13px; color: #9a7287; }
.hours { font-weight: 700; color: #d4568a; }

.entry-head, .entry-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.entry-head {
  font-size: 12px;
  color: #9a7287;
  padding-bottom: 6px;
  border-bottom: 1px solid #ffe6f1;
  margin-bottom: 8px;
}
.entry-row { margin-bottom: 10px; }
.col-user { width: 190px; flex: none; }
.col-date { width: 150px; flex: none; }
.col-time { width: 120px; flex: none; }
.col-hours { width: 100px; flex: none; }
.col-remark { flex: 1; min-width: 120px; }
.col-op { width: 40px; flex: none; text-align: center; }
.w100 { width: 100%; }
.balance-hint { margin-top: 4px; font-size: 12px; color: #67c23a; }
.balance-hint.negative { color: #f56c6c; font-weight: 700; }
.entry-add { margin-top: 4px; }

.edit-tip { margin-top: 10px; font-size: 12px; color: var(--text-sub); }
.import-tip {
  font-size: 13px; color: #9a7287; margin-bottom: 12px;
  display: flex; align-items: center; gap: 8px;
}
.import-result {
  margin-top: 14px; padding: 10px 12px; background: #fff7fb;
  border: 1px solid #ffe6f1; border-radius: 8px; font-size: 13px;
}
.error-list { margin: 8px 0 0; padding-left: 18px; max-height: 180px; overflow: auto; color: #f56c6c; }
.ok-text { color: #67c23a; }
.err-text { color: #f56c6c; }

/* 已作废记录不做物理删除，仅置灰展示 */
:deep(.row-void) {
  color: #c0c4cc;
  background: #fafafa;
}
:deep(.row-void .cell) {
  color: #c0c4cc;
}
:deep(.row-void .hours) {
  color: #c0c4cc;
  text-decoration: line-through;
}
</style>

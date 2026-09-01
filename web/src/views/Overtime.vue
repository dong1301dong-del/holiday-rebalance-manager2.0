<script setup>
/**
 * 增加调休时长（路由 /overtime，权限码 overtime:view）
 * 职责：加班转调休记录的查询、批量录入、编辑、删除、导入与导出。
 * 按钮权限：overtime:add（录入）、overtime:edit（编辑）、overtime:delete（删除）、
 *          overtime:import（导入）、overtime:export（导出）。
 *
 * 两种录入模式（弹窗内用单选切换，同一批只能是一种）：
 *  ① 加班转休 OVERTIME：填加班日期 + 开始/结束时间 + 打卡时间（均必填，默认 18:30/20:30/20:30）；
 *     转休时长 = 加班时长 × 系数（法定工作日/补班日 0.5，休息日/法定节假日 1），系数按所选日期自动判定。
 *  ② 其他转休 MANUAL：只填转休时长 + 备注（备注必填）；不填日期时默认记为当天。
 *
 * 普通员工（EMPLOYEE）只能录入/查看本人数据，员工下拉固定为本人。
 */
import { ref, reactive, onMounted, onBeforeUnmount, computed, nextTick } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Edit, Delete, Plus } from '@element-plus/icons-vue';
import * as echarts from 'echarts';
import { overtimeApi, userApi, holidayApi } from '../api';
import { auth, isEmployee, canManage } from '../store';
import { ok, err } from '../toast';

/** 日类型中文与默认系数（后端以日历判定为准，此处仅作兜底展示） */
const DAY_TYPE = {
  WORKDAY: '法定工作日',
  RESTDAY: '休息日',
  LEGAL: '法定节假日',
  HOLIDAY: '法定节假日', // 后端历史上用过 HOLIDAY，与 LEGAL 同义
  ADJUSTED: '补班日',
};
/** 折算系数：法定工作日与补班日 1:0.5，休息日与法定节假日 1:1 */
const defaultRatio = (dayType) => (dayType === 'WORKDAY' || dayType === 'ADJUSTED' ? 0.5 : 1);
const labelOf = (dayType) => DAY_TYPE[dayType] || dayType || '-';

/** 录入模式常量：加班转休 / 其他转休（其他转休在后端存为 MANUAL） */
const MODE_OVERTIME = 'OVERTIME';
const MODE_OTHER = 'MANUAL';
const isOther = (mode) => mode === MODE_OTHER;

// ---------------- 查询 ----------------
const search = reactive({ from: '', to: '', name: '', department: '' });
const list = ref([]);
const loading = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);

const users = ref([]);   // 员工下拉（管理员/录入员可见）
const userMap = ref({}); // id → 用户，用于快速取用户名

/** 加载员工下拉：GET /api/users；普通员工无权限，直接跳过 */
async function loadUsers() {
  if (isEmployee()) return;
  try {
    const res = await userApi.list();
    // 管理员/录入员自身不参与加班调休折算，下拉里直接隐藏，避免误选被后端拒绝
    users.value = (res.data || []).filter(
      (u) => u.status !== 'DELETED' && !(u.roles || []).some((r) => r === 'ADMIN' || r === 'CLERK')
    );
    const m = {};
    users.value.forEach((u) => (m[u.id] = u));
    userMap.value = m;
  } catch { /* 无权限时忽略 */ }
}

/**
 * 普通员工只能看本人数据：查询/导出时强制带上 userId
 * 管理员与录入员不限制，返回 undefined 表示不带该条件
 * @returns {number|undefined}
 */
const selfScope = () => (isEmployee() && auth.user?.id ? auth.user.id : undefined);

/** 加载列表：GET /api/overtime；空条件转 undefined */
async function loadList() {
  loading.value = true;
  try {
    const res = await overtimeApi.list({
      userId: selfScope(),
      from: search.from || undefined,
      to: search.to || undefined,
      name: search.name || undefined,
      department: search.department || undefined,
      page: page.value,
      size: size.value,
    });
    list.value = res.data || [];
    total.value = res.total || 0;
    // 删除末页最后一条后回退一页：此时 total 变小导致当前页无数据，需自动跳回上一页
    if (total.value > 0 && list.value.length === 0 && page.value > 1) {
      page.value -= 1;
      loadList();
      return;
    }
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}

/** 点击「查询」时触发：回到第 1 页 */
function onSearch() {
  page.value = 1;
  loadList();
}
/** 点击「重置」时触发：清空条件并回到第 1 页 */
function onReset() {
  search.from = '';
  search.to = '';
  search.name = '';
  search.department = '';
  page.value = 1;
  loadList();
}
/** 翻页：@current-change 触发 */
function onPageChange(p) {
  page.value = p;
  loadList();
}
/** 改每页条数：@size-change 触发；须回到第 1 页避免页码越界 */
function onSizeChange(s) {
  size.value = s;
  page.value = 1;
  loadList();
}
/** 跨页连续序号 */
const indexOf = (i) => (page.value - 1) * size.value + i + 1;

onMounted(() => {
  loadList();
  loadUsers();
});

/** 窗口尺寸变化时趋势图同步自适应（响应式布局要求）：弹窗打开期间缩放浏览器即重绘 */
function onWindowResize() {
  trendChart.value?.resize();
}
onMounted(() => window.addEventListener('resize', onWindowResize));
onBeforeUnmount(() => window.removeEventListener('resize', onWindowResize));

// ---------------- 日历解析 ----------------
/**
 * 按日期自动判定日类型与系数：GET /api/holidays/resolve?date=
 * 接口不可用时按周末兜底判定，保证录入界面仍能给出系数，不至于卡住录入
 * @param {string} date 'YYYY-MM-DD'
 * @returns {Promise<{dayType:string, ratio:number|null, holidayName:string}>}
 */
async function resolveDay(date) {
  if (!date) return { dayType: '', ratio: null, holidayName: '' };
  try {
    const res = await holidayApi.resolve(date);
    const d = res.data || {};
    return {
      // 后端 HolidayDto.ResolveResult：type 为主字段，dayType/holidayName 为兼容别名
      dayType: d.dayType || d.type || '',
      ratio: d.ratio != null ? Number(d.ratio) : null,
      holidayName: d.holidayName || d.name || '',
    };
  } catch {
    // 兜底：周六周日算休息日，其余算法定工作日；加 T00:00:00 避免按 UTC 解析导致日期偏移
    const dow = new Date(date + 'T00:00:00').getDay();
    const dayType = dow === 0 || dow === 6 ? 'RESTDAY' : 'WORKDAY';
    return { dayType, ratio: defaultRatio(dayType), holidayName: '' };
  }
}

// ---------------- 时长计算 ----------------
/**
 * 'HH:mm' → 分钟数
 * @param {string} hhmm
 * @returns {number|null} 解析失败返回 null
 */
function toMinutes(hhmm) {
  if (!hhmm) return null;
  const [h, m] = String(hhmm).split(':').map(Number);
  if (Number.isNaN(h) || Number.isNaN(m)) return null;
  return h * 60 + m;
}
/** 加班时长 = 结束时间 − 开始时间（小时，两位小数），不扣时段、不封顶 */
function hoursBetween(start, end) {
  const s = toMinutes(start);
  const e = toMinutes(end);
  if (s == null || e == null || e <= s) return 0;
  return (e - s) / 60;
}
/** 统一保留两位小数 */
const fmt2 = (n) => Number(n || 0).toFixed(2);

/** 一行表单的加班时长（小时） */
function hoursOf(row) { return hoursBetween(row.startTime, row.endTime); }
/**
 * 一行的转休时长（小时）
 * 加班转休：加班时长 × 系数；其他转休：直接取手工填写的 convertedHours
 * @param {object} row 表单行
 * @param {string} mode 当前模式
 */
function convertedOf(row, mode) {
  if (isOther(mode)) return Number(row.convertedHours || 0);
  return hoursOf(row) * (row.ratio != null && row.ratio !== '' ? Number(row.ratio) : 0);
}

// ---------------- 录入弹窗 ----------------
const showEntry = ref(false);
const saving = ref(false);
const entryMode = ref(MODE_OVERTIME); // 当前录入模式
const entryUserId = ref('');          // 整批记录统一归属的员工（弹窗顶部选择）
const rows = ref([]);                 // 多行录入数据

// 员工固定逻辑：普通员工锁定为自己，管理员默认选中列表第一人
const selfId = () => (isEmployee() && auth.user?.id ? auth.user.id : '');
const firstUserId = () => selfId() || users.value[0]?.id || '';

/** 今天（YYYY-MM-DD）：其他转休不填写日期，默认记为当天 */
function todayStr() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 一行空白表单：加班转休与其他转休共用同一结构，用不到的字段留空即可 */
function blankRow() {
  return {
    date: '',
    dayType: '',
    ratio: null,
    holidayName: '',
    startTime: '18:30', // 默认下班后加班
    endTime: '20:30',
    clockInTime: '20:30', // 打卡时间，默认与结束时间一致
    convertedHours: null,
    remark: '',
  };
}
/** 打开录入弹窗：重置为加班转休模式 + 一行空白 */
function openEntry() {
  entryMode.value = MODE_OVERTIME;
  entryUserId.value = firstUserId();
  rows.value = [blankRow()];
  showEntry.value = true;
}
/** 切换录入模式：@change 触发；清空各行的模式相关字段，仅保留已选日期 */
function onEntryModeChange() {
  rows.value = rows.value.map((r) => ({ ...blankRow(), date: r.date }));
}
/** 追加一行 */
function addRow() {
  const last = rows.value[rows.value.length - 1];
  const row = blankRow();
  // 同一弹窗内固定员工，后续行继承上一行的日期与时间段，便于连续录入多天
  row.date = last?.date || '';
  if (!isOther(entryMode.value)) {
    row.startTime = last?.startTime || '18:30';
    row.endTime = last?.endTime || '20:30';
    row.clockInTime = last?.clockInTime || '20:30';
  }
  rows.value.push(row);
}
/** 删除一行：至少保留一行 */
function removeRow(i) {
  if (rows.value.length <= 1) return;
  rows.value.splice(i, 1);
}
/**
 * 加班日期变化：@change 触发
 * 重新解析该日期的类型与系数并回填；清空日期时一并清掉类型相关字段
 * @param {object} row 表单行（录入多行或编辑单行）
 */
async function onDateChange(row) {
  if (!row.date) {
    row.dayType = '';
    row.ratio = null;
    row.holidayName = '';
    return;
  }
  const d = await resolveDay(row.date);
  row.dayType = d.dayType;
  // 接口没给 coefficient 时用内置默认系数兜底
  row.ratio = d.ratio != null ? d.ratio : defaultRatio(d.dayType);
  row.holidayName = d.holidayName;
}

/**
 * 把一行表单数据整理成后端记录（两种模式共用）
 * 其他转休：只提交 mode/userId/date/convertedHours/remark，日期缺省取当天
 * 加班转休：提交 mode/userId/date/startTime/endTime/clockInTime?/remark?
 * @param {object} row 表单行
 * @param {string} mode 模式
 * @returns {object} 后端 /api/overtime/batch 的 records 元素
 */
function buildRecord(row, mode) {
  const base = {
    mode,
    userId: Number(entryUserId.value) || undefined,
    date: row.date,
    remark: row.remark || undefined,
  };
  if (isOther(mode)) {
    // 其他转休：只记录姓名、转休时长、备注；日期默认取当天
    return { ...base, date: row.date || todayStr(), convertedHours: Number(row.convertedHours) };
  }
  return {
    ...base,
    // 时间选择器给 'HH:mm'，后端要求 'HH:mm:ss'
    startTime: row.startTime + ':00',
    endTime: row.endTime + ':00',
    clockInTime: row.clockInTime ? row.clockInTime + ':00' : undefined,
  };
}

/**
 * 提交录入：POST /api/overtime/batch
 * 逐行校验，错误提示带行号；整批要么全成功要么全失败
 */
async function submitEntry() {
  if (!entryUserId.value) return err('请选择员工姓名');
  const records = [];
  for (let i = 0; i < rows.value.length; i++) {
    const r = rows.value[i];
    const no = `第 ${i + 1} 行`;
    if (isOther(entryMode.value)) {
      if (r.convertedHours === null || r.convertedHours === '') return err(`${no}：请输入转休时长`);
      if (Number(r.convertedHours) < 0) return err(`${no}：转休时长不能为负数`);
      if (!r.remark) return err(`${no}：其他转休的备注为必填项`);
    } else {
      if (!r.date) return err(`${no}：请选择加班日期`);
      if (!r.startTime || !r.endTime) return err(`${no}：请选择开始与结束时间`);
      if (!r.clockInTime) return err(`${no}：请选择打卡时间`);
      if (hoursBetween(r.startTime, r.endTime) <= 0) return err(`${no}：结束时间必须晚于开始时间`);
    }
    records.push(buildRecord(r, entryMode.value));
  }
  saving.value = true;
  try {
    await overtimeApi.createBatch(records);
    ok(`已录入 ${records.length} 条记录`);
    showEntry.value = false;
    loadList();
  } catch (e) {
    err(e.message);
  } finally {
    saving.value = false;
  }
}

// ---------------- 编辑 ----------------
const showEdit = ref(false);
const editId = ref(null);
const editMode = ref(MODE_OVERTIME);
const editRow = ref(blankRow());
const editUserId = ref('');

/** 录入弹窗顶部展示的员工姓名（普通员工取自己，管理员按所选 id 反查） */
const entryUserName = computed(() => userMap.value[entryUserId.value]?.name || auth.user?.name || '');

/**
 * 打开编辑弹窗：按该行原 mode 还原模式，时间截取成 'HH:mm'
 * 原记录缺 dayType 时主动解析一次，确保界面上类型与比例不为空
 * @param {object} r 表格行
 */
async function openEdit(r) {
  editId.value = r.id;
  editMode.value = isOther(r.mode) ? MODE_OTHER : MODE_OVERTIME;
  editUserId.value = r.userId || firstUserId();
  editRow.value = {
    date: r.date,
    dayType: r.dayType,
    ratio: r.ratio != null ? Number(r.ratio) : null,
    holidayName: r.holidayName || '',
    startTime: (r.startTime || '18:30').slice(0, 5),
    endTime: (r.endTime || '20:30').slice(0, 5),
    clockInTime: (r.clockInTime || '').slice(0, 5),
    convertedHours: r.convertedHours != null ? Number(r.convertedHours) : null,
    remark: r.remark || '',
  };
  showEdit.value = true;
  if (!editRow.value.dayType) await onDateChange(editRow.value);
}
/** 编辑弹窗切换模式：重置为对应模式的空行，仅保留日期 */
function onEditModeChange() {
  const keepDate = editRow.value.date;
  editRow.value = { ...blankRow(), date: keepDate };
}

/**
 * 提交编辑：PUT /api/overtime/{id}
 * 两种模式提交字段互斥：其他转休不传时间段，加班转休不传 convertedHours，由后端重算
 */
async function submitEdit() {
  if (!editUserId.value) return err('请选择员工姓名');
  const r = editRow.value;
  if (isOther(editMode.value)) {
    if (r.convertedHours === null || r.convertedHours === '') return err('请输入转休时长');
    if (Number(r.convertedHours) < 0) return err('转休时长不能为负数');
    if (!r.remark) return err('其他转休的备注为必填项');
  } else {
    if (!r.date) return err('请选择加班日期');
    if (!r.startTime || !r.endTime) return err('请选择开始与结束时间');
    if (!r.clockInTime) return err('请选择打卡时间');
    if (hoursBetween(r.startTime, r.endTime) <= 0) return err('结束时间必须晚于开始时间');
  }
  saving.value = true;
  try {
    await overtimeApi.update(editId.value, {
      mode: editMode.value,
      userId: Number(editUserId.value),
      date: isOther(editMode.value) ? r.date || todayStr() : r.date,
      startTime: isOther(editMode.value) ? undefined : r.startTime + ':00',
      endTime: isOther(editMode.value) ? undefined : r.endTime + ':00',
      clockInTime: !isOther(editMode.value) && r.clockInTime ? r.clockInTime + ':00' : undefined,
      convertedHours: isOther(editMode.value) ? Number(r.convertedHours) : undefined,
      remark: r.remark || undefined,
    });
    ok('已保存');
    showEdit.value = false;
    loadList();
  } catch (e) {
    err(e.message);
  } finally {
    saving.value = false;
  }
}

// ---------------- 删除 ----------------
/**
 * 删除记录：二次确认（文案中带出将被冲减的转休时长）后 DELETE /api/overtime/{id}
 * @param {object} r 表格行
 */
async function onDelete(r) {
  try {
    await ElMessageBox.confirm(
      `确定删除 ${r.userName || '该员工'} ${r.date} 的记录吗？已折算的 ${fmt2(r.convertedHours)}h 调休将一并冲减。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await overtimeApi.remove(r.id);
    ok('已删除');
    loadList();
  } catch (e) {
    err(e.message);
  }
}

// ---------------- 我的加班趋势（仅普通员工可见） ----------------
const showTrend = ref(false);
const trendFrom = ref('');    // YYYY-MM-DD，留空表示不限
const trendTo = ref('');
const trendLoading = ref(false);
const trendChart = ref(null); // echarts 实例
const trendRef = ref(null);   // 图表容器 DOM
const trendEmpty = ref(false);
const trendData = ref([]);    // [{ month: 'YYYY-MM', hours: Number }]，月份升序

/**
 * 默认区间：近 12 个月（今天 与 往前推 11 个月的当月 1 号）
 * @returns {{from:string, to:string}}
 */
function defaultTrendRange() {
  const p = (n) => String(n).padStart(2, '0');
  const now = new Date();
  const f = new Date(now.getFullYear(), now.getMonth() - 11, 1);
  return {
    from: `${f.getFullYear()}-${p(f.getMonth() + 1)}-01`,
    to: `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`,
  };
}

/** 打开趋势弹窗：首次打开填充近 12 个月，随后立即查询一次 */
async function openTrend() {
  if (!trendFrom.value && !trendTo.value) {
    const d = defaultTrendRange();
    trendFrom.value = d.from;
    trendTo.value = d.to;
  }
  showTrend.value = true;
  await nextTick();
  loadTrend();
}

/** 弹窗关闭时销毁图表实例，避免再次打开复用旧容器报错 */
function disposeTrend() {
  trendChart.value?.dispose();
  trendChart.value = null;
}

/**
 * 查询并按月聚合：复用列表接口 GET /api/overtime，单页取 2000 条
 * 以 date 的 YYYY-MM 为桶累加 convertedHours，结果按月份升序
 */
async function loadTrend() {
  trendLoading.value = true;
  try {
    const res = await overtimeApi.list({
      userId: auth.user?.id,
      from: trendFrom.value || undefined,
      to: trendTo.value || undefined,
      page: 1,
      size: 2000,
    });
    const bucket = {};
    (res.data || []).forEach((r) => {
      if (!r.date) return;
      const m = String(r.date).slice(0, 7);
      bucket[m] = (bucket[m] || 0) + Number(r.convertedHours || 0);
    });
    trendData.value = Object.keys(bucket)
      .sort()
      .map((m) => ({ month: m, hours: Number(bucket[m].toFixed(2)) }));
    trendEmpty.value = trendData.value.length === 0;
    // 等 v-if/v-else 切换完成：容器可能刚挂载（有数据）或刚卸载（无数据）
    await nextTick();
    if (trendEmpty.value) {
      disposeTrend();
      return;
    }
    renderTrend();
  } catch (e) {
    err(e.message);
  } finally {
    trendLoading.value = false;
  }
}

/** 渲染折线图（带渐变面积），notMerge 保证切换区间后彻底替换旧 option */
function renderTrend() {
  const el = trendRef.value;
  if (!el) return;
  // 空态与图表互相切换会重建 DOM，旧实例指向已卸载节点时必须重建
  if (trendChart.value && trendChart.value.getDom() !== el) {
    trendChart.value.dispose();
    trendChart.value = null;
  }
  if (!trendChart.value) trendChart.value = echarts.init(el);
  const d = trendData.value;
  trendChart.value.setOption(
    {
      grid: { left: 56, right: 24, top: 40, bottom: 48 },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0];
          return `${p.name}<br/>转休时长：${Number(p.value || 0).toFixed(2)} 小时`;
        },
      },
      xAxis: {
        type: 'category',
        data: d.map((m) => m.month),
        axisLine: { lineStyle: { color: '#ffd9e8' } },
        axisLabel: { color: '#9a7287' },
      },
      yAxis: {
        type: 'value',
        name: '转休时长(h)',
        nameTextStyle: { color: '#9a7287' },
        axisLabel: { color: '#9a7287' },
        splitLine: { lineStyle: { color: '#fff0f6' } },
      },
      series: [
        {
          name: '转休时长',
          type: 'line',
          smooth: true,
          symbolSize: 7,
          data: d.map((m) => Number(m.hours || 0)),
          itemStyle: { color: '#ff6fa5' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(255,111,165,0.35)' },
              { offset: 1, color: 'rgba(255,111,165,0.02)' },
            ]),
          },
        },
      ],
    },
    true
  );
}

// ---------------- 导入 ----------------
const showImport = ref(false);
const uploadFiles = ref([]);
const importing = ref(false);
const importResult = ref(null); // { success, failed, errors }

/** 打开导入弹窗：清空上次的文件与结果 */
function openImport() {
  importResult.value = null;
  uploadFiles.value = [];
  showImport.value = true;
}
/** 提交导入：POST /api/overtime/import（multipart）；全部成功才额外弹成功提示 */
async function submitImport() {
  const file = uploadFiles.value[0]?.raw;
  if (!file) return err('请选择要导入的 Excel 文件');
  importing.value = true;
  try {
    const res = await overtimeApi.importFile(file);
    importResult.value = res.data || {};
    if (importResult.value.failed === 0) {
      ElMessage.success(`导入成功 ${importResult.value.success || 0} 条`);
    }
    loadList();
  } catch (e) {
    err(e.message);
  } finally {
    importing.value = false;
  }
}
/** 下载导入模板：GET /api/overtime/template */
async function downloadTemplate() {
  try {
    await overtimeApi.downloadTemplate();
    ok('模板已下载');
  } catch (e) {
    err(e.message);
  }
}

// ---------------- 导出 ----------------
const exporting = ref(false);
/** 导出：GET /api/overtime/export；无数据时后端会给出明确提示，这里降级为 warning */
async function onExport() {
  exporting.value = true;
  try {
    // 不传查询条件时后端导出全部；员工仅导出本人
    await overtimeApi.exportFile({
      userId: selfScope(),
      from: search.from || undefined,
      to: search.to || undefined,
      name: search.name || undefined,
      department: search.department || undefined,
    });
    ok('导出成功');
  } catch (e) {
    ElMessage.warning(e.message || '当前查询结果无数据可导出');
  } finally {
    exporting.value = false;
  }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">加班录入记录</div>
        <div class="page-sub">
          ①加班转调休：法定工作日：1:0.5换算；节假日，休息日1:1换算；②其他转休：只记录姓名，转调休时长，备注(必填项)
        </div>
      </div>
      <div class="btn-row">
        <!-- 员工本人的加班趋势入口：管理员/录入员走「全员调休查看」页 -->
        <el-button v-if="isEmployee()" @click="openTrend">我的加班趋势</el-button>
        <el-button v-permission="'overtime:import'" @click="openImport">导入</el-button>
        <el-button v-permission="'overtime:export'" :loading="exporting" @click="onExport">导出</el-button>
        <el-button v-permission="'overtime:add'" type="primary" @click="openEntry">＋ 增加时长</el-button>
      </div>
    </div>

    <div class="card">
      <!-- 搜索区 -->
      <el-form :inline="true" :model="search" class="search-form" @submit.prevent>
        <el-form-item label="加班日期">
          <el-date-picker
            v-model="search.from"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="开始日期"
            style="width: 150px"
            clearable
          />
          <span style="margin: 0 6px; color: var(--text-light)">~</span>
          <el-date-picker
            v-model="search.to"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="结束日期"
            style="width: 150px"
            clearable
          />
        </el-form-item>
        <!-- 员工视角只保留加班日期范围：姓名/部门仅管理员与录入员可用 -->
        <el-form-item v-if="canManage()" label="姓名">
          <el-input v-model="search.name" placeholder="员工姓名（模糊）" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item v-if="canManage()" label="部门">
          <el-input v-model="search.department" placeholder="部门" clearable style="width: 150px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table v-loading="loading" :data="list" border stripe size="default" style="width: 100%">
        <el-table-column label="序号" width="70" align="center" fixed="left">
          <template #default="{ $index }">{{ indexOf($index) }}</template>
        </el-table-column>
        <el-table-column label="姓名" prop="userName" min-width="100" show-overflow-tooltip />
        <el-table-column label="加班日期" width="130" align="center">
          <template #default="{ row }">
            {{ row.date }}
            <div v-if="row.holidayName" style="color: var(--warning); font-size: 12px">{{ row.holidayName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="部门" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="!isOther(row.mode)" size="small" :type="row.dayType === 'WORKDAY' ? 'info' : 'success'">
              {{ labelOf(row.dayType) }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="比例" width="80" align="center">
          <template #default="{ row }">
            <template v-if="!isOther(row.mode) && row.ratio != null && row.ratio !== ''">
              {{ Number(row.ratio).toFixed(1) }}
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="开始" width="90" align="center">
          <template #default="{ row }">
            {{ isOther(row.mode) ? '-' : (row.startTime || '').slice(0, 5) || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="结束" width="90" align="center">
          <template #default="{ row }">
            {{ isOther(row.mode) ? '-' : (row.endTime || '').slice(0, 5) || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="加班时长" width="100" align="center">
          <template #default="{ row }">
            <template v-if="!isOther(row.mode)">{{ fmt2(row.hours) }}h</template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="转休时长" width="110" align="center">
          <template #default="{ row }">
            <span style="color: var(--primary); font-weight: 700">{{ fmt2(row.convertedHours) }}h</span>
          </template>
        </el-table-column>
        <el-table-column label="打卡时间" width="110" align="center">
          <template #default="{ row }">
            {{ isOther(row.mode) ? '-' : (row.clockInTime || '').slice(0, 5) || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canManage()" label="操作" width="110" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="编辑" placement="top">
              <el-button
                v-permission="'overtime:edit'"
                :icon="Edit"
                size="small"
                text
                type="primary"
                @click="openEdit(row)"
              />
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button
                v-permission="'overtime:delete'"
                :icon="Delete"
                size="small"
                text
                type="danger"
                @click="onDelete(row)"
              />
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div style="padding: 30px; color: var(--text-light)">暂无记录</div>
        </template>
      </el-table>

      <!-- 分页 -->
      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
        <span class="total-text">共计 {{ total }} 条数据</span>
      </div>
    </div>

    <!-- 录入弹窗 -->
    <el-dialog v-model="showEntry" width="900px" :close-on-click-modal="false">
      <template #header>
        <div class="dlg-head">
          <span class="dlg-title">增加调休时长</span>
          <div class="dlg-tools">
            <span class="dlg-label">员工姓名</span>
            <el-select
              v-if="!isEmployee()"
              v-model="entryUserId"
              filterable
              placeholder="输入姓名搜索"
              style="width: 180px"
            >
              <el-option
                v-for="u in users"
                :key="u.id"
                :value="u.id"
                :label="`${u.name}（${u.department || '未分组'}）`"
              />
            </el-select>
            <el-input v-else :model-value="entryUserName" disabled style="width: 180px" />
            <el-radio-group v-model="entryMode" size="small" @change="onEntryModeChange">
              <el-radio-button :value="MODE_OVERTIME">加班转休</el-radio-button>
              <el-radio-button :value="MODE_OTHER">其他转休</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>

      <div class="pink-box">
        <!-- 加班转休：每行两行 -->
        <template v-if="!isOther(entryMode)">
          <div class="row-item" v-for="(r, i) in rows" :key="i">
            <!-- 第一行：加班日期 | 类型 | 比例 | 开始 | 结束 -->
            <el-form :inline="true" class="entry-form">
              <el-form-item label="加班日期" required>
                <el-date-picker
                  v-model="r.date"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择日期"
                  style="width: 132px"
                  @change="onDateChange(r)"
                />
              </el-form-item>
              <el-form-item label="类型">
                <el-input :model-value="r.dayType ? labelOf(r.dayType) + (r.holidayName ? `·${r.holidayName}` : '') : '-'" disabled style="width: 140px" />
              </el-form-item>
              <el-form-item label="比例">
                <el-input
                  :model-value="r.ratio != null && r.ratio !== '' ? Number(r.ratio).toFixed(1) : '-'"
                  disabled
                  style="width: 72px"
                />
              </el-form-item>
              <el-form-item label="开始" required>
                <el-time-select
                  v-model="r.startTime"
                  start="00:00"
                  step="00:30"
                  end="23:30"
                  placeholder="18:30"
                  style="width: 110px"
                />
              </el-form-item>
              <el-form-item label="结束" required>
                <el-time-select
                  v-model="r.endTime"
                  start="00:00"
                  step="00:30"
                  end="23:30"
                  placeholder="20:30"
                  style="width: 110px"
                />
              </el-form-item>
            </el-form>

            <!-- 第二行：加班时长 | 转休时长 | 打卡时间 | 备注 -->
            <el-form :inline="true" class="entry-form">
              <el-form-item label="加班时长">
                <el-input :model-value="fmt2(hoursOf(r)) + 'h'" disabled style="width: 100px" />
              </el-form-item>
              <el-form-item label="转休时长">
                <el-input :model-value="fmt2(convertedOf(r, entryMode)) + 'h'" disabled style="width: 100px" />
              </el-form-item>
              <el-form-item label="打卡时间" required>
                <el-time-picker
                  v-model="r.clockInTime"
                  format="HH:mm"
                  value-format="HH:mm"
                  placeholder="20:30"
                  clearable
                  style="width: 110px"
                />
              </el-form-item>
              <el-form-item label="备注" class="grow-item">
                <el-input v-model="r.remark" placeholder="选填" style="width: 100%" />
              </el-form-item>
              <el-form-item>
                <el-button :icon="Delete" type="danger" text :disabled="rows.length <= 1" @click="removeRow(i)" />
              </el-form-item>
            </el-form>
          </div>
        </template>

        <!-- 其他转休：每行一行 -->
        <template v-else>
          <div class="row-item" v-for="(r, i) in rows" :key="i">
            <el-form :inline="true" class="entry-form">
              <el-form-item label="转休时长" required>
                <el-input-number
                  v-model="r.convertedHours"
                  :precision="2"
                  :min="0"
                  :step="0.5"
                  :controls="false"
                  placeholder="0.00"
                  style="width: 140px"
                />
                <span class="unit">h</span>
              </el-form-item>
              <el-form-item label="备注" required>
                <el-input v-model="r.remark" placeholder="必填" style="width: 320px" />
              </el-form-item>
              <el-form-item>
                <el-button :icon="Delete" type="danger" text :disabled="rows.length <= 1" @click="removeRow(i)" />
              </el-form-item>
            </el-form>
          </div>
        </template>
      </div>

      <div class="add-row">
        <el-button :icon="Plus" text type="primary" @click="addRow">添加一行</el-button>
        <span class="hint">
          {{ isOther(entryMode) ? '同一员工可连续添加多行其他转休；转休时长与备注均为必填' : '同一员工可连续追加多天加班；类型与比例按所选日期自动判定' }}
        </span>
      </div>

      <template #footer>
        <el-button @click="showEntry = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEntry">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="showEdit" width="900px" :close-on-click-modal="false">
      <template #header>
        <div class="dlg-head">
          <span class="dlg-title">编辑调休时长</span>
          <div class="dlg-tools">
            <span class="dlg-label">员工姓名</span>
            <el-select
              v-if="!isEmployee()"
              v-model="editUserId"
              filterable
              placeholder="输入姓名搜索"
              style="width: 180px"
            >
              <el-option
                v-for="u in users"
                :key="u.id"
                :value="u.id"
                :label="`${u.name}（${u.department || '未分组'}）`"
              />
            </el-select>
            <el-input v-else :model-value="auth.user?.name || ''" disabled style="width: 180px" />
            <el-radio-group v-model="editMode" size="small" @change="onEditModeChange">
              <el-radio-button :value="MODE_OVERTIME">加班转休</el-radio-button>
              <el-radio-button :value="MODE_OTHER">其他转休</el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>

      <div class="pink-box">
        <!-- 加班转休 -->
        <template v-if="!isOther(editMode)">
          <!-- 第一行：加班日期 | 类型 | 比例 | 开始 | 结束 -->
          <el-form :inline="true" class="entry-form">
            <el-form-item label="加班日期" required>
              <el-date-picker
                v-model="editRow.date"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择日期"
                style="width: 132px"
                @change="onDateChange(editRow)"
              />
            </el-form-item>
            <el-form-item label="类型">
              <el-input :model-value="editRow.dayType ? labelOf(editRow.dayType) : '-'" disabled style="width: 140px" />
            </el-form-item>
            <el-form-item label="比例">
              <el-input
                :model-value="editRow.ratio != null && editRow.ratio !== '' ? Number(editRow.ratio).toFixed(1) : '-'"
                disabled
                style="width: 72px"
              />
            </el-form-item>
            <el-form-item label="开始" required>
              <el-time-select
                v-model="editRow.startTime"
                start="00:00"
                step="00:30"
                end="23:30"
                placeholder="18:30"
                style="width: 110px"
              />
            </el-form-item>
            <el-form-item label="结束" required>
              <el-time-select
                v-model="editRow.endTime"
                start="00:00"
                step="00:30"
                end="23:30"
                placeholder="20:30"
                style="width: 110px"
              />
            </el-form-item>
          </el-form>
          <!-- 第二行：加班时长 | 转休时长 | 打卡时间 | 备注 -->
          <el-form :inline="true" class="entry-form">
            <el-form-item label="加班时长">
              <el-input :model-value="fmt2(hoursOf(editRow)) + 'h'" disabled style="width: 100px" />
            </el-form-item>
            <el-form-item label="转休时长">
              <el-input :model-value="fmt2(convertedOf(editRow, editMode)) + 'h'" disabled style="width: 100px" />
            </el-form-item>
            <el-form-item label="打卡时间" required>
              <el-time-picker
                v-model="editRow.clockInTime"
                format="HH:mm"
                value-format="HH:mm"
                placeholder="20:30"
                clearable
                style="width: 110px"
              />
            </el-form-item>
            <el-form-item label="备注" class="grow-item">
              <el-input v-model="editRow.remark" placeholder="选填" style="width: 100%" />
            </el-form-item>
          </el-form>
        </template>

        <!-- 其他转休 -->
        <template v-else>
          <el-form :inline="true" class="entry-form">
            <el-form-item label="转休时长" required>
              <el-input-number
                v-model="editRow.convertedHours"
                :precision="2"
                :min="0"
                :step="0.5"
                :controls="false"
                placeholder="0.00"
                style="width: 140px"
              />
              <span class="unit">h</span>
            </el-form-item>
            <el-form-item label="备注" required>
              <el-input v-model="editRow.remark" placeholder="必填" style="width: 320px" />
            </el-form-item>
          </el-form>
        </template>
      </div>

      <template #footer>
        <el-button @click="showEdit = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 我的加班趋势（员工视角） -->
    <el-dialog v-model="showTrend" title="我的加班趋势" width="820px" @closed="disposeTrend">
      <div class="trend-bar">
        <el-date-picker
          v-model="trendFrom"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="开始日期"
          clearable
          style="width: 150px"
        />
        <span class="trend-sep">~</span>
        <el-date-picker
          v-model="trendTo"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="结束日期"
          clearable
          style="width: 150px"
        />
        <el-button type="primary" :loading="trendLoading" @click="loadTrend">查询</el-button>
        <span class="hint">按加班日期所在月份统计转休时长；起止日期留空表示不限</span>
      </div>
      <el-empty v-if="trendEmpty" description="所选时间范围内暂无加班记录" />
      <div v-else ref="trendRef" v-loading="trendLoading" style="height: 340px"></div>
      <template #footer>
        <el-button @click="showTrend = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 导入弹窗 -->
    <el-dialog v-model="showImport" title="导入调休时长记录" width="600px" :close-on-click-modal="false">
      <div style="margin-bottom: 12px; color: var(--text-sub); font-size: 13px">
        请先
        <el-button link type="primary" @click="downloadTemplate">下载模板</el-button>
        ，按模板填写后上传（列顺序：员工姓名/工号、加班日期、开始时间、结束时间、备注；时间以 30 分钟为一跳）。
      </div>
      <el-upload
        v-model:file-list="uploadFiles"
        :auto-upload="false"
        :limit="1"
        accept=".xlsx,.xls"
      >
        <template #trigger>
          <el-button>选择文件</el-button>
        </template>
        <el-button style="margin-left: 10px" type="primary" :loading="importing" @click="submitImport">
          开始导入
        </el-button>
        <template #tip>
          <div style="color: var(--text-light); font-size: 12px; margin-top: 6px">
            仅支持 .xlsx / .xls 文件，单次最多一个文件
          </div>
        </template>
      </el-upload>

      <div v-if="importResult" style="margin-top: 16px">
        <el-alert
          :type="importResult.failed ? 'warning' : 'success'"
          :closable="false"
          show-icon
          :title="`导入完成：成功 ${importResult.success || 0} 条，失败 ${importResult.failed || 0} 条`"
        />
        <div v-if="importResult.errors && importResult.errors.length" style="margin-top: 10px">
          <div style="font-size: 13px; margin-bottom: 6px">错误明细：</div>
          <div class="err-list">
            <div v-for="(e, i) in importResult.errors" :key="i" class="err-line">{{ e }}</div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="showImport = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.btn-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
}
.search-form {
  margin-bottom: 4px;
}
/* 录入区每行严格一行展示，不换行：粉色框内加班转休固定两行、其他转休固定一行 */
.entry-form {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
}
.entry-form :deep(.el-form-item) {
  margin-bottom: 10px;
  margin-right: 8px;
  flex-shrink: 0;
}
.entry-form :deep(.el-form-item__label) {
  padding-right: 4px;
}
/* 备注占满剩余宽度 */
.entry-form :deep(.el-form-item.grow-item) {
  flex: 1 1 auto;
  min-width: 150px;
}
.unit {
  margin-left: 6px;
  color: var(--text-light);
  font-size: 13px;
}
/* 我的加班趋势：日期区间行 */
.trend-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.trend-sep {
  color: var(--text-light);
}

/* 弹窗标题行：标题 + 员工选择 + 模式切换 */
.dlg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding-right: 24px;
}
.dlg-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text, #4a2f3c);
}
.dlg-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dlg-label {
  font-size: 13px;
  color: var(--text-sub, #9a7287);
  white-space: nowrap;
}

/* 粉色线框：录入区域 */
.pink-box {
  border: 1px solid #ffb3d1;
  border-radius: 10px;
  padding: 12px;
  background: #fffafc;
  max-height: 52vh;
  overflow-y: auto;
}
.row-item {
  padding: 8px 10px;
  margin-bottom: 10px;
  border: 1px dashed #ffd0e2;
  border-radius: 8px;
  background: #fff;
}
.row-item:last-child {
  margin-bottom: 0;
}
.add-row {
  margin-top: 10px;
  display: flex;
  align-items: center;
}
.hint {
  color: var(--text-light);
  font-size: 12px;
  margin-left: 8px;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}
.total-text {
  color: var(--text-light);
  font-size: 13px;
}
.err-list {
  max-height: 180px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  background: #fff;
}
.err-line {
  font-size: 12px;
  color: var(--danger, #e55353);
  line-height: 1.9;
}
</style>

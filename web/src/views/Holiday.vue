<script setup>
/**
 * 节假日日历（路由 /holiday，权限码 holiday:view；刷新按钮需 holiday:manage）
 * 职责：
 *  - 年度视图：12 张月份卡片，展示每月法定节假日/工作日/休息日天数与人工调整天数。
 *  - 月份弹窗：点卡片打开该月日历，逐日下拉切换类型，切换后先本地暂存（pending），点「保存更改」才提交。
 *  - 刷新：拉取官方节假日数据；若存在人工变更会提示冲突，可强制覆盖。
 * 日期类型直接决定加班折算系数（法定工作日/补班日 0.5，法定休息日（含法定节假日）1），故变更属高风险操作，需二次确认。
 */
import { ref, computed, onMounted, h } from 'vue';
import { holidayApi } from '../api';
import { ok, err } from '../toast';
import { ElMessageBox, ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';

/** 两种日期类型：中文名 + 颜色（原 LEGAL 法定节假日已并入 RESTDAY 法定休息日，两者折算系数均为 1） */
const TYPE_META = {
  WORKDAY: { label: '法定工作日', color: '#409eff', dot: '#409eff' },
  RESTDAY: { label: '法定休息日', color: '#67c23a', dot: '#67c23a' },
};
const TYPE_OPTIONS = [
  { value: 'WORKDAY', label: '法定工作日', dot: '#409eff' },
  { value: 'RESTDAY', label: '法定休息日', dot: '#67c23a' },
];
const WEEK_HEADERS = ['一', '二', '三', '四', '五', '六', '日'];

const year = ref(String(new Date().getFullYear())); // 年份选择器值为 'YYYY'
const months = ref([]);      // 12 个月份卡片统计
const loading = ref(false);
const refreshing = ref(false); // 刷新官方数据中
const saving = ref(false);     // 保存月份变更中

// ---------- 月份详情弹窗 ----------
const dialogVisible = ref(false);
const grid = ref({ month: '', label: '', days: [] }); // 当前打开月份的完整日历
/** 待保存的变更：{ '2026-01-04': 'WORKDAY' }，点保存前只体现在本地 */
const pending = ref({});

/** 兜底：接口未返回或数据异常时给空数组，避免模板 v-for 报错 */
const safeDays = computed(() => (grid.value && grid.value.days) || []);

/** 当前展示的类型（含未保存的本地变更）：pending 优先于后端返回的 type */
function effType(day) {
  return pending.value[day.date] || day.type;
}
/** 日期类型中文名 */
function labelOf(day) {
  return (TYPE_META[effType(day)] || {}).label || effType(day);
}
/** 类型文字颜色 */
function colorOf(day) {
  return (TYPE_META[effType(day)] || {}).color || '#909399';
}
/** 右下角小圆点颜色 */
function dotOf(day) {
  return (TYPE_META[effType(day)] || {}).dot || '#c0c4cc';
}
/** 是否与官方类型不一致（即被人动过），用于打「人工」标记 */
function isManual(day) {
  return !!day.officialType && effType(day) !== day.officialType;
}

/** 本次实际发生变化的天数（排除非本月补位日期与改回原值的项） */
const changedCount = computed(
  () => safeDays.value.filter((d) => d.inMonth && pending.value[d.date] && pending.value[d.date] !== d.type).length,
);

// ---------- 数据加载 ----------
/** 加载年度概览：GET /api/holidays/year?year=YYYY */
async function loadYear() {
  loading.value = true;
  try {
    const res = await holidayApi.year(year.value);
    months.value = res.data || [];
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}

/** 打开某月详情：GET /api/holidays/month?month=YYYY-MM；每次打开都要清空上次的未保存变更 */
async function openMonth(month) {
  try {
    const res = await holidayApi.month(month);
    grid.value = res.data || { month, label: month, days: [] };
    pending.value = {};
    dialogVisible.value = true;
  } catch (e) {
    err(e.message);
  }
}

onMounted(loadYear);

// ---------- 单日类型变更 ----------
/**
 * 本地兜底的高风险文案（后端返回 warnings 时以后端为准）
 * 与后端一致：该日期是否高风险取决于「是否带有法定节假日名称」，而不再区分 LEGAL / RESTDAY。
 * @param {string} holidayName 该日期的法定节假日名称；后端已把原 LEGAL 归一为 RESTDAY，故此处只认 name
 */
function localWarning(holidayName) {
  return `该日期为国家法定节假日「${holidayName}」，变更可能影响历史数据与后续加班折算口径，是否继续？`;
}

/**
 * 下拉选择某天的类型：@command 触发
 * 仅当该日期带有法定节假日名称（day.name 非空）时算高风险，需要二次确认；
 * 确认后只写入 pending，等点「保存更改」才真正提交。
 * @param {object} day 日期格子
 * @param {'WORKDAY'|'RESTDAY'} type 目标类型
 */
async function onPick(day, type) {
  if (!day || !day.inMonth) return; // 补位的非本月日期不可编辑
  if (effType(day) === type) return; // 类型未变
  // 高风险判定改为与后端一致：该日期是否带有法定节假日名称（name 非空）
  const risk = !!day.name;
  if (risk) {
    const msg = localWarning(day.name);
    // 用 h() 拼 VNode：确认框需要两行不同颜色的文案，纯字符串无法着色
    const vnode = h('div', null, [
      h('div', null, `确认将 ${day.date} 从「${labelOf(day)}」变更为「${TYPE_META[type].label}」？`),
      h('div', { style: 'margin-top:8px;color:#e6a23c;line-height:1.6' }, msg),
    ]);
    try {
      await ElMessageBox.confirm(vnode, '高风险操作确认', {
        type: 'warning',
        confirmButtonText: '确认变更',
        cancelButtonText: '取消',
      });
    } catch {
      return; // 用户取消
    }
  }
  pending.value[day.date] = type;
}

/**
 * 保存本月变更：POST /api/holidays/month，只提交真正变化的日期
 * 后端返回 warnings（高风险提示）时用 alert 集中告知，不阻断保存结果
 */
async function saveChanges() {
  const changes = [];
  safeDays.value.forEach((d) => {
    const t = pending.value[d.date];
    if (d.inMonth && t && t !== d.type) changes.push({ date: d.date, type: t });
  });
  if (changes.length === 0) {
    ElMessage.warning('本次没有需要保存的修改');
    return;
  }
  saving.value = true;
  try {
    const res = await holidayApi.saveMonth(grid.value.month, changes);
    const data = res.data || {};
    if (data.warnings && data.warnings.length) {
      ElMessageBox.alert(data.warnings.join('<br/>'), '高风险变更提示', {
        type: 'warning',
        dangerouslyUseHTMLString: true,
        confirmButtonText: '我知道了',
      });
    }
    ok(`已保存 ${data.saved != null ? data.saved : changes.length} 天变更`);
    dialogVisible.value = false;
    await loadYear(); // 月份卡片上的天数统计会随之变化，必须重拉
  } catch (e) {
    err(e.message);
  } finally {
    saving.value = false;
  }
}

// ---------- 刷新 ----------
/**
 * 刷新当年官方数据：点击「刷新」按钮触发
 * 第一次不带 force；若后端返回 conflicts（存在人工变更），列出冲突日让用户确认后再强制覆盖。
 */
async function onRefresh() {
  refreshing.value = true;
  try {
    const res = await holidayApi.refresh('year', String(year.value), false);
    const data = res.data || {};
    if (data.refreshed) {
      ok(data.message || '刷新成功');
      await loadYear();
      return;
    }
    const conflicts = data.conflicts || [];
    const vnode = h('div', { style: 'max-height:260px;overflow:auto' }, [
      h('div', { style: 'margin-bottom:6px' }, `以下 ${conflicts.length} 天存在人工变更：`),
      ...conflicts.map((c) =>
        h('div', { style: 'color:#e6a23c;line-height:1.8' }, `${c.date}（${c.currentTypeLabel || c.currentType}）`),
      ),
      h('div', { style: 'margin-top:8px' }, '是否强制刷新并覆盖？'),
    ]);
    try {
      await ElMessageBox.confirm(vnode, '刷新冲突确认', {
        type: 'warning',
        confirmButtonText: '强制刷新',
        cancelButtonText: '取消',
      });
    } catch {
      return;
    }
    const forced = await holidayApi.refresh('year', String(year.value), true);
    const d2 = forced.data || {};
    ok(d2.message || `已强制刷新，覆盖 ${(d2.conflicts || conflicts).length} 天人工变更`);
    await loadYear();
  } catch (e) {
    err(e.message);
  } finally {
    refreshing.value = false;
  }
}
</script>

<template>
  <div class="hd-page">
    <div class="hd-header">
      <div>
        <div class="hd-title">节假日日历</div>
        <div class="hd-sub">
          维护法定节假日、调休补班日与休息日；判定优先级：人工维护 &gt; 系统官方规则 &gt; 周末默认休息日。
          日期类型决定加班折算系数（法定工作日 0.5，法定休息日（含法定节假日）1）
        </div>
      </div>
      <div class="hd-actions">
        <el-date-picker
          v-model="year"
          type="year"
          value-format="YYYY"
          placeholder="选择年份"
          style="width: 130px"
          @change="loadYear"
        />
        <el-button
          v-permission="'holiday:manage'"
          type="primary"
          :icon="Refresh"
          :loading="refreshing"
          @click="onRefresh"
        >
          刷新
        </el-button>
      </div>
    </div>

    <div v-loading="loading" class="hd-grid">
      <div v-for="m in months" :key="m.month" class="hd-card" @click="openMonth(m.month)">
        <div class="hd-card-title">{{ m.label }}</div>
        <div class="hd-stats">
          <div class="hd-stat">
            <span class="hd-dot" style="background: #409eff"></span>
            <span class="hd-stat-name">法定工作日</span>
            <b class="hd-stat-num" style="color: #409eff">{{ m.workdayCount }}</b>
          </div>
          <div class="hd-stat">
            <span class="hd-dot" style="background: #67c23a"></span>
            <span class="hd-stat-name">法定休息日</span>
            <b class="hd-stat-num" style="color: #67c23a">{{ m.restCount }}</b>
          </div>
        </div>
        <div v-if="m.manualCount" class="hd-manual">已人工调整 {{ m.manualCount }} 天</div>
      </div>
      <div v-if="!loading && months.length === 0" class="hd-empty">暂无数据</div>
    </div>

    <el-dialog v-model="dialogVisible" width="900px" destroy-on-close class="hd-dialog">
      <template #header>
        <div class="hd-dlg-header">
          <span class="hd-dlg-title">{{ grid.label || '节假日维护' }}</span>
          <span class="hd-legend">
            <span v-for="opt in TYPE_OPTIONS" :key="opt.value" class="hd-legend-item">
              <i class="hd-dot" :style="{ background: opt.dot }"></i>{{ opt.label }}
            </span>
          </span>
        </div>
      </template>

      <div class="hd-calendar">
        <div v-for="w in WEEK_HEADERS" :key="w" class="hd-week">{{ w }}</div>
        <el-dropdown
          v-for="d in safeDays"
          :key="d.date"
          trigger="click"
          :disabled="!d.inMonth"
          @command="(cmd) => onPick(d, cmd)"
        >
          <div class="hd-cell" :class="{ 'is-out': !d.inMonth }">
            <div class="hd-cell-day" :class="{ 'is-dim': !d.inMonth }">{{ d.day }}</div>
            <div class="hd-cell-type" :style="{ color: colorOf(d) }">{{ d.inMonth ? labelOf(d) : '' }}</div>
            <div class="hd-cell-name">{{ d.name || '' }}</div>
            <span v-if="d.inMonth" class="hd-dot hd-cell-dot" :style="{ background: dotOf(d) }"></span>
            <span v-if="isManual(d)" class="hd-cell-manual">人工</span>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="opt in TYPE_OPTIONS"
                :key="opt.value"
                :command="opt.value"
                :disabled="opt.value === effType(d)"
              >
                <span class="hd-dot" :style="{ background: opt.dot }"></span>
                {{ opt.label }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <template #footer>
        <div class="hd-footer">
          <span class="hd-count">本次共修改 <b>{{ changedCount }}</b> 天</span>
          <span>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="saving" @click="saveChanges">保存更改</el-button>
          </span>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hd-page {
  padding: 4px 2px;
}
.hd-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.hd-title {
  font-size: 20px;
  font-weight: 700;
  color: #303133;
}
.hd-sub {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
  line-height: 1.6;
  max-width: 780px;
}
.hd-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 12 个月份卡片：4 列网格 */
.hd-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  min-height: 120px;
}
.hd-card {
  position: relative;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  padding: 14px 14px 12px;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
}
.hd-card:hover {
  border-color: #ffd0de;
  box-shadow: 0 6px 18px rgba(241, 100, 143, 0.25);
  transform: translateY(-2px);
}
.hd-card-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
}
.hd-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 10px;
}
.hd-stat {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #606266;
}
.hd-stat-name {
  margin-right: 6px;
}
.hd-stat-num {
  font-size: 14px;
}
.hd-manual {
  margin-top: 10px;
  display: inline-block;
  padding: 2px 8px;
  font-size: 12px;
  color: #e6a23c;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 10px;
}
.hd-empty {
  grid-column: 1 / -1;
  text-align: center;
  color: #909399;
  padding: 40px 0;
}

.hd-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

/* 弹窗 */
.hd-dlg-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.hd-dlg-title {
  font-size: 17px;
  font-weight: 700;
  color: #303133;
}
.hd-legend {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: #606266;
  font-weight: 400;
}
.hd-legend-item {
  display: inline-flex;
  align-items: center;
}

.hd-calendar {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}
.hd-week {
  text-align: center;
  font-size: 13px;
  font-weight: 600;
  color: #909399;
  padding: 4px 0 6px;
}
.hd-cell {
  position: relative;
  min-height: 74px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 6px 8px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.hd-cell:hover {
  border-color: #ffb3c9;
  box-shadow: 0 4px 12px rgba(241, 100, 143, 0.18);
}
.hd-cell.is-out {
  background: #fafafa;
  color: #c0c4cc;
  cursor: not-allowed;
  box-shadow: none;
  border-color: #f2f2f2;
}
.hd-cell.is-out:hover {
  box-shadow: none;
  border-color: #f2f2f2;
}
.hd-cell-day {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.hd-cell-day.is-dim {
  color: #c0c4cc;
  font-weight: 500;
}
.hd-cell-type {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.3;
}
.hd-cell-name {
  margin-top: 2px;
  font-size: 11px;
  color: #909399;
  line-height: 1.3;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.hd-cell-dot {
  position: absolute;
  right: 7px;
  bottom: 7px;
  margin: 0;
}
.hd-cell-manual {
  position: absolute;
  right: 7px;
  top: 6px;
  font-size: 10px;
  color: #e6a23c;
  border: 1px solid #faecd8;
  background: #fdf6ec;
  border-radius: 4px;
  padding: 0 3px;
}

.hd-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hd-count {
  font-size: 13px;
  color: #606266;
}
.hd-count b {
  color: #f1648f;
  font-size: 15px;
}

@media (max-width: 1200px) {
  .hd-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .hd-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

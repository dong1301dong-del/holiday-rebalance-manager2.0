<script setup>
/**
 * 全员调休查看（路由 /all-staff-leave，权限码 allStaffLeave:view）
 * 职责：按姓名/部门/日期范围汇总每位员工的调休录入、使用与余额，并支持导出。
 * 表格里每行的「加班趋势」是一条手绘迷你折线（近 12 个月加班时长），点击可打开月度统计弹窗。
 * 弹窗顶部支持按起始/结束日期（YYYY-MM-DD）筛选，折线图与月份明细表同步按所选范围截断，
 * 空月保留 0 值以体现连续性。
 *
 * 口径说明：日期范围按发生日期筛选（加班看加班日期、调休看使用日期），
 * 余额为区间内净增额；趋势数据以结束日期（留空则为当月）为终点的近 12 个月，不受开始日期影响。
 * 列表已排除 ADMIN / CLERK（后端处理）。
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { Search, Refresh, Download } from '@element-plus/icons-vue';
import * as echarts from 'echarts';
import { reportApi, userApi } from '../api';
import { ok, warn, err as errToast } from '../toast';

// ---------------- 查询条件 ----------------
const search = reactive({ name: '', department: '', range: null }); // range 为 [from, to]
const list = ref([]);
const loading = ref(false);
const exporting = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);

/** 现有部门列表（由用户列表去重得到） */
const departments = ref([]);

/** 加载部门下拉：GET /api/users 后去重去空并排序；无权限时降级为只有「全部部门」 */
async function loadDepartments() {
  try {
    const res = await userApi.list();
    const set = [];
    (res.data || []).forEach((u) => {
      if (u.status === 'DELETED') return;
      const d = (u.department || '').trim();
      if (d && !set.includes(d)) set.push(d);
    });
    departments.value = set.sort();
  } catch { /* 无权限时忽略，下拉仅剩「全部部门」 */ }
}

/** 组装查询参数：把日期区间拆成 from/to，空值转 undefined */
function currentParams() {
  return {
    name: search.name || undefined,
    department: search.department || undefined,
    from: search.range?.[0] || undefined,
    to: search.range?.[1] || undefined,
    page: page.value,
    size: size.value,
  };
}

/** 加载列表：GET /api/report/all-staff-leave（后端分页） */
async function loadList() {
  loading.value = true;
  try {
    const res = await reportApi.allStaffLeave(currentParams());
    list.value = res.data || [];
    total.value = res.total || 0;
    // 末页数据被删空时自动回退一页
    if (total.value > 0 && list.value.length === 0 && page.value > 1) {
      page.value -= 1;
      loadList();
      return;
    }
  } catch (e) {
    errToast(e.message);
  } finally {
    loading.value = false;
  }
}

/** 点击「搜索」或姓名框回车时触发 */
function onSearch() {
  page.value = 1;
  loadList();
}
/** 点击「重置」时触发：清空姓名、部门与日期区间 */
function onReset() {
  search.name = '';
  search.department = '';
  search.range = null;
  page.value = 1;
  loadList();
}
/** 翻页：@current-change 触发 */
function onPageChange(p) {
  page.value = p;
  loadList();
}
/** 改每页条数：@size-change 触发；须回到第 1 页 */
function onSizeChange(s) {
  size.value = s;
  page.value = 1;
  loadList();
}
/** 跨页连续序号 */
const indexOf = (i) => (page.value - 1) * size.value + i + 1;

/** 导出：GET /api/report/all-staff-leave/export；无数据时后端返回明确提示，降级为 warning */
async function onExport() {
  exporting.value = true;
  try {
    await reportApi.exportAllStaffLeave({
      name: search.name || undefined,
      department: search.department || undefined,
      from: search.range?.[0] || undefined,
      to: search.range?.[1] || undefined,
    });
    ok('导出成功');
  } catch (e) {
    warn(e.message || '当前查询结果无数据可导出');
  } finally {
    exporting.value = false;
  }
}

// ---------------- 展示辅助 ----------------
/** 金额/时长统一两位小数 */
const fmt2 = (n) => Number(n || 0).toFixed(2);
/** 取姓名首字作为头像文字 */
const surname = (name) => (name || '?').trim().charAt(0) || '?';

/**
 * 迷你折线 polyline 点位：把 trendMonths 映射到 100×26 的 SVG 画布
 * 纵坐标按 [min(含0), max] 区间归一化；span 为 0（全部等值）时兜底为 1 防止除零
 * @param {object} row 列表行，含 trendMonths: [{ month, hours }]
 * @returns {string} polyline 的 points 属性
 */
function sparkPoints(row) {
  const pts = row?.trendMonths || [];
  if (!pts.length) return '';
  const w = 100;
  const h = 26;
  const vals = pts.map((p) => Number(p.hours || 0));
  const max = Math.max(...vals);
  const min = Math.min(...vals, 0); // 以 0 为基线，负值也能正常显示
  const span = max - min || 1;
  const step = pts.length > 1 ? w / (pts.length - 1) : 0;
  // SVG 的 y 轴向下，所以用 h 减去归一化后的高度
  return vals
    .map((v, i) => `${(i * step).toFixed(2)},${(h - ((v - min) / span) * h).toFixed(2)}`)
    .join(' ');
}
/** 是否有趋势数据可画 */
const hasTrend = (row) => (row?.trendMonths || []).length > 0;

// ---------------- 月度加班时长统计弹窗 ----------------
const showDetail = ref(false);
const detailRow = ref(null);  // 当前查看的员工行
const chartRef = ref(null);
let chart = null; // echarts 实例，不参与响应式

// 弹窗内的日期范围筛选（YYYY-MM-DD，精确到日；留空表示不限）
const trendFrom = ref('');
const trendTo = ref('');

/**
 * 渲染月度加班时长折线图（带渐变面积）
 * 第三个参数 true = notMerge，切换员工时彻底替换旧 option
 */
function renderDetailChart() {
  const el = chartRef.value;
  if (!el) return;
  // 筛完范围后图表可能在「暂无数据 / 图表」之间切换并重建 DOM，
  // 此时旧实例指向已卸载的节点，必须销毁重建，否则图表画不出来
  if (chart && chart.getDom() !== el) {
    chart.dispose();
    chart = null;
  }
  if (!chart) chart = echarts.init(el);
  const months = detailMonths.value;
  chart.setOption(
    {
      grid: { left: 48, right: 24, top: 40, bottom: 48 },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0];
          return `${detailRow.value?.name || ''}<br/>${p.name}<br/>加班时长：${Number(p.value || 0).toFixed(2)} 小时`;
        },
      },
      xAxis: {
        type: 'category',
        data: months.map((m) => m.month),
        axisLine: { lineStyle: { color: '#ffd9e8' } },
        axisLabel: { color: '#9a7287' },
      },
      yAxis: {
        type: 'value',
        name: '小时',
        nameTextStyle: { color: '#9a7287' },
        axisLabel: { color: '#9a7287' },
        splitLine: { lineStyle: { color: '#fff0f6' } },
      },
      series: [
        {
          name: '加班时长',
          type: 'line',
          smooth: true,
          symbolSize: 7,
          data: months.map((m) => Number(m.hours || 0)),
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

/**
 * 点击某行的迷你折线：打开弹窗并渲染图表
 * nextTick 等待弹窗 DOM 挂载完成，否则容器没有尺寸会导致图表渲染为 0 高
 * @param {object} row 列表行
 */
async function openDetail(row) {
  detailRow.value = row;
  // 每次打开都清空范围筛选，默认展示完整的近 12 个月
  trendFrom.value = '';
  trendTo.value = '';
  showDetail.value = true;
  await nextTick();
  renderDetailChart();
}

/**
 * 应用弹窗内的日期范围筛选
 * 后端只提供近 12 个月的月度聚合，这里按 YYYY-MM 本地截断即可，无需重新请求
 */
async function applyTrendFilter() {
  if (trendFrom.value && trendTo.value && trendFrom.value > trendTo.value) {
    errToast('起始日期不能晚于结束日期');
    return;
  }
  await nextTick();
  renderDetailChart();
}
/** 重置筛选：恢复展示完整的近 12 个月 */
function resetTrendFilter() {
  trendFrom.value = '';
  trendTo.value = '';
  applyTrendFilter();
}

/** 窗口尺寸变化时同步图表宽度 */
function onResize() {
  if (chart) chart.resize();
}

onMounted(() => {
  loadList();
  loadDepartments();
  window.addEventListener('resize', onResize);
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize);
  // 销毁实例，避免 DOM 移除后残留监听
  if (chart) {
    chart.dispose();
    chart = null;
  }
});

/** 弹窗内该员工完整的近 12 个月数据 */
const trendMonthsAll = computed(() => detailRow.value?.trendMonths || []);

/**
 * 按弹窗内所选日期范围截断月份数据
 * 精确到日的范围按 YYYY-MM 比较（落在区间内的整月都算），0 值月份继续保留以体现连续性
 */
const detailMonths = computed(() => {
  const all = trendMonthsAll.value;
  const from = trendFrom.value ? String(trendFrom.value).slice(0, 7) : '';
  const to = trendTo.value ? String(trendTo.value).slice(0, 7) : '';
  if (!from && !to) return all;
  return all.filter((m) => (!from || m.month >= from) && (!to || m.month <= to));
});
/** 统计区间文案：未筛选时说明是近 12 个月，已筛选时显示所选范围 */
const detailRangeText = computed(() => {
  if (!trendFrom.value && !trendTo.value) {
    return `以${search.range?.[1] || '当月'}为终点的近 12 个月`;
  }
  return `${trendFrom.value || '不限'} ~ ${trendTo.value || '不限'}`;
});
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">全员调休查看</div>
        <div class="page-sub">
          汇总每位员工的调休录入、使用与余额情况；日期范围按发生日期筛选（加班看加班日期、调休看使用日期），余额为区间内净增额；点击「加班趋势」可查看该员工逐月加班时长
        </div>
      </div>
      <div style="display: flex; gap: 8px; flex-wrap: wrap">
        <el-button :icon="Download" :loading="exporting" @click="onExport">导出</el-button>
      </div>
    </div>

    <div class="card">
      <!-- 搜索区 -->
      <el-form :inline="true" :model="search" class="search-form" @submit.prevent>
        <el-form-item label="姓名">
          <el-input
            v-model="search.name"
            placeholder="请输入员工姓名"
            clearable
            style="width: 170px"
            :prefix-icon="Search"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="search.department" placeholder="全部部门" clearable style="width: 170px">
            <el-option label="全部部门" value="" />
            <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期范围">
          <el-date-picker
            v-model="search.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="~"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 250px"
            clearable
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="onSearch">搜索</el-button>
          <el-button :icon="Refresh" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table v-loading="loading" :data="list" border stripe size="default" style="width: 100%">
        <el-table-column type="index" label="序号" width="70" align="center">
          <template #default="{ $index }">{{ indexOf($index) }}</template>
        </el-table-column>

        <el-table-column label="员工姓名" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="user-cell">
              <span class="avatar">{{ surname(row.name) }}</span>
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="部门" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>

        <el-table-column label="当前录入调休时长" min-width="150" align="center">
          <template #default="{ row }">{{ fmt2(row.earnedHours) }}h</template>
        </el-table-column>

        <el-table-column label="已使用调休时长" min-width="140" align="center">
          <template #default="{ row }">{{ fmt2(row.usedHours) }}h</template>
        </el-table-column>

        <el-table-column label="总计调休余额" min-width="140" align="center">
          <template #default="{ row }">
            <span class="balance">{{ fmt2(row.balance) }}h</span>
          </template>
        </el-table-column>

        <el-table-column label="加班趋势" width="160" align="center">
          <template #header>
            <el-tooltip
              content="展示截至所选结束日期（留空则为当月）的近 12 个月加班时长，不受开始日期影响"
              placement="top"
            >
              <span>加班趋势 ⓘ</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <div v-if="hasTrend(row)" class="spark" @click="openDetail(row)">
              <svg viewBox="0 0 100 26" preserveAspectRatio="none" class="spark-svg">
                <polyline :points="sparkPoints(row)" fill="none" stroke="#ff6fa5" stroke-width="1.6" />
              </svg>
              <span class="spark-tip">查看</span>
            </div>
            <span v-else style="color: var(--text-light)">-</span>
          </template>
        </el-table-column>

        <template #empty>
          <div style="padding: 30px; color: var(--text-light)">暂无数据</div>
        </template>
      </el-table>

      <!-- 分页 -->
      <div class="pager">
        <span class="total-text">共 {{ total }} 条</span>
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
      </div>
    </div>

    <!-- 月度加班时长统计弹窗 -->
    <el-dialog
      v-model="showDetail"
      :title="`${detailRow?.name || ''} - 月度加班时长统计`"
      width="860px"
      :close-on-click-modal="false"
    >
      <div v-if="!trendMonthsAll.length" class="empty-box">暂无数据</div>
      <template v-else>
        <div class="trend-filter">
          <span class="filter-label">起始日期</span>
          <el-date-picker
            v-model="trendFrom"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="起始日期"
            clearable
            style="width: 165px"
          />
          <span class="filter-label">结束日期</span>
          <el-date-picker
            v-model="trendTo"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="结束日期"
            clearable
            style="width: 165px"
          />
          <el-button type="primary" :icon="Search" @click="applyTrendFilter">搜索</el-button>
          <el-button :icon="Refresh" @click="resetTrendFilter">重置</el-button>
        </div>

        <div class="chart-hint">
          统计区间：{{ detailRangeText }}（共 {{ detailMonths.length }} 个月）
        </div>

        <div v-if="!detailMonths.length" class="empty-box">所选日期范围内暂无数据</div>
        <div v-else ref="chartRef" class="detail-chart"></div>
      </template>
      <template #footer>
        <el-button @click="showDetail = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.search-form {
  margin-bottom: 4px;
}
.user-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.avatar {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffb8d4, #ff6fa5);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.balance {
  color: var(--primary);
  font-weight: 700;
}
.spark {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.spark-svg {
  width: 96px;
  height: 26px;
}
.spark:hover .spark-svg {
  filter: drop-shadow(0 0 4px rgba(255, 111, 165, 0.6));
}
.spark-tip {
  font-size: 12px;
  color: var(--primary);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 14px;
}
.total-text {
  color: var(--text-light);
  font-size: 13px;
}
.trend-filter {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.filter-label {
  font-size: 13px;
  color: var(--text-light);
}
.chart-hint {
  font-size: 12px;
  color: var(--text-light);
  margin-bottom: 4px;
}
.detail-chart {
  width: 100%;
  height: 320px;
}
.empty-box {
  padding: 40px 0;
  text-align: center;
  color: var(--text-light);
}
.table .num {
  text-align: right;
}
.total-row td {
  background: var(--primary-bg, #fff0f6);
  font-weight: 700;
}
</style>

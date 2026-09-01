<script setup>
/**
 * 工作台（路由 /dashboard，权限码 dashboard:view，登录后默认落地页）
 * 职责：
 *  - 管理员/录入员（admin 模式）：三张统计卡 + 成员余额表；三张卡均可点击下钻到职位分布饼图、部门余额柱状图、透支名单。
 *  - 普通员工（self 模式）：只展示本人调休余额与温馨提示。
 * 模式判定：dashboard 接口要求 ADMIN/CLERK 角色，员工调用会失败，此时自动降级请求个人余额接口并切到 self 模式。
 */
import { ref, onMounted, onBeforeUnmount, computed, reactive, nextTick } from 'vue';
import * as echarts from 'echarts';
import { reportApi } from '../api';
import { auth } from '../store';
import { err } from '../toast';

const data = ref(null);         // admin 模式下的 dashboard 数据
const selfBalance = ref(null);  // self 模式下的个人余额
const mode = ref('admin');      // admin | self
const loading = ref(true);

const page = reactive({ page: 1, size: 10 }); // 成员余额表前端分页

/**
 * 加载工作台数据
 * 先试 GET /api/report/dashboard；失败（无权限）则退到 GET /api/report/balance 切 self 模式
 */
async function load() {
  loading.value = true;
  try {
    const res = await reportApi.dashboard();
    data.value = res.data;
    mode.value = 'admin';
  } catch {
    try {
      const r = await reportApi.balance();
      selfBalance.value = r.data;
      mode.value = 'self';
    } catch (e) {
      err(e.message);
    }
  } finally {
    loading.value = false;
  }
}
onMounted(load);

/** 窗口尺寸变化时，已打开的图表同步自适应（响应式布局要求） */
function onWindowResize() {
  if (pieChart) pieChart.resize();
  if (barChart) barChart.resize();
}
onMounted(() => window.addEventListener('resize', onWindowResize));

/** 按当前小时给出问候语 */
const greeting = computed(() => {
  const h = new Date().getHours();
  if (h < 6) return '夜深了';
  if (h < 9) return '早上好';
  if (h < 12) return '上午好';
  if (h < 14) return '中午好';
  if (h < 18) return '下午好';
  return '晚上好';
});

const users = computed(() => data.value?.users || []);
const total = computed(() => users.value.length);

// 前端分页：dashboard 接口一次性返回全部成员，切片即可，无需再请求后端
const pagedUsers = computed(() => {
  const start = (page.page - 1) * page.size;
  return users.value.slice(start, start + page.size);
});

// ---------------- 统计卡下钻弹窗 ----------------
/** 图表配色（粉色系，与整体视觉一致） */
const PINK = ['#ff8fbb', '#ffa0c9', '#ffb3d1', '#ffc2d9', '#ffd0e2', '#f9a7c6', '#f78fb3', '#e8608f'];

const showPie = ref(false);        // 职位分布饼图
const showBar = ref(false);        // 各部门余额柱状图
const showOverdraft = ref(false);  // 透支人员明细

const chartLoading = ref(false);
const positionData = ref([]);   // [{ name, value }] 职位 → 人数
const deptData = ref([]);       // [{ name, value }] 部门 → 余额
const overdraftData = ref([]);  // [{ id, name, department, balance, overdraft }]

const pieRef = ref(null);
const barRef = ref(null);
// echarts 实例存在普通变量里即可：不需要响应式，否则会引发不必要的依赖收集
let pieChart = null;
let barChart = null;

/** 饼图弹窗关闭：销毁实例，避免 DOM 移除后残留监听与内存泄漏 */
function onPieClosed() {
  if (pieChart) { pieChart.dispose(); pieChart = null; }
}
function onBarClosed() {
  if (barChart) { barChart.dispose(); barChart = null; }
}
onBeforeUnmount(() => {
  window.removeEventListener('resize', onWindowResize);
  onPieClosed();
  onBarClosed();
});

/**
 * 接口不可用时的兜底：用已加载的 dashboard 成员数据推导部门余额
 * 注意 dashboard 返回的 users 本身已排除 ADMIN / CLERK，所以这里无需再过滤
 */
const deptFallback = () => {
  const m = {};
  users.value.forEach((u) => {
    const k = u.department || '未分组';
    m[k] = (m[k] || 0) + Number(u.balance || 0);
  });
  return Object.entries(m).map(([name, v]) => ({ name, value: Number(v.toFixed(2)) }));
};
/** 兜底：从成员列表筛出余额为负的透支人员 */
const overdraftFallback = () =>
  users.value
    .filter((u) => Number(u.balance) < 0)
    .map((u) => ({ id: u.id, name: u.name, department: u.department, balance: u.balance }));

/**
 * 一次取回三张弹窗所需数据：职位分布 / 部门余额 / 透支名单（GET /api/report/dashboard/charts）
 * 失败时不弹错误阻断，改用本地兜底数据，保证弹窗仍可查看
 */
async function loadCharts() {
  chartLoading.value = true;
  try {
    const d = (await reportApi.dashboardCharts()).data || {};
    positionData.value = d.positionPie || [];
    deptData.value = d.departmentBar || [];
    overdraftData.value = d.overdraftList || [];
  } catch (e) {
    err(e.message);
    positionData.value = [];
    deptData.value = deptFallback();
    overdraftData.value = overdraftFallback();
  } finally {
    chartLoading.value = false;
  }
}

/** 点击「员工总数」卡片：拉数据 → 等 DOM 挂载 → 渲染饼图（nextTick 保证容器已有尺寸） */
async function openPie() {
  showPie.value = true;
  await loadCharts();
  await nextTick();
  renderPie();
}

/** 点击「全员可调休余额」卡片 */
async function openBar() {
  showBar.value = true;
  await loadCharts();
  await nextTick();
  renderBar();
}

/** 点击「透支人数」卡片：纯表格，无需渲染图表 */
async function openOverdraft() {
  showOverdraft.value = true;
  await loadCharts();
}

/**
 * 渲染职位分布饼图（环形）
 * 第三个参数 true 表示 notMerge，彻底替换旧 option，避免切换数据时残留上次的 series
 */
function renderPie() {
  if (!pieRef.value || !positionData.value.length) return;
  if (!pieChart) pieChart = echarts.init(pieRef.value);
  pieChart.setOption(
    {
      color: PINK,
      tooltip: { trigger: 'item', formatter: '{b}：{c} 人（{d}%）' },
      legend: { type: 'scroll', bottom: 0 },
      series: [
        {
          type: 'pie',
          radius: ['42%', '66%'],
          center: ['50%', '46%'],
          data: positionData.value,
          itemStyle: { borderColor: '#fff', borderWidth: 2 },
          label: { formatter: '{b}\n{c} 人' },
        },
      ],
    },
    true
  );
  // 弹窗从隐藏到显示时容器尺寸才最终确定，需手动 resize 一次
  pieChart.resize();
}

/** 渲染各部门调休余额柱状图；部门较多时把 x 轴标签旋转 30 度防重叠 */
function renderBar() {
  if (!barRef.value || !deptData.value.length) return;
  if (!barChart) barChart = echarts.init(barRef.value);
  const names = deptData.value.map((d) => d.name);
  barChart.setOption(
    {
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: (v) => `${v} h` },
      grid: { left: 12, right: 16, bottom: 8, top: 30, containLabel: true },
      xAxis: {
        type: 'category',
        data: names,
        axisLabel: { rotate: names.length > 6 ? 30 : 0, color: '#9a7287' },
      },
      yAxis: { type: 'value', name: '小时(h)', nameTextStyle: { color: '#9a7287' } },
      series: [
        {
          type: 'bar',
          data: deptData.value.map((d) => d.value),
          barMaxWidth: 42,
          itemStyle: { color: '#ff8fbb', borderRadius: [6, 6, 0, 0] },
          label: { show: true, position: 'top', color: '#c0356b' },
        },
      ],
    },
    true
  );
  barChart.resize();
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">{{ greeting }}，{{ auth.user?.name }} 💖</div>
        <div class="page-sub">欢迎使用调休管家，今天又是元气满满哦~</div>
      </div>
    </div>

    <div v-loading="loading" class="dash-wrap">
      <template v-if="mode === 'admin' && data">
        <div class="stat-grid">
          <div class="stat-card clickable" @click="openPie">
            <div class="icon pink">👥</div>
            <div class="label">员工总数</div>
            <div class="value">{{ data.userCount }}<span class="unit">人</span></div>
            <div class="foot">点击查看职位分布</div>
          </div>
          <div class="stat-card clickable" @click="openBar">
            <div class="icon pink">💗</div>
            <div class="label">全员可调休余额</div>
            <div class="value">{{ data.totalBalance }}<span class="unit">h</span></div>
            <div class="foot">点击查看各部门余额</div>
          </div>
          <div class="stat-card clickable" @click="openOverdraft">
            <div class="icon red">⚠️</div>
            <div class="label">透支人数</div>
            <div class="value">{{ data.overdraftCount }}<span class="unit">人</span></div>
            <div class="foot">点击查看透支人员</div>
          </div>
        </div>

        <div class="card">
          <div class="card-title">成员调休余额</div>
          <el-table :data="pagedUsers" border stripe style="width: 100%">
            <el-table-column label="序号" width="80" align="center">
              <template #default="{ $index }">{{ (page.page - 1) * page.size + $index + 1 }}</template>
            </el-table-column>
            <el-table-column prop="name" label="姓名" min-width="120" align="center" />
            <el-table-column prop="username" label="账号" min-width="140" align="center" />
            <el-table-column prop="department" label="部门" min-width="160" align="center">
              <template #default="{ row }">{{ row.department || '-' }}</template>
            </el-table-column>
            <el-table-column label="调休余额(h)" min-width="140" align="center">
              <template #default="{ row }">
                <span :class="['balance-val', { negative: Number(row.balance) < 0 }]">{{ row.balance }}</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="table-foot">
            <span class="total-text">共计 {{ total }} 条数据</span>
            <el-pagination
              v-model:current-page="page.page"
              v-model:page-size="page.size"
              :page-sizes="[10, 20, 50]"
              :total="total"
              layout="total, sizes, prev, pager, next, jumper"
            />
          </div>
        </div>
      </template>

      <template v-else-if="mode === 'self' && selfBalance">
        <div class="stat-grid single">
          <div class="stat-card">
            <div class="icon pink">💗</div>
            <div class="label">我的可调休余额</div>
            <div class="value" :class="{ negative: selfBalance.overdraft }">
              {{ selfBalance.balance }}<span class="unit">h</span>
            </div>
            <div class="foot">{{ selfBalance.overdraft ? '当前为透支状态，请尽快补回' : '状态正常' }}</div>
          </div>
        </div>
        <div class="card">
          <div class="card-title">温馨提示</div>
          <p class="tip">
            你可在「员工门户」查看本人的加班与调休使用记录。余额不足时会记为透支，记得及时通过加班补回哦～
          </p>
        </div>
      </template>
    </div>

    <!-- 职位分布饼图 -->
    <el-dialog v-model="showPie" title="职位分布" width="720px" @opened="renderPie" @closed="onPieClosed">
      <div v-loading="chartLoading" class="chart-box">
        <div v-if="!chartLoading && positionData.length === 0" class="chart-empty">暂无职位数据</div>
        <div v-show="positionData.length" ref="pieRef" class="chart"></div>
      </div>
      <template #footer>
        <el-button @click="showPie = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 各部门调休余额柱状图 -->
    <el-dialog v-model="showBar" title="各部门调休余额" width="860px" @opened="renderBar" @closed="onBarClosed">
      <div v-loading="chartLoading" class="chart-box">
        <div v-if="!chartLoading && deptData.length === 0" class="chart-empty">暂无部门余额数据</div>
        <div v-show="deptData.length" ref="barRef" class="chart"></div>
      </div>
      <template #footer>
        <el-button @click="showBar = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 透支人员列表 -->
    <el-dialog v-model="showOverdraft" title="透支人员明细" width="720px">
      <el-table v-loading="chartLoading" :data="overdraftData" border stripe size="default" max-height="440">
        <el-table-column label="序号" width="80" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="name" label="姓名" min-width="120" align="center" />
        <el-table-column prop="department" label="部门" min-width="180" align="center">
          <template #default="{ row }">{{ row.department || '-' }}</template>
        </el-table-column>
        <el-table-column label="透支余额(h)" min-width="140" align="center">
          <template #default="{ row }">
            <span class="balance-val negative">{{ row.balance }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div style="padding: 30px; color: #9a7287">暂无透支人员</div>
        </template>
      </el-table>
      <template #footer>
        <el-button @click="showOverdraft = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header { margin-bottom: 16px; }
.page-title { font-size: 20px; font-weight: 800; color: #c0356b; }
.page-sub { font-size: 13px; color: #9a7287; margin-top: 4px; }

.dash-wrap { min-height: 120px; }
/* 三张卡等分一行：minmax(0,1fr) 防止内容把列撑宽导致不等分 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}
.stat-grid.single { grid-template-columns: minmax(260px, 1fr); }
/* 窄屏一列放不下三张卡时自动折行，避免卡片被压扁 */
@media (max-width: 900px) {
  .stat-grid { grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); }
}
.stat-card {
  background: #fff; border-radius: 12px; padding: 18px; border: 1px solid #ffe6f1;
  box-shadow: 0 2px 10px rgba(255, 111, 165, 0.1);
}
.stat-card.clickable { cursor: pointer; transition: transform 0.15s, box-shadow 0.15s; }
.stat-card.clickable:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(255, 111, 165, 0.22);
}
.stat-card .icon {
  width: 40px; height: 40px; border-radius: 12px; display: flex; align-items: center;
  justify-content: center; font-size: 20px; margin-bottom: 10px;
}
.stat-card .icon.pink { background: #fff0f6; }
.stat-card .icon.red { background: #ffeef3; }
.stat-card .label { font-size: 13px; color: #9a7287; }
.stat-card .value { font-size: 26px; font-weight: 800; color: #ff6fa5; margin-top: 4px; }
.stat-card .value.negative { color: #f1648f; }
.stat-card .unit { font-size: 13px; font-weight: 600; margin-left: 2px; color: #c39ab2; }
.stat-card .foot { font-size: 12px; color: #c39ab2; margin-top: 6px; }

.card {
  background: #fff; border-radius: 12px; box-shadow: 0 2px 10px rgba(255, 111, 165, 0.1);
  padding: 18px; border: 1px solid #ffe6f1;
}
.card-title { font-size: 16px; font-weight: 700; color: #4a2f3c; margin-bottom: 12px; }

.balance-val { font-weight: 700; color: #ff6fa5; }
.balance-val.negative { color: #f1648f; }

.table-foot { display: flex; align-items: center; justify-content: space-between; padding-top: 12px; flex-wrap: wrap; gap: 8px; }
.total-text { font-size: 13px; color: #9a7287; }
.tip { color: #9a7287; line-height: 1.8; }

.chart-box { width: 100%; height: 400px; position: relative; }
.chart { width: 100%; height: 400px; }
.chart-empty {
  height: 400px; display: flex; align-items: center; justify-content: center;
  color: #9a7287; font-size: 14px;
}
</style>

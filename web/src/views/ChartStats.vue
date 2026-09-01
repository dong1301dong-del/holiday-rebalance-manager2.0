<script setup>
/**
 * 统计图展示（路由 /chart-stats，权限码 chartStats:view）
 * 职责：两个 Tab 的加班时长趋势图 ——
 *   ① 部门加班趋势：按月聚合，部门留空=全公司，默认近 12 个月，跨度上限 24 个月；
 *   ② 员工加班趋势：按日聚合，需选员工，默认近 30 天，跨度上限 365 天。
 * 跨度在前端先校验再请求，避免后端一次聚合过多数据。
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';
import { Search, Refresh } from '@element-plus/icons-vue';
import * as echarts from 'echarts';
import { reportApi, userApi } from '../api';
import { warn, err as errToast } from '../toast';

const activeTab = ref('dept'); // 'dept' | 'emp'

// ---------------- 日期工具 ----------------
/** Date → 'YYYY-MM'（getMonth() 返回 0-11，需 +1 后补零） */
function monthStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}
/** Date → 'YYYY-MM-DD' */
function dateStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
/**
 * 相对当前月偏移 delta 个月的 'YYYY-MM'
 * 先 setDate(1) 再 setMonth，避免 1 月 31 日减 1 个月溢出到上上月的问题
 * @param {number} delta 负数=往前，0=当月
 */
function shiftMonth(delta) {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() + delta);
  return monthStr(d);
}
/** 相对今天偏移 delta 天的 'YYYY-MM-DD' */
function shiftDay(delta) {
  const d = new Date();
  d.setDate(d.getDate() + delta);
  return dateStr(d);
}

// ---------------- 基础数据 ----------------
const departments = ref([]); // 部门下拉
const users = ref([]);       // 员工下拉

/**
 * 加载员工与部门基础数据：GET /api/users
 * 部门为空即代表全公司，所以基础数据一就绪就能直接查一次部门趋势作为首屏
 */
async function loadBase() {
  try {
    const res = await userApi.list();
    // 排除已删除人员，以及管理员/录入员：他们是系统账号，不参与加班/调休统计
    const arr = (res.data || []).filter(
      (u) => u.status !== 'DELETED' && !(u.roles || []).some((r) => r === 'ADMIN' || r === 'CLERK')
    );
    users.value = arr;
    const set = [];
    arr.forEach((u) => {
      const d = (u.department || '').trim();
      if (d && !set.includes(d)) set.push(d);
    });
    departments.value = set.sort();
    // 部门为空=全公司，因此基础数据就绪后即可直接查询一次
    queryDept();
  } catch { /* 无权限时忽略 */ }
}

// ---------------- 部门加班趋势 ----------------
const deptForm = reactive({
  department: '',               // 空=全公司
  fromMonth: shiftMonth(-11),    // 默认近 12 个月
  toMonth: shiftMonth(0),
});
const deptRows = ref([]);
const deptLoading = ref(false);
const deptChartRef = ref(null);
let deptChart = null;

/**
 * 两个年月之间相差的月数（含首尾）
 * @param {string} from 'YYYY-MM'
 * @param {string} to 'YYYY-MM'
 * @returns {number|null} 解析失败返回 null
 */
function monthSpan(from, to) {
  if (!from || !to) return null;
  const [y1, m1] = from.split('-').map(Number);
  const [y2, m2] = to.split('-').map(Number);
  if (!y1 || !y2) return null;
  return (y2 - y1) * 12 + (m2 - m1) + 1;
}

/** 查询部门趋势：POST /api/report/trend/department；校验通过后才发请求 */
async function queryDept() {
  if (!deptForm.fromMonth || !deptForm.toMonth) return warn('请选择起始年月与结束年月');
  const span = monthSpan(deptForm.fromMonth, deptForm.toMonth);
  if (span == null || span <= 0) return warn('结束年月不能早于起始年月');
  if (span > 24) return warn('时间跨度不能超过 24 个月，请缩小查询范围');

  deptLoading.value = true;
  try {
    const res = await reportApi.departmentTrend({
      department: deptForm.department,
      fromMonth: deptForm.fromMonth,
      toMonth: deptForm.toMonth,
    });
    deptRows.value = res.data || [];
    // 等容器渲染出来再初始化图表，否则取不到尺寸
    await nextTick();
    renderDeptChart();
  } catch (e) {
    errToast(e.message);
  } finally {
    deptLoading.value = false;
  }
}

/** 重置部门查询条件：回到默认区间并清空图表 */
function resetDept() {
  deptForm.department = '';
  deptForm.fromMonth = shiftMonth(-11);
  deptForm.toMonth = shiftMonth(0);
  deptRows.value = [];
  if (deptChart) deptChart.clear();
}

/** 渲染部门趋势折线图（notMerge=true，彻底替换旧配置） */
function renderDeptChart() {
  if (!deptChartRef.value) return;
  if (!deptChart) deptChart = echarts.init(deptChartRef.value);
  const rows = deptRows.value;
  deptChart.setOption(
    {
      title: {
        text: '部门加班时长趋势图',
        left: 'center',
        textStyle: { color: '#4a2f3c', fontSize: 16, fontWeight: 700 },
      },
      grid: { left: 56, right: 28, top: 64, bottom: 56 },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0];
          return `部门：${p.seriesName} | 时间：${p.name} | 总加班时长：${Number(p.value || 0).toFixed(2)}小时`;
        },
      },
      xAxis: {
        type: 'category',
        name: '年月',
        data: rows.map((r) => r.month),
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#ffd9e8' } },
        axisLabel: { color: '#9a7287' },
        nameTextStyle: { color: '#9a7287' },
      },
      yAxis: {
        type: 'value',
        name: '时长(小时)',
        nameTextStyle: { color: '#9a7287' },
        axisLabel: { color: '#9a7287' },
        splitLine: { lineStyle: { color: '#fff0f6' } },
      },
      series: [
        {
          name: deptForm.department || '全公司',
          type: 'line',
          smooth: true,
          symbolSize: 7,
          data: rows.map((r) => Number(r.hours || 0)),
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

// ---------------- 员工加班趋势 ----------------
const empForm = reactive({
  userId: '',
  from: shiftDay(-29), // 默认近 30 天
  to: shiftDay(0),
});
const empRows = ref([]);
const empLoading = ref(false);
const empChartRef = ref(null);
let empChart = null;

/**
 * 两个日期之间相差的天数（含首尾）
 * 拼接 T00:00:00 强制按本地时间解析，避免 UTC 解析导致差一天
 * @param {string} from 'YYYY-MM-DD'
 * @param {string} to 'YYYY-MM-DD'
 * @returns {number|null}
 */
function daySpan(from, to) {
  if (!from || !to) return null;
  const s = new Date(from + 'T00:00:00').getTime();
  const e = new Date(to + 'T00:00:00').getTime();
  if (Number.isNaN(s) || Number.isNaN(e)) return null;
  return Math.floor((e - s) / 86400000) + 1;
}

/** 当前所选员工姓名，用作图表 series 名（tooltip 里展示） */
const empName = computed(() => users.value.find((u) => u.id === empForm.userId)?.name || '');

/** 查询员工趋势：POST /api/report/trend/employee */
async function queryEmp() {
  if (!empForm.userId) return warn('请选择员工');
  if (!empForm.from || !empForm.to) return warn('请选择起始日期与结束日期');
  const span = daySpan(empForm.from, empForm.to);
  if (span == null || span <= 0) return warn('结束日期不能早于起始日期');
  if (span > 365) return warn('时间跨度不能超过 365 天，请缩小查询范围');

  empLoading.value = true;
  try {
    const res = await reportApi.employeeTrend({
      userId: empForm.userId,
      from: empForm.from,
      to: empForm.to,
    });
    empRows.value = res.data || [];
    await nextTick();
    renderEmpChart();
  } catch (e) {
    errToast(e.message);
  } finally {
    empLoading.value = false;
  }
}

/** 重置员工查询条件 */
function resetEmp() {
  empForm.userId = '';
  empForm.from = shiftDay(-29);
  empForm.to = shiftDay(0);
  empRows.value = [];
  if (empChart) empChart.clear();
}

/** 渲染员工趋势折线图（与部门图同款配色，仅 x 轴换成日期） */
function renderEmpChart() {
  if (!empChartRef.value) return;
  if (!empChart) empChart = echarts.init(empChartRef.value);
  const rows = empRows.value;
  empChart.setOption(
    {
      title: {
        text: '员工加班时长趋势图',
        left: 'center',
        textStyle: { color: '#4a2f3c', fontSize: 16, fontWeight: 700 },
      },
      grid: { left: 56, right: 28, top: 64, bottom: 56 },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const p = params[0];
          return `员工：${p.seriesName} | 时间：${p.name} | 总加班时长：${Number(p.value || 0).toFixed(2)}小时`;
        },
      },
      xAxis: {
        type: 'category',
        name: '日期',
        data: rows.map((r) => r.date),
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#ffd9e8' } },
        axisLabel: { color: '#9a7287' },
        nameTextStyle: { color: '#9a7287' },
      },
      yAxis: {
        type: 'value',
        name: '时长(小时)',
        nameTextStyle: { color: '#9a7287' },
        axisLabel: { color: '#9a7287' },
        splitLine: { lineStyle: { color: '#fff0f6' } },
      },
      series: [
        {
          name: empName.value,
          type: 'line',
          smooth: true,
          symbolSize: 7,
          data: rows.map((r) => Number(r.hours || 0)),
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

// ---------------- 生命周期 ----------------
/** 窗口尺寸变化：两张图都要 resize */
function onResize() {
  if (deptChart) deptChart.resize();
  if (empChart) empChart.resize();
}

/**
 * 切换 Tab：隐藏的图表容器尺寸为 0，显示后需重新 resize 才能正确铺满
 * 等 nextTick 让 DOM 完成切换再执行
 */
watch(activeTab, async () => {
  await nextTick();
  onResize();
});

onMounted(() => {
  window.addEventListener('resize', onResize);
  loadBase();
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize);
  // 销毁实例，避免 DOM 移除后残留监听
  if (deptChart) {
    deptChart.dispose();
    deptChart = null;
  }
  if (empChart) {
    empChart.dispose();
    empChart = null;
  }
});
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">统计图展示</div>
        <div class="page-sub">
          按部门或员工维度查看加班时长趋势；部门最长 24 个月，员工最长 365 天
        </div>
      </div>
    </div>

    <div class="card">
      <el-tabs v-model="activeTab">
        <!-- 部门加班趋势 -->
        <el-tab-pane label="部门加班趋势" name="dept">
          <el-form :inline="true" :model="deptForm" class="search-form" @submit.prevent>
            <el-form-item label="部门">
              <el-select
                v-model="deptForm.department"
                filterable
                placeholder="请选择部门"
                style="width: 200px"
              >
                <el-option label="全公司" value="" />
                <el-option v-for="d in departments" :key="d" :label="d" :value="d" />
              </el-select>
            </el-form-item>
            <el-form-item label="起始年月">
              <el-date-picker
                v-model="deptForm.fromMonth"
                type="month"
                value-format="YYYY-MM"
                placeholder="起始年月"
                style="width: 150px"
                clearable
              />
            </el-form-item>
            <el-form-item label="结束年月">
              <el-date-picker
                v-model="deptForm.toMonth"
                type="month"
                value-format="YYYY-MM"
                placeholder="结束年月"
                style="width: 150px"
                clearable
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :icon="Search" :loading="deptLoading" @click="queryDept">
                查询
              </el-button>
              <el-button :icon="Refresh" @click="resetDept">重置</el-button>
            </el-form-item>
          </el-form>

          <div v-loading="deptLoading" class="chart-box">
            <div v-show="deptRows.length" ref="deptChartRef" class="chart"></div>
            <div v-if="!deptRows.length" class="empty-box">暂无数据</div>
          </div>
        </el-tab-pane>

        <!-- 员工加班趋势 -->
        <el-tab-pane label="员工加班趋势" name="emp">
          <el-form :inline="true" :model="empForm" class="search-form" @submit.prevent>
            <el-form-item label="员工">
              <el-select
                v-model="empForm.userId"
                filterable
                placeholder="请选择员工"
                style="width: 220px"
              >
                <el-option
                  v-for="u in users"
                  :key="u.id"
                  :value="u.id"
                  :label="`${u.name} - ${u.department || '未分组'}`"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="起始日期">
              <el-date-picker
                v-model="empForm.from"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="起始日期"
                style="width: 160px"
                clearable
              />
            </el-form-item>
            <el-form-item label="结束日期">
              <el-date-picker
                v-model="empForm.to"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="结束日期"
                style="width: 160px"
                clearable
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :icon="Search" :loading="empLoading" @click="queryEmp">
                查询
              </el-button>
              <el-button :icon="Refresh" @click="resetEmp">重置</el-button>
            </el-form-item>
          </el-form>

          <div v-loading="empLoading" class="chart-box">
            <div v-show="empRows.length" ref="empChartRef" class="chart"></div>
            <div v-if="!empRows.length" class="empty-box">暂无数据</div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<style scoped>
.search-form {
  margin-bottom: 4px;
}
.chart-box {
  position: relative;
  width: 100%;
  min-height: 420px;
}
.chart {
  width: 100%;
  height: 420px;
}
.empty-box {
  height: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-light);
  font-size: 14px;
}
</style>

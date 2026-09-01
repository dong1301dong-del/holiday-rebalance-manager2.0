<script setup>
/**
 * 日历视图（历史遗留页面）
 * 职责：以月历形式展示法定节假日、调休补班日，以及每日的加班时长与调休条数。
 *
 * 注意：本组件依赖的 statsApi.holidays 目前未在 src/api.js 中导出，路由表 router.js 里也没有注册 /calendar，
 * 因此当前不会被打包进可访问页面（节假日日历现由 Holiday.vue 承载）。
 * 若后续要启用，需要把 statsApi.holidays 换成 holidayApi.year/month 并补注册路由。
 */
import { ref, computed, onMounted } from 'vue';
import { overtimeApi, leaveApi, statsApi } from '../api';
import { isEmployee } from '../store';
import { err } from '../toast';

const today = new Date();
const curYear = ref(today.getFullYear());
const curMonth = ref(today.getMonth() + 1); // 1-12（Date.getMonth() 返回 0-11，故 +1）

const holidays = ref([]);
const overtime = ref([]);
const leaves = ref([]);
const loading = ref(true);

// 表头按周一开始排列（与下面 days 的 lead 计算口径一致）
const weekHeaders = ['一', '二', '三', '四', '五', '六', '日'];

/**
 * 加载当月数据：节假日 + 加班 + 调休，三者并行
 */
async function load() {
  loading.value = true;
  const ym = `${curYear.value}-${String(curMonth.value).padStart(2, '0')}`;
  try {
    const [hRes, oRes, lRes] = await Promise.all([
      statsApi.holidays(curYear.value),
      overtimeApi.list({ month: ym }),
      // 调休使用记录按日期区间查询（后端无 month 参数）；size 取 500 确保单月数据一次拉全
      leaveApi.list({
        from: `${ym}-01`,
        // new Date(year, month, 0) 取到的是本月最后一天，用它得到当月天数
        to: `${ym}-${String(new Date(curYear.value, curMonth.value, 0).getDate()).padStart(2, '0')}`,
        size: 500,
      }),
    ]);
    holidays.value = hRes.data;
    overtime.value = oRes.data;
    leaves.value = lRes.data;
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}
onMounted(load);

const monthLabel = computed(() => `${curYear.value} 年 ${curMonth.value} 月`);

/** 上一月：跨年时回退到去年 12 月 */
function prevMonth() {
  curMonth.value -= 1;
  if (curMonth.value < 1) { curMonth.value = 12; curYear.value -= 1; }
  load();
}
/** 下一月：跨年时前进到次年 1 月 */
function nextMonth() {
  curMonth.value += 1;
  if (curMonth.value > 12) { curMonth.value = 1; curYear.value += 1; }
  load();
}
/** 回到今天所在月份 */
function goToday() {
  curYear.value = today.getFullYear();
  curMonth.value = today.getMonth() + 1;
  load();
}

/**
 * 生成日历格子：前导空格 + 本月每天 + 尾部补满整周
 * 每个格子聚合了当天节假日类型、加班总时长/条数、调休条数。
 * 加班只统计 CONFIRMED（已确认）状态，调休排除 VOID（已作废）记录。
 */
const days = computed(() => {
  const year = curYear.value;
  const month = curMonth.value;
  const first = new Date(year, month - 1, 1);
  // 周一开始：getDay() 周日=0，减 1 后为 -1，需折算成 6（即上月末尾那格）
  let lead = first.getDay() - 1;
  if (lead < 0) lead = 6;
  const total = new Date(year, month, 0).getDate();
  const cells = [];
  for (let i = 0; i < lead; i++) cells.push(null);
  for (let d = 1; d <= total; d++) {
    const ds = `${year}-${String(month).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    const hol = holidays.value.find((h) => h.date === ds);
    const ots = overtime.value.filter((o) => o.date === ds && o.status === 'CONFIRMED');
    const lvs = leaves.value.filter((l) => l.date === ds && l.status !== 'VOID');
    const dayOfWeek = new Date(year, month - 1, d).getDay();
    // 样式优先级：人工/官方节假日 > 补班日 > 周末；无匹配则为空（普通工作日）
    let cls = '';
    if (hol?.type === 'HOLIDAY') cls = 'holiday';
    else if (hol?.type === 'ADJUSTED') cls = 'adjusted';
    else if (dayOfWeek === 0 || dayOfWeek === 6) cls = 'weekend';
    cells.push({
      date: ds,
      day: d,
      cls,
      holidayName: hol?.name || '',
      otHours: ots.reduce((s, o) => s + o.hours, 0),
      otCount: ots.length,
      leaveCount: lvs.length,
    });
  }
  // 尾部补 null 直到 7 的整数倍，保证网格最后一行完整
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
});

/** 图例（类名与 days.cls 对应） */
const legend = [
  { cls: 'holiday', label: '法定节假日' },
  { cls: 'adjusted', label: '调休补班' },
  { cls: 'weekend', label: '周末' },
];
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">日历视图</div>
        <div class="page-sub">法定节假日、调休补班与加班/调休情况一览</div>
      </div>
    </div>

    <div class="card">
      <div class="cal-toolbar">
        <div class="cal-nav">
          <button class="btn btn-sm" @click="prevMonth">‹</button>
          <span class="cal-month">{{ monthLabel }}</span>
          <button class="btn btn-sm" @click="nextMonth">›</button>
          <button class="btn btn-sm" @click="goToday" style="margin-left:8px">今天</button>
        </div>
        <div class="cal-legend">
          <span v-for="l in legend" :key="l.cls" class="legend-item">
            <span class="legend-dot" :class="l.cls"></span>{{ l.label }}
          </span>
        </div>
      </div>

      <div v-if="loading" style="padding:40px"><div class="spinner"></div></div>
      <template v-else>
        <div class="cal-week">
          <div v-for="w in weekHeaders" :key="w" class="cal-week-cell">{{ w }}</div>
        </div>
        <div class="cal-grid">
          <div v-for="(d, i) in days" :key="i" class="cal-cell" :class="d ? d.cls : 'empty-cell'">
            <template v-if="d">
              <div class="cal-day" :class="{ today: d.date === today.toISOString().slice(0, 10) }">{{ d.day }}</div>
              <div v-if="d.holidayName" class="cal-holiday">{{ d.holidayName }}</div>
              <div class="cal-badges">
                <span v-if="d.otCount" class="cal-badge ot" :title="`加班 ${d.otHours}h`">⏰ {{ d.otHours }}h</span>
                <span v-if="d.leaveCount" class="cal-badge lv" :title="`调休 ${d.leaveCount} 条`">🏖️ {{ d.leaveCount }}</span>
              </div>
            </template>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.cal-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
}
.cal-nav { display: flex; align-items: center; gap: 6px; }
.cal-month { font-size: 18px; font-weight: 700; color: var(--text-main); min-width: 130px; text-align: center; }
.cal-legend { display: flex; gap: 14px; font-size: 12.5px; color: var(--text-sub); }
.legend-item { display: flex; align-items: center; gap: 5px; }
.legend-dot { width: 12px; height: 12px; border-radius: 4px; display: inline-block; }
.legend-dot.holiday { background: #fdeaea; border: 1px solid #e05a5a; }
.legend-dot.adjusted { background: #fdf0dc; border: 1px solid #e8a23a; }
.legend-dot.weekend { background: #eef1f5; border: 1px solid #c9d6e3; }

.cal-week {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  margin-bottom: 6px;
}
.cal-week-cell {
  text-align: center;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-light);
  padding: 6px 0;
}
.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
}
.cal-cell {
  min-height: 96px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: #fff;
  padding: 8px;
  position: relative;
  transition: box-shadow 0.2s;
}
.cal-cell:hover { box-shadow: var(--shadow); }
.cal-cell.holiday { background: #fef3f3; border-color: #f3c9c9; }
.cal-cell.adjusted { background: #fef9ef; border-color: #f0ddb8; }
.cal-cell.weekend { background: #f8fafc; }
.cal-cell.empty-cell { border: none; background: transparent; }

.cal-day {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-main);
}
.cal-day.today {
  display: inline-flex;
  width: 24px; height: 24px;
  align-items: center; justify-content: center;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--primary-light), var(--primary));
  color: #fff;
}
.cal-holiday {
  font-size: 11px;
  color: var(--danger);
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cal-cell.adjusted .cal-holiday { color: var(--warning); }

.cal-badges { position: absolute; bottom: 8px; left: 8px; right: 8px; display: flex; flex-wrap: wrap; gap: 4px; }
.cal-badge {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 10px;
  white-space: nowrap;
}
.cal-badge.ot { background: var(--primary-bg); color: var(--primary); }
.cal-badge.lv { background: #e4f5ee; color: var(--success); }

@media (max-width: 700px) {
  .cal-cell { min-height: 64px; padding: 5px; }
  .cal-badge { font-size: 9px; padding: 0 4px; }
}
</style>

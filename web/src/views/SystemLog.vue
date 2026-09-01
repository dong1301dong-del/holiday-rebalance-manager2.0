<script setup>
/**
 * 系统日志（路由 /system-log，权限码 systemLog:view）
 * 职责：按模块 / 操作类型 / 级别 / 时间区间 / 关键字检索全部关键操作记录（登录、增删改、导入导出、节假日变更）。
 * 分页由后端完成，前端只负责传参与渲染。
 */
import { ref, reactive, onMounted, computed } from 'vue';
import { systemLogApi } from '../api';
import { err } from '../toast';

const loading = ref(false);
const rows = ref([]);
const total = ref(0);
const modules = ref([]); // 模块下拉选项

const query = reactive({
  module: '',
  action: '',
  level: '',      // INFO | WARN | ERROR
  keyword: '',    // 匹配操作人/对象/内容
  from: '',       // YYYY-MM-DD
  to: '',         // YYYY-MM-DD
  page: 1,
  size: 20,
});

// 操作类型下拉：与后端记录的 action 文案保持一致（后端未单独提供枚举接口）
const ACTIONS = ['登录', '新增', '编辑', '删除', '作废', '导入', '导出', '变更日期类型', '刷新节假日', '强制刷新节假日'];

/** 日志级别 → Element Plus tag 类型与中文名 */
const LEVEL_TAG = {
  INFO: { type: 'info', label: '信息' },
  WARN: { type: 'warning', label: '警告' },
  ERROR: { type: 'danger', label: '错误' },
};

// 日期区间选择器的值（[from, to]），与 query.from/to 通过 onDateChange 同步
const dateRange = ref(null);

/** 加载日志：GET /api/system-logs；空条件统一转 undefined，避免把空串传给后端 */
async function load() {
  loading.value = true;
  try {
    const params = {
      module: query.module || undefined,
      action: query.action || undefined,
      level: query.level || undefined,
      keyword: query.keyword || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      page: query.page,
      size: query.size,
    };
    const res = await systemLogApi.list(params);
    rows.value = res.data || [];
    total.value = res.total || 0;
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}

/** 加载模块下拉：GET /api/system-logs/modules；失败仅降级为空下拉 */
async function loadModules() {
  try {
    const res = await systemLogApi.modules();
    modules.value = res.data || [];
  } catch { /* 忽略 */ }
}

/** 点击「查询」或翻页时触发：条件变化必须回到第 1 页，否则会停在越界页码 */
function handleSearch() {
  query.page = 1;
  load();
}

/** 点击「重置」时触发：清空全部条件（含日期区间组件）并回到第 1 页 */
function handleReset() {
  query.module = '';
  query.action = '';
  query.level = '';
  query.keyword = '';
  query.from = '';
  query.to = '';
  dateRange.value = null; // 需同时清掉组件自身的值，否则界面仍显示旧区间
  query.page = 1;
  load();
}

/** 日期区间变化时同步到查询条件；清空选择器时 val 为 null */
function onDateChange(val) {
  if (val && val.length === 2) {
    query.from = val[0];
    query.to = val[1];
  } else {
    query.from = '';
    query.to = '';
  }
}

/** 时间格式化：后端 ISO 串 '2026-01-01T12:00:00' → '2026-01-01 12:00:00' */
function fmtTime(t) {
  if (!t) return '-';
  return String(t).replace('T', ' ').slice(0, 19);
}

onMounted(() => {
  loadModules();
  load();
});
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">系统日志</div>
        <div class="page-sub">记录所有关键操作，包含登录、增删改、导入导出与高风险日期变更</div>
      </div>
    </div>

    <div class="card">
      <el-form :inline="true" class="search-form" @submit.prevent>
        <el-form-item label="模块">
          <el-select v-model="query.module" placeholder="全部模块" clearable style="width: 150px">
            <el-option v-for="m in modules" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作类型">
          <el-select v-model="query.action" placeholder="全部操作" clearable style="width: 150px">
            <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="query.level" placeholder="全部级别" clearable style="width: 120px">
            <el-option label="信息" value="INFO" />
            <el-option label="警告" value="WARN" />
            <el-option label="错误" value="ERROR" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作时间">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 250px"
            @change="onDateChange"
          />
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="操作人/对象/内容" clearable style="width: 200px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" border stripe style="width: 100%">
        <el-table-column type="index" label="序号" width="70" align="center">
          <template #default="{ $index }">{{ (query.page - 1) * query.size + $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="操作时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="140">
          <template #default="{ row }">
            {{ row.userRealName || row.username || '系统' }}
            <span v-if="row.username" class="sub-text">({{ row.username }})</span>
          </template>
        </el-table-column>
        <el-table-column prop="module" label="模块" width="120" />
        <el-table-column prop="action" label="操作类型" width="130" />
        <el-table-column prop="target" label="操作对象" width="160" show-overflow-tooltip />
        <el-table-column prop="detail" label="操作内容" min-width="220" show-overflow-tooltip />
        <el-table-column label="级别" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="(LEVEL_TAG[row.level] || LEVEL_TAG.INFO).type" size="small" effect="light">
              {{ (LEVEL_TAG[row.level] || LEVEL_TAG.INFO).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small" effect="plain">
              {{ row.result === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="130" />
      </el-table>

      <div class="table-foot">
        <span class="total-text">共计 {{ total }} 条数据</span>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="load"
          @size-change="handleSearch"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header { margin-bottom: 16px; }
.page-title { font-size: 20px; font-weight: 800; color: #c0356b; }
.page-sub { font-size: 13px; color: #9a7287; margin-top: 4px; }
.card {
  background: #fff; border-radius: 12px; box-shadow: 0 2px 10px rgba(255, 111, 165, 0.1);
  padding: 18px; border: 1px solid #ffe6f1;
}
.search-form { margin-bottom: 4px; }
.sub-text { color: #c39ab2; font-size: 12px; }
.table-foot {
  display: flex; align-items: center; justify-content: space-between;
  padding-top: 12px; flex-wrap: wrap; gap: 8px;
}
.total-text { font-size: 13px; color: #9a7287; }
</style>

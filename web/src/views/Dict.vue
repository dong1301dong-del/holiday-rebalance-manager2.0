<script setup>
/**
 * 数据字典（历史遗留页面）
 * 职责：左侧字典类型 + 右侧数据项的两栏维护（如部门、请假类型的下拉选项）。
 *
 * 注意：本组件依赖的 dictApi 目前未在 src/api.js 中导出，且路由表 router.js 里也没有注册 /dict，
 * 因此当前不会被打包进可访问页面。若后续要启用，需要同时在 api.js 补 dictApi 与在 router.js 加路由。
 */
import { ref, onMounted, computed } from 'vue';
import { dictApi } from '../api';
import { ok, err } from '../toast';

const types = ref([]);    // 字典类型列表
const current = ref(null); // 当前选中的类型
const dataList = ref([]);  // 当前类型下的数据项
const loading = ref(false);

/** 加载类型列表：dictApi.types()；首次进入自动选中第一个类型 */
async function loadTypes() {
  try {
    const res = await dictApi.types();
    types.value = res.data || [];
    if (!current.value && types.value.length) selectType(types.value[0]);
  } catch (e) { err(e.message); }
}
onMounted(loadTypes);

/** 切换字典类型：点击左侧类型项时触发，重新拉取该类型下的数据项 */
async function selectType(t) {
  current.value = t;
  loading.value = true;
  try {
    const res = await dictApi.data(t.code);
    dataList.value = res.data || [];
  } catch (e) { err(e.message); }
  finally { loading.value = false; }
}

// ---------- 类型 ----------
const showType = ref(false);
const typeForm = ref({ code: '', name: '' });
function openType() { typeForm.value = { code: '', name: '' }; showType.value = true; }

/** 新增字典类型：dictApi.createType({ code, name }) */
async function submitType() {
  const f = typeForm.value;
  if (!f.code || !f.name) return err('请填写类型编码与名称');
  try { await dictApi.createType({ code: f.code, name: f.name }); ok('类型已添加'); showType.value = false; loadTypes(); }
  catch (e) { err(e.message); }
}

// ---------- 数据项 ----------
const showData = ref(false);
const isEdit = ref(false); // true=编辑，false=新增
const dataForm = ref({ id: null, value: '', label: '', sort: 0, status: 'ACTIVE' });

/** 打开新增数据项弹窗；未选类型时直接拦截 */
function openData() {
  if (!current.value) return err('请先选择字典类型');
  isEdit.value = false;
  dataForm.value = { id: null, value: '', label: '', sort: 0, status: 'ACTIVE' };
  showData.value = true;
}
function openEditData(d) {
  isEdit.value = true;
  dataForm.value = { id: d.id, value: d.value, label: d.label, sort: d.sort ?? 0, status: d.status };
  showData.value = true;
}

/** 新增/更新数据项：dictApi.upsertData 以 typeCode 归属到当前类型 */
async function submitData() {
  const f = dataForm.value;
  if (!f.value || !f.label) return err('请填写值与显示文本');
  try {
    await dictApi.upsertData({ typeCode: current.value.code, value: f.value, label: f.label, sort: Number(f.sort) || 0, status: f.status });
    ok(isEdit.value ? '已更新' : '已添加');
    showData.value = false;
    selectType(current.value);
  } catch (e) { err(e.message); }
}

/** 删除数据项：原生 confirm 二次确认 */
async function removeData(d) {
  if (!confirm(`确定删除数据项「${d.label}」吗？`)) return;
  try { await dictApi.removeData(d.id); ok('已删除'); selectType(current.value); } catch (e) { err(e.message); }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">数据字典</div>
        <div class="page-sub">维护系统可配置的下拉选项与枚举（如部门、请假类型等），按类型分组管理</div>
      </div>
    </div>

    <div class="dict-layout">
      <div class="card dict-side">
        <div class="side-head">
          <span>字典类型</span>
          <button v-permission="'dict:manage'" class="btn btn-sm btn-primary" @click="openType">＋ 类型</button>
        </div>
        <div class="side-list">
          <div v-for="t in types" :key="t.id" class="side-item" :class="{ active: current && current.code === t.code }" @click="selectType(t)">
            <span>{{ t.name }}</span><code class="muted">{{ t.code }}</code>
          </div>
          <div v-if="!types.length" class="empty-sm">暂无类型</div>
        </div>
      </div>

      <div class="card dict-main">
        <div class="side-head" v-if="current">
          <span>「{{ current.name }}」数据项</span>
          <button v-permission="'dict:manage'" class="btn btn-sm btn-primary" @click="openData">＋ 数据项</button>
        </div>
        <div v-if="loading" style="padding:30px"><div class="spinner"></div></div>
        <div v-else-if="!current" class="empty"><div class="emoji">📚</div>请选择左侧字典类型</div>
        <div v-else-if="dataList.length === 0" class="empty"><div class="emoji">📭</div>该类型暂无数据项</div>
        <div v-else class="table-wrap">
          <table class="table">
            <thead><tr><th>值</th><th>显示文本</th><th class="num">排序</th><th>状态</th><th style="width:120px">操作</th></tr></thead>
            <tbody>
              <tr v-for="d in dataList" :key="d.id">
                <td style="font-family:monospace">{{ d.value }}</td>
                <td>{{ d.label }}</td>
                <td class="num">{{ d.sort }}</td>
                <td><span class="tag" :class="d.status === 'ACTIVE' ? 'tag-green' : 'tag-gray'">{{ d.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
                <td>
                  <button v-permission="'dict:manage'" class="btn-text btn-sm" @click="openEditData(d)">编辑</button>
                  <button v-permission="'dict:manage'" class="btn-text danger btn-sm" @click="removeData(d)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- 新增类型 -->
    <div v-if="showType" class="modal-mask" @click.self="showType = false">
      <div class="modal" style="width:min(420px,94vw)">
        <div class="modal-header">新增字典类型</div>
        <div class="modal-body">
          <div class="form-item"><label class="form-label">类型编码 *</label><input v-model="typeForm.code" class="input" placeholder="如 department" /></div>
          <div class="form-item" style="margin-bottom:0"><label class="form-label">类型名称 *</label><input v-model="typeForm.name" class="input" placeholder="如 部门" /></div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showType = false">取消</button>
          <button class="btn btn-primary" @click="submitType">添加</button>
        </div>
      </div>
    </div>

    <!-- 新增 / 编辑数据项 -->
    <div v-if="showData" class="modal-mask" @click.self="showData = false">
      <div class="modal" style="width:min(460px,94vw)">
        <div class="modal-header">{{ isEdit ? '编辑数据项' : '新增数据项' }}</div>
        <div class="modal-body">
          <div class="form-row">
            <div class="form-item"><label class="form-label">值 *</label><input v-model="dataForm.value" class="input" placeholder="如 rd" /></div>
            <div class="form-item"><label class="form-label">显示文本 *</label><input v-model="dataForm.label" class="input" placeholder="如 研发部" /></div>
          </div>
          <div class="form-row">
            <div class="form-item"><label class="form-label">排序</label><input v-model.number="dataForm.sort" type="number" class="input" /></div>
            <div class="form-item"><label class="form-label">状态</label>
              <select v-model="dataForm.status" class="select"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showData = false">取消</button>
          <button class="btn btn-primary" @click="submitData">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dict-layout { display: grid; grid-template-columns: 260px 1fr; gap: 16px; align-items: start; }
@media (max-width: 800px) { .dict-layout { grid-template-columns: 1fr; } }
.side-head { display: flex; justify-content: space-between; align-items: center; font-weight: 700; color: var(--text-sub); margin-bottom: 12px; }
.side-list { display: flex; flex-direction: column; gap: 6px; }
.side-item { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border-radius: 10px; cursor: pointer; border: 1px solid transparent; }
.side-item:hover { background: var(--primary-bg); }
.side-item.active { background: var(--primary-bg); border-color: var(--primary-light); color: var(--primary); font-weight: 700; }
.muted { color: var(--text-light); font-size: 11px; font-family: monospace; }
.empty-sm { color: var(--text-light); font-size: 13px; padding: 12px; text-align: center; }
</style>

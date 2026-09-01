<script setup>
/**
 * 系统配置（路由 /system/config，权限码 config:view；编辑按钮需 config:edit）
 * 职责：查看与修改系统级参数（单日加班封顶、时段时长、折算比例等），保存后即时生效。
 * 仅支持改「值」与「说明」，配置键由后端预置、前端只读展示。
 */
import { ref, onMounted } from 'vue';
import { configApi } from '../api';
import { ok, err } from '../toast';

const list = ref([]);        // 配置项列表
const loading = ref(true);
const showForm = ref(false); // 编辑弹窗显隐
const form = ref({ key: '', value: '', description: '' });

/** 加载配置列表：GET /api/config */
async function load() {
  loading.value = true;
  try {
    const res = await configApi.list();
    list.value = res.data || [];
  } catch (e) { err(e.message); }
  finally { loading.value = false; }
}
onMounted(load);

/** 打开编辑弹窗：以当前行数据填充表单（key 不可编辑） */
function openEdit(c) {
  form.value = { key: c.key, value: c.value || '', description: c.description || '' };
  showForm.value = true;
}

/** 保存配置：POST /api/config（key 已存在则为更新） */
async function submit() {
  const f = form.value;
  if (!f.key) return err('配置键缺失');
  try {
    await configApi.set({ key: f.key, value: f.value, description: f.description });
    ok('配置已保存');
    showForm.value = false;
    load();
  } catch (e) { err(e.message); }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">系统配置</div>
        <div class="page-sub">调整系统级参数（如单日加班封顶、折算比例、系统名称等），修改后即时生效</div>
      </div>
    </div>

    <div class="card">
      <div v-if="loading" style="padding:40px"><div class="spinner"></div></div>
      <div v-else-if="list.length === 0" class="empty"><div class="emoji">🔧</div>暂无配置项</div>
      <div v-else class="table-wrap">
        <table class="table">
          <thead><tr><th>配置键</th><th>值</th><th>说明</th><th style="width:100px">操作</th></tr></thead>
          <tbody>
            <tr v-for="c in list" :key="c.id">
              <td style="font-family:monospace;font-weight:600">{{ c.key }}</td>
              <td>{{ c.value }}</td>
              <td style="color:var(--text-sub)">{{ c.description || '-' }}</td>
              <td><button v-permission="'config:edit'" class="btn-text btn-sm" @click="openEdit(c)">编辑</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal" style="width:min(480px,94vw)">
        <div class="modal-header">编辑配置</div>
        <div class="modal-body">
          <div class="form-item"><label class="form-label">配置键</label><input :value="form.key" class="input" disabled /></div>
          <div class="form-item"><label class="form-label">值</label><input v-model="form.value" class="input" /></div>
          <div class="form-item" style="margin-bottom:0"><label class="form-label">说明</label><input v-model="form.description" class="input" /></div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showForm = false">取消</button>
          <button class="btn btn-primary" @click="submit">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

/**
 * 轻量 toast 提示（自实现，不依赖 Element Plus 的 ElMessage）
 * App.vue 负责渲染 toasts 数组；各页面直接调用 ok / err / warn 即可。
 */
import { reactive } from 'vue';

/** 当前在屏幕上显示的提示队列，元素形如 { id, message, type } */
export const toasts = reactive([]);

// 自增 id，用于定时移除时精确定位，避免并发提示互相误删
let seq = 0;

/**
 * 弹出一条提示
 * @param {string} message 文案
 * @param {'info'|'success'|'error'|'warning'} [type='info'] 类型，对应 App.vue 中的样式类
 * @param {number} [duration=2600] 毫秒后自动消失
 */
export function toast(message, type = 'info', duration = 2600) {
  const id = ++seq;
  toasts.push({ id, message, type });
  setTimeout(() => {
    const idx = toasts.findIndex((t) => t.id === id);
    if (idx >= 0) toasts.splice(idx, 1);
  }, duration);
}

/** 成功提示（绿色） */
export const ok = (m) => toast(m, 'success');
/** 错误提示（红色） */
export const err = (m) => toast(m, 'error');
/** 警告提示（橙色） */
export const warn = (m) => toast(m, 'warning');

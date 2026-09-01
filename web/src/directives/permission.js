import { hasPermission } from '../store';

/**
 * v-permission 指令：按钮级权限控制
 * 用法：v-permission="'overtime:add'"（值为权限码，对应后端资源表的 permission 字段）
 * 无权限时直接从 DOM 移除元素，而不是 disabled，避免用户看到不可用的按钮。
 *
 * 注意：仅在 mounted 时判断一次。权限集合来自 store.auth.permissions，
 * 登录后由 /api/auth/me 一次性下发，运行期不会变化，故无需 updated 钩子。
 */
export const permission = {
  /**
   * @param {HTMLElement} el 指令所在元素
   * @param {{value:string}} binding binding.value 即权限码
   */
  mounted(el, binding) {
    if (!hasPermission(binding.value)) el.parentNode && el.parentNode.removeChild(el);
  },
};

import { reactive } from 'vue';
import { getToken, setToken, authApi } from './api';

const LAST_ACTIVITY_KEY = 'lastActivityAt';

/**
 * 全局登录状态（动态菜单/权限来自后端 /api/auth/me）
 * 作为单一数据源被 MainLayout.vue、各 views 与 router.js 守卫读取。
 */
export const auth = reactive({
  token: getToken(),
  user: null,        // { id, username, name, department, position, roles: [] }
  menus: [],         // 后端下发的菜单树，MainLayout 据此渲染侧边栏
  permissions: [],   // 后端下发的权限编码集合，v-permission 与 hasPermission 据此判断
  initialized: false, // 是否已拉取过个人信息；router 守卫据此避免重复请求
});

/**
 * 初始化登录态：拉取个人信息、菜单与权限
 * 由 router.beforeEach 在首次路由跳转时调用；未登录或 token 失效时静默返回 false
 * @returns {Promise<boolean>} 是否成功恢复登录态
 */
export async function initAuth() {
  if (!auth.token) {
    auth.initialized = true;
    return false;
  }
  try {
    const res = await authApi.me();
    applyAuth(res.data);
    return true;
  } catch {
    // token 过期/被顶下线：清空本地状态，交给路由守卫跳转登录页
    auth.token = '';
    setToken('');
    return false;
  } finally {
    // 无论成功失败都要置位，避免守卫里重复 await
    auth.initialized = true;
  }
}

/**
 * 把 /api/auth/me 或 /api/auth/login 的返回写入全局状态
 * @param {{token?:string, user:object, menus?:object[], permissions?:string[]}} data
 */
function applyAuth(data) {
  auth.token = data.token || auth.token;
  auth.user = data.user;
  auth.menus = data.menus || [];
  auth.permissions = data.permissions || [];
  if (data.token) setToken(data.token);
}

/**
 * 登录：POST /api/auth/login 并落地全局状态
 * @param {string} username
 * @param {string} password
 * @returns {Promise<object>} 含 user / menus / permissions / mustChangePwd
 */
export async function doLogin(username, password) {
  const res = await authApi.login(username, password);
  applyAuth(res.data);
  // 登录成功刷新活动时间，避免旧时间戳导致立即被空闲监听踢下线
  localStorage.setItem(LAST_ACTIVITY_KEY, Date.now().toString());
  return res.data;
}

/** 退出登录：清空内存状态并移除本地 token */
export function logout() {
  auth.token = '';
  auth.user = null;
  auth.menus = [];
  auth.permissions = [];
  setToken('');
  localStorage.removeItem(LAST_ACTIVITY_KEY);
}

/**
 * 是否命中任一角色
 * @param {...string} roles 角色码，如 'ADMIN' | 'CLERK' | 'EMPLOYEE'
 * @returns {boolean}
 */
export const hasRole = (...roles) => {
  if (!auth.user) return false;
  return (auth.user.roles || []).some((r) => roles.includes(r));
};

/**
 * 是否拥有某个权限码（如 'overtime:add'）
 * @param {string} code 权限编码
 * @returns {boolean}
 */
export const hasPermission = (code) => (auth.permissions || []).includes(code);

// 角色快捷判断：员工只能看本人数据，管理员/录入员可管理全员数据
export const isEmployee = () => hasRole('EMPLOYEE');
export const isAdmin = () => hasRole('ADMIN');
export const isClerk = () => hasRole('CLERK');
export const canManage = () => hasRole('ADMIN', 'CLERK');

import { createRouter, createWebHashHistory } from 'vue-router';
import { auth, initAuth, hasPermission } from './store';

/**
 * 路由表
 * - meta.public：无需登录即可访问（目前仅 /login）
 * - meta.title：MainLayout 顶部面包屑文案
 * - meta.permission：进入该路由所需权限码，无权限会被重定向到 /dashboard
 * - 侧边栏菜单不在此维护，来自后端 /api/auth/me 下发的 menus（MainLayout 动态渲染）
 * - /system/role、/system/resource、/system/config 为管理员隐藏页，不在菜单中出现，需直接输地址访问
 */
const routes = [
  { path: '/login', name: 'login', component: () => import('./views/Login.vue'), meta: { public: true } },
  { path: '/change-password', name: 'change-password', component: () => import('./views/ChangePassword.vue') },
  {
    path: '/',
    component: () => import('./layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('./views/Dashboard.vue'), meta: { title: '工作台', permission: 'dashboard:view' } },
      { path: 'overtime', name: 'overtime', component: () => import('./views/Overtime.vue'), meta: { title: '加班录入记录', permission: 'overtime:view' } },
      { path: 'leave', name: 'leave', component: () => import('./views/Leave.vue'), meta: { title: '调休使用记录', permission: 'leave:view' } },
      { path: 'holiday', name: 'holiday', component: () => import('./views/Holiday.vue'), meta: { title: '系统日历', permission: 'holiday:view' } },
      { path: 'all-staff-leave', name: 'all-staff-leave', component: () => import('./views/AllStaffLeave.vue'), meta: { title: '全员调休查看', permission: 'allStaffLeave:view' } },
      { path: 'chart-stats', name: 'chart-stats', component: () => import('./views/ChartStats.vue'), meta: { title: '统计图展示', permission: 'chartStats:view' } },
      { path: 'system-log', name: 'system-log', component: () => import('./views/SystemLog.vue'), meta: { title: '系统日志', permission: 'systemLog:view' } },
      { path: 'user', name: 'user', component: () => import('./views/Users.vue'), meta: { title: '用户管理', permission: 'user:view' } },
      { path: 'profile', name: 'profile', component: () => import('./views/Profile.vue'), meta: { title: '个人中心', permission: 'dashboard:view' } },
      // 管理员隐藏页面（不在动态菜单中，通过直接地址访问）
      { path: 'system/role', name: 'system-role', component: () => import('./views/Role.vue'), meta: { title: '角色权限', permission: 'role:view' } },
      { path: 'system/resource', name: 'system-resource', component: () => import('./views/Resource.vue'), meta: { title: '资源管理', permission: 'resource:view' } },
      { path: 'system/config', name: 'system-config', component: () => import('./views/Config.vue'), meta: { title: '系统配置', permission: 'config:view' } },
    ],
  },
  // 兜底路由：未匹配的地址一律回工作台
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
];

const router = createRouter({
  // 用 hash 模式：后端以静态资源方式托管前端时无需额外配置 fallback
  history: createWebHashHistory(),
  routes,
});

/**
 * 全局前置守卫：保证进入任何页面前登录态已就绪
 * 1. 首次跳转先 initAuth 拉取个人信息/菜单/权限
 * 2. 公开页直接放行
 * 3. 未登录跳登录页；已登录但缺 meta.permission 则退回工作台
 * @param {object} to 目标路由
 */
router.beforeEach(async (to) => {
  if (!auth.initialized) await initAuth();
  if (to.meta.public) return true;
  if (!auth.token || !auth.user) return { path: '/login' };
  if (to.meta.permission && !hasPermission(to.meta.permission)) return { path: '/dashboard' };
  return true;
});

export default router;

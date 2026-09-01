import { getToken } from './api';
import { logout } from './store';

const KEY = 'lastActivityAt';
const IDLE_MS = 24 * 60 * 60 * 1000; // 24 小时无操作强制退出

/**
 * 初始化空闲监听：
 * 1. 启动时若 token 存在且已超 24 小时无操作，立即退出；
 * 2. 监听常见交互事件，每次交互刷新 lastActivityAt；
 * 3. 每分钟巡检一次，超时后强制退出。
 */
export function initIdleMonitor() {
  // 先做一次启动检查：避免关闭浏览器一天后再打开仍处于登录态
  checkIdle();

  const events = ['mousedown', 'keydown', 'touchstart', 'scroll', 'mousemove'];
  // 节流：同一秒内多次事件只写一次 localStorage
  let lastTouch = 0;
  const onActivity = () => {
    const now = Date.now();
    if (now - lastTouch < 1000) return;
    lastTouch = now;
    localStorage.setItem(KEY, now.toString());
  };
  events.forEach((e) => document.addEventListener(e, onActivity, { passive: true }));

  // 跨标签页同步：在其它标签页操作也会刷新 localStorage，本标签页监听到后重新计时，
  // 避免「用户在 A 标签页一直用，B 标签页却被踢下线」的误杀。
  window.addEventListener('storage', (e) => {
    if (e.key === KEY && e.newValue) {
      // 其它页面刷新了活动时间，本页也读取到最新的时间
      checkIdle();
    }
  });

  // 立即记录一次
  onActivity();

  // 每分钟巡检
  setInterval(checkIdle, 60 * 1000);
}

/** 读取最后活动时间并判定是否超时；超时时清空登录态并重定向到登录页 */
function checkIdle() {
  if (!getToken()) return;
  const raw = localStorage.getItem(KEY);
  const last = raw ? Number(raw) : Date.now();
  if (Date.now() - last >= IDLE_MS) {
    localStorage.removeItem(KEY);
    logout();
    window.location.replace('/login');
  }
}

/** 手动刷新活动时间，适合 AJAX 成功/路由切换等主动行为 */
export function touchIdle() {
  localStorage.setItem(KEY, Date.now().toString());
}

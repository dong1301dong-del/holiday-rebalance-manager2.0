<script setup>
/**
 * 主框架布局（登录后的所有页面都嵌在它的 <router-view> 里）
 * 组成：左侧动态菜单（数据来自后端 /api/auth/me 的 menus）+ 顶部栏（面包屑、余额、预警铃铛、用户卡片）+ 内容区。
 * 主要交互：菜单路由跳转、点击铃铛查看余额透支预警、点击用户卡片进个人中心、退出登录。
 */
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { auth, logout } from '../store';
import { reportApi, leaveApi } from '../api';
import { ok } from '../toast';

const route = useRoute();
const router = useRouter();
const balance = ref(null);   // 当前登录者调休余额（小时），null 表示未加载到
const messages = ref([]);    // 余额透支预警消息列表

// 把后端菜单树整理成两级结构（根菜单 + 子菜单）；只支持两级，更深的层级不会渲染
const tree = computed(() => {
  const all = auth.menus || [];
  const roots = all.filter((m) => !m.parentId);
  const childrenOf = (pid) => all.filter((m) => m.parentId === pid);
  return roots.map((r) => ({ ...r, kids: childrenOf(r.id) }));
});

/** 角色码转中文，多人多角色用 ' / ' 连接 */
const roleLabel = computed(() => {
  const map = { ADMIN: '管理员', CLERK: '录入员', EMPLOYEE: '普通员工' };
  return (auth.user?.roles || []).map((r) => map[r] || r).join(' / ');
});

// 头像取姓名首字；未登录/异常时兜底 '?'
const initials = computed(() => (auth.user?.name || '?').slice(0, 1));
// 菜单高亮项 = 当前路由 path，需与后端菜单的 path 完全一致才会命中
const activePath = computed(() => route.path);

// 预警消息只有管理员/录入员能看到（后端 /api/leave/messages 也是这个口径）
const canSeeMessages = computed(() => (auth.user?.roles || []).some((r) => r === 'ADMIN' || r === 'CLERK'));

// 系统管理员与录入员是特殊账号，不参与加班/调休录入，因此不展示「个人调休余额」
const isSystemAccount = canSeeMessages;

/** 加载本人调休余额：GET /api/report/balance（不传 userId 即查本人）；失败时不展示余额模块 */
async function loadBalance() {
  if (isSystemAccount.value) { balance.value = null; return; }
  try {
    const res = await reportApi.balance();
    balance.value = res.data.balance;
  } catch {
    balance.value = null;
  }
}

/** 加载余额透支提醒消息（仅管理员/录入员）：GET /api/leave/messages */
async function loadMessages() {
  if (!canSeeMessages.value) return;
  try {
    const res = await leaveApi.messages();
    messages.value = res.data || [];
  } catch {
    messages.value = [];
  }
}

/** 退出登录：清空 store 里的登录态与本地 token 后回登录页 */
function doLogout() {
  logout();
  ok('已退出登录');
  router.push('/login');
}

// 小屏下侧边栏转为离屏抽屉：sidebarOpen 控制抽屉显隐
const sidebarOpen = ref(false);
function toggleSidebar() { sidebarOpen.value = !sidebarOpen.value; }

onMounted(() => {
  loadBalance();
  loadMessages();
});
</script>

<template>
  <div class="layout">
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="logo">
        <div class="logo-icon">💖</div>
        <div class="logo-text">
          <div class="logo-name">调休管家</div>
          <div class="logo-sub">加班调休管理系统</div>
        </div>
      </div>

      <el-scrollbar class="nav">
        <el-menu :default-active="activePath" router unique-opened class="side-menu">
          <template v-for="g in tree" :key="g.id">
            <!-- 有子菜单 -->
            <el-sub-menu v-if="g.kids && g.kids.length" :index="g.path || String(g.id)">
              <template #title>
                <el-icon v-if="g.icon"><component :is="g.icon" /></el-icon>
                <span>{{ g.name }}</span>
              </template>
              <el-menu-item v-for="c in g.kids" :key="c.id" :index="c.path">
                <el-icon v-if="c.icon"><component :is="c.icon" /></el-icon>
                <span>{{ c.name }}</span>
              </el-menu-item>
            </el-sub-menu>
            <!-- 单级菜单 -->
            <el-menu-item v-else :index="g.path">
              <el-icon v-if="g.icon"><component :is="g.icon" /></el-icon>
              <template #title>{{ g.name }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>

      <div class="sidebar-foot">v2.0.0 · 粉色小管家</div>
    </aside>

    <!-- 小屏抽屉打开时的遮罩层：点击关闭抽屉 -->
    <div v-if="sidebarOpen" class="sidebar-mask" @click="sidebarOpen = false"></div>

    <div class="main">
      <header class="topbar">
        <div class="topbar-left">
          <button class="menu-toggle" type="button" :aria-expanded="sidebarOpen" aria-label="展开/收起菜单" @click="toggleSidebar">☰</button>
          <div class="crumb">{{ route.meta.title || '调休管家' }}</div>
        </div>
        <div class="topbar-right">
          <!-- 余额透支提醒 -->
          <el-popover v-if="canSeeMessages" placement="bottom-end" :width="360" trigger="click">
            <template #reference>
              <div class="msg-bell">
                <el-badge :value="messages.length" :hidden="messages.length === 0" :max="99">
                  <el-icon :size="20"><Bell /></el-icon>
                </el-badge>
              </div>
            </template>
            <div class="msg-panel">
              <div class="msg-title">余额预警</div>
              <div v-if="messages.length === 0" class="msg-empty">暂无预警消息 🌸</div>
              <div v-else class="msg-list">
                <div v-for="m in messages" :key="m.id" class="msg-item">
                  <el-icon class="msg-warn"><WarningFilled /></el-icon>
                  <div class="msg-body">
                    <div class="msg-content">{{ m.content }}</div>
                    <div class="msg-time">{{ m.createdAt }}</div>
                  </div>
                </div>
              </div>
            </div>
          </el-popover>

          <!-- 管理员/录入员不展示个人调休余额 -->
          <div v-if="!isSystemAccount && balance !== null" class="mini-balance" title="我的调休余额">
            <span class="mini-dot"></span>
            余额 <b :class="{ negative: Number(balance) < 0 }">{{ balance }}</b><span class="mini-unit">h</span>
          </div>

          <div class="user-chip" @click="router.push('/profile')">
            <div class="avatar">{{ initials }}</div>
            <div class="user-meta">
              <div class="user-name">{{ auth.user?.name }}</div>
              <div class="user-role">{{ roleLabel }}</div>
            </div>
          </div>
          <el-button link type="danger" @click="doLogout">退出</el-button>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100vh; overflow: hidden; }

.sidebar {
  width: 232px; flex-shrink: 0;
  background: linear-gradient(180deg, #ffd6e7 0%, #ffc2d9 100%);
  display: flex; flex-direction: column;
  box-shadow: 2px 0 12px rgba(255,150,190,0.25);
}
.logo { display: flex; align-items: center; gap: 12px; padding: 22px 20px 16px; }
.logo-icon {
  width: 42px; height: 42px; border-radius: 14px;
  background: linear-gradient(135deg, #ff9bc2, #ff6fa5);
  display: flex; align-items: center; justify-content: center; font-size: 22px;
  box-shadow: 0 4px 12px rgba(255,111,165,0.4);
}
.logo-name { font-size: 18px; font-weight: 800; color: #c0356b; letter-spacing: 1px; }
.logo-sub { font-size: 11px; color: #b3648a; margin-top: 2px; }

.nav { flex: 1; overflow: hidden; }
.side-menu {
  border-right: none;
  background: transparent;
}
.side-menu :deep(.el-menu-item),
.side-menu :deep(.el-sub-menu__title) {
  color: #8a4a6b;
  font-weight: 500;
  border-radius: 10px;
  margin: 2px 8px;
  height: 44px;
  line-height: 44px;
}
.side-menu :deep(.el-menu-item:hover),
.side-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(255,255,255,0.55);
  color: #c0356b;
}
.side-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, #ff8fbb, #ff6fa5);
  color: #fff;
  font-weight: 700;
  box-shadow: 0 3px 10px rgba(255,111,165,0.35);
}
.sidebar-foot { padding: 14px 20px; font-size: 11px; color: #c46a93; }

.main { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.topbar {
  height: 60px; background: #fff; border-bottom: 1px solid #ffe1ee;
  display: flex; align-items: center; justify-content: space-between; padding: 0 24px; flex-shrink: 0;
}
.crumb { font-size: 16px; font-weight: 700; color: #c0356b; }
.topbar-right { display: flex; align-items: center; gap: 16px; }

.msg-bell { cursor: pointer; display: flex; align-items: center; color: #b3648a; }
.msg-bell:hover { color: #ff6fa5; }
.msg-panel { max-height: 340px; overflow-y: auto; }
.msg-title { font-weight: 700; color: #c0356b; margin-bottom: 8px; }
.msg-empty { color: #b3648a; font-size: 13px; text-align: center; padding: 16px 0; }
.msg-list { display: flex; flex-direction: column; gap: 10px; }
.msg-item { display: flex; gap: 8px; padding: 8px; background: #fff0f6; border-radius: 8px; }
.msg-warn { color: #f0a35e; margin-top: 2px; }
.msg-content { font-size: 13px; color: #7a3b58; line-height: 1.5; }
.msg-time { font-size: 11px; color: #c39ab2; margin-top: 2px; }

.mini-balance {
  background: #fff0f6; border-radius: 20px; padding: 6px 14px; font-size: 13px;
  color: #a14a72; display: flex; align-items: center; gap: 6px;
}
.mini-balance b { color: #ff5e9a; font-size: 15px; }
.mini-balance b.negative { color: #f1648f; }
.mini-dot { width: 8px; height: 8px; border-radius: 50%; background: #ff85a2; box-shadow: 0 0 0 3px rgba(255,133,162,0.18); }
.user-chip { display: flex; align-items: center; gap: 10px; cursor: pointer; padding: 6px 10px; border-radius: 10px; transition: background 0.2s; }
.user-chip:hover { background: #fff0f6; }
.avatar {
  width: 34px; height: 34px; border-radius: 50%;
  background: linear-gradient(135deg, #ffb3d1, #ff6fa5); color: #fff;
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 15px;
}
.user-name { font-size: 13.5px; font-weight: 700; color: #7a3b58; line-height: 1.2; }
.user-role { font-size: 11.5px; color: #b3648a; margin-top: 2px; }

.content { flex: 1; overflow-y: auto; padding: 22px; background: #fff7fb; }

/* ---------- 响应式：小屏侧边栏转离屏抽屉 + 顶栏换行 ---------- */
.menu-toggle { display: none; }

@media (max-width: 768px) {
  .layout { position: relative; }

  /* 侧边栏离屏抽屉化 */
  .sidebar {
    position: fixed;
    top: 0; bottom: 0; left: 0;
    z-index: 1200;
    width: 232px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: 4px 0 24px rgba(255, 150, 190, 0.35);
  }
  .sidebar.open { transform: translateX(0); }

  .sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(90, 47, 60, 0.4);
    z-index: 1100;
    animation: fadeIn 0.2s;
  }

  /* 顶栏换行，留出汉堡按钮空间 */
  .topbar {
    flex-wrap: wrap;
    height: auto;
    min-height: 60px;
    padding: 8px 14px;
    gap: 8px;
  }
  .topbar-left { display: flex; align-items: center; gap: 4px; min-width: 0; flex: 1; }
  .menu-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 38px; height: 38px;
    flex-shrink: 0;
    border: none;
    background: transparent;
    cursor: pointer;
    font-size: 20px;
    color: #c0356b;
    border-radius: 10px;
  }
  .menu-toggle:hover { background: #fff0f6; }
  .crumb { font-size: 15px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .topbar-right { flex-wrap: wrap; justify-content: flex-end; gap: 10px; }

  .content { padding: 14px; }
}

@media (max-width: 480px) {
  .topbar-right .mini-balance { display: none; }
  .user-meta { display: none; }
  .user-chip { padding: 4px; }
}
</style>

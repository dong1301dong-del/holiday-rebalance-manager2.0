import { createApp } from 'vue';
import App from './App.vue';
import router from './router';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import { permission } from './directives/permission';
import { initIdleMonitor } from './idle';
import 'element-plus/dist/index.css';
import './styles/main.css';
import './styles/element-theme.css';

/**
 * 应用入口：注册 Element Plus（含中文语言包）、全局图标、权限指令，并挂载根组件
 * 菜单图标由后端下发图标名字符串，MainLayout 用 <component :is="menu.icon" /> 动态解析，
 * 因此所有图标必须在全局注册，否则菜单图标显示不出来。
 */
const app = createApp(App);

// 注册所有 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}

// 后端菜单里少量图标名沿用旧称（Element Plus 已改名/移除），补别名，
// 保证 MainLayout 里 <component :is="menu.icon" /> 能解析到组件，图标正常显示
const ICON_ALIAS = {
  Home: 'House',       // 工作台
  Date: 'Calendar',    // 节假日日历
  Role: 'Avatar',      // 角色权限
  Config: 'Setting',   // 系统配置
};
for (const [from, to] of Object.entries(ICON_ALIAS)) {
  if (!ElementPlusIconsVue[from] && ElementPlusIconsVue[to]) {
    app.component(from, ElementPlusIconsVue[to]);
  }
}

app.use(router);
app.use(ElementPlus, { locale: zhCn, size: 'default' });
app.directive('permission', permission);
app.mount('#app');

// 启动 24 小时无操作自动退出监听（任何角色统一口径）
initIdleMonitor();

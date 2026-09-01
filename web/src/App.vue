<script setup>
/**
 * 根组件
 * 职责：只做两件事 —— 渲染路由视图（登录页或 MainLayout），以及全局挂载 toast 提示层。
 * toast 数据来自 toast.js 的 toasts 响应式数组，任何页面调用 ok/err/warn 都会在这里冒泡显示。
 */
import { toasts } from './toast';
</script>

<template>
  <router-view />
  <!-- 全局提示层：fixed 定位在最上层，样式见 styles/main.css -->
  <div class="toast-wrap">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="t.type">{{ t.message }}</div>
  </div>
</template>

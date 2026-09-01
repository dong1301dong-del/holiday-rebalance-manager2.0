import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 8601,
    proxy: {
      '/api': {
        target: 'http://localhost:8600',
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://localhost:8600',
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 8601,
    proxy: {
      '/api': {
        target: 'http://localhost:8600',
        changeOrigin: true,
      },
      '/uploads': {
        target: 'http://localhost:8600',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: false, // 环境的安全删除拦截器与 vite 清理冲突，改为覆盖写入
    chunkSizeWarningLimit: 1024,
  },
});

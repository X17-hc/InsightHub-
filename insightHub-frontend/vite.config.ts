import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5177,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  // Ant Design Vue 的日期组件会按默认导入使用 Day.js 的 UMD 插件。
  // 显式预构建可将其转换为开发服务器可消费的 ESM，避免路由懒加载失败。
  optimizeDeps: {
    include: ['dayjs', 'dayjs/plugin/advancedFormat'],
  },
  build: {
    target: 'esnext',
    minify: 'terser',
  },
})

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
  // 本机 node_modules 存在重解析点：Node 可读、esbuild 原生读到乱码。
  // 因此禁用依赖预构建，避免 Vite 启动/扫依赖时崩溃。
  optimizeDeps: {
    noDiscovery: true,
    include: [],
  },
  build: {
    target: 'esnext',
    minify: 'terser',
  },
})

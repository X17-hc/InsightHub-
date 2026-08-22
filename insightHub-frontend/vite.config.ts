import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue(), react()],
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
  // 本机 node_modules 存在重解析点：全面 discovery 时 esbuild 易读到乱码。
  // 仍需预构建 react/react-dom，否则浏览器无法从 CJS client.js 做 ESM 命名导入。
  optimizeDeps: {
    noDiscovery: true,
    include: ['react', 'react-dom', 'react/jsx-runtime', 'performative-ui'],
  },
  build: {
    target: 'esnext',
    minify: 'terser',
  },
})

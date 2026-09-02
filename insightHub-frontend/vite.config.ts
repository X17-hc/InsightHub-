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
    // Ant Design Vue 是独立、可长期缓存的 vendor chunk；阈值用于关注业务包异常增长。
    chunkSizeWarningLimit: 750,
    rollupOptions: {
      output: {
        // 将体积较大的 UI/框架依赖拆成稳定缓存块，避免所有页面共享一个近 1 MB 入口包。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('ant-design-vue') || id.includes('@ant-design')) return 'vendor-antd'
          if (id.includes('performative-ui') || id.includes('react-dom') || id.includes('/react/')) {
            return 'vendor-react'
          }
          if (id.includes('markdown-it') || id.includes('highlight.js')) return 'vendor-markdown'
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia')) return 'vendor-vue'
          return 'vendor-common'
        },
      },
    },
  },
})

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

/**
 * Markdown 渲染（暂不引入 highlight.js）。
 * 原因：本机对 .js 的重解析点会导致 Vite/esbuild 预构建 highlight.js 失败，
 * 且浏览器直接加载其 CJS 入口会报 missing default export，阻断任务详情页跳转。
 */
const props = defineProps<{ content?: string }>()
const escapeHtml = new MarkdownIt().utils.escapeHtml
const markdown: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  // 代码块仅做转义展示，避免 XSS；语法高亮待文件系统拦截解除后再加回
  highlight: (code): string => {
    return `<pre class="hljs"><code>${escapeHtml(code)}</code></pre>`
  },
})
const rendered = computed(() => markdown.render(props.content || '报告尚未生成。'))
</script>

<template><article class="markdown-body" v-html="rendered" /></template>

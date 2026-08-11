<script setup lang="ts">
import { FileText } from '@lucide/vue'
import MarkdownViewer from '@/components/MarkdownViewer.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Report } from '@/types'
import { formatDate } from '@/utils/display'

defineProps<{ report: Report | null; streamingContent: string; generating: boolean }>()
</script>
<template>
  <div class="section-title"><div><h2>研究报告</h2><p v-if="report">版本 {{ report.version }} · 更新于 {{ formatDate(report.updatedAt) }}</p><p v-else-if="streamingContent">正在接收报告片段。</p><p v-else>任务完成后在此查看 Markdown 报告。</p></div><StatusTag v-if="report" :status="report.status === 'READY' ? 'COMPLETED' : report.status"/><StatusTag v-else-if="streamingContent" status="GENERATING"/></div>
  <div v-if="report?.markdownContent || streamingContent" class="report-content"><MarkdownViewer :content="report?.markdownContent || streamingContent"/></div>
  <div v-else class="empty-state report-empty"><FileText :size="30"/><h3>{{ generating ? '报告生成中' : '报告尚未生成' }}</h3><p>研究执行完成后，最新报告会自动出现在这里。</p></div>
</template>

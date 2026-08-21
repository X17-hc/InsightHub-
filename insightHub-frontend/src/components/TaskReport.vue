<script setup lang="ts">
import { Download, FileClock, FileText } from '@lucide/vue'
import MarkdownViewer from '@/components/MarkdownViewer.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Report, TaskStatus } from '@/types'
import { formatDate } from '@/utils/display'

const props = defineProps<{ report: Report | null; streamingContent: string; status: TaskStatus }>()

const emptyCopy = () => {
  if (props.status === 'WAITING_APPROVAL') return { title: '等待计划确认', body: '确认研究计划后，Agent 才会开始收集证据并生成报告。' }
  if (props.status === 'PLANNING') return { title: '研究计划生成中', body: 'Planner 正在设计研究路径，报告将在计划批准后生成。' }
  if (props.status === 'REVIEWING') return { title: '证据质量评审中', body: 'Critic 正在核对证据覆盖和结论可信度。' }
  if (props.status === 'GENERATING') return { title: '报告生成中', body: 'Writer 正在将已验证证据整理为最终报告。' }
  if (props.status === 'FAILED') return { title: '报告未生成', body: '任务执行失败，请查看执行动态中的错误信息。' }
  return { title: '报告尚未生成', body: '研究执行完成后，最新报告会自动出现在这里。' }
}
</script>
<template>
  <div class="section-title report-title"><div><h2>研究报告</h2><p v-if="report">版本 {{ report.version }} · 更新于 {{ formatDate(report.updatedAt) }}</p><p v-else-if="streamingContent">正在接收报告片段。</p><p v-else>基于通过质量评审的证据生成。</p></div><StatusTag v-if="report" :status="report.status === 'READY' ? 'COMPLETED' : report.status"/><StatusTag v-else-if="streamingContent" status="GENERATING"/></div>
  <div class="report-tools" aria-label="报告工具"><span>更多报告能力将在服务端接口完成后开放</span><div><a-button size="small" disabled title="服务端报告版本接口待接入"><FileClock :size="13"/> 历史版本</a-button><a-button size="small" disabled title="服务端导出接口待接入"><Download :size="13"/> HTML</a-button><a-button size="small" disabled title="服务端导出接口待接入"><Download :size="13"/> PDF</a-button></div></div>
  <div v-if="report?.markdownContent || streamingContent" class="report-content"><MarkdownViewer :content="report?.markdownContent || streamingContent"/></div>
  <div v-else class="empty-state report-empty"><FileText :size="30"/><h3>{{ emptyCopy().title }}</h3><p>{{ emptyCopy().body }}</p></div>
</template>

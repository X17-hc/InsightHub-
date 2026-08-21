<script setup lang="ts">
import { Download, FileClock, FileText } from '@lucide/vue'
import { ref } from 'vue'
import { researchTaskApi } from '@/api/researchTask'
import MarkdownViewer from '@/components/MarkdownViewer.vue'
import StatusTag from '@/components/StatusTag.vue'
import type { Report, ReportVersion, TaskStatus } from '@/types'
import { formatDate } from '@/utils/display'

const props = defineProps<{ report: Report | null; streamingContent: string; status: TaskStatus; workspaceId: string; taskId: string }>()
const historyOpen = ref(false); const versions = ref<ReportVersion[]>([]); const loadingVersions = ref(false)
async function showHistory() { loadingVersions.value = true; try { versions.value = await researchTaskApi.reportVersions(props.workspaceId, props.taskId); historyOpen.value = true } finally { loadingVersions.value = false } }
async function download(type: 'html' | 'pdf') { if (!props.report) return; const blob = await researchTaskApi.reportExport(props.workspaceId, props.taskId, props.report.version, type); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `${props.report.title || 'insighthub-report'}.${type}`; link.click(); URL.revokeObjectURL(url) }
const emit = defineEmits<{ selectVersion: [version: number] }>()

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
  <div class="report-tools" aria-label="报告工具"><span>{{ report ? '可查看历史快照或导出当前不可变版本' : '报告生成后可查看历史版本和导出' }}</span><div><a-button size="small" :loading="loadingVersions" :disabled="!report" @click="showHistory"><FileClock :size="13"/> 历史版本</a-button><a-button size="small" :disabled="!report" @click="download('html')"><Download :size="13"/> HTML</a-button><a-button size="small" :disabled="!report" @click="download('pdf')"><Download :size="13"/> PDF</a-button></div></div>
  <div v-if="report?.markdownContent || streamingContent" class="report-content"><MarkdownViewer :content="report?.markdownContent || streamingContent"/></div>
  <div v-else class="empty-state report-empty"><FileText :size="30"/><h3>{{ emptyCopy().title }}</h3><p>{{ emptyCopy().body }}</p></div>
  <a-drawer v-model:open="historyOpen" title="报告历史版本" placement="right" width="360"><a-list :data-source="versions"><template #renderItem="item"><a-list-item><a-button type="link" @click="emit('selectVersion', item.version)">版本 {{ item.version }} · {{ item.title }}</a-button></a-list-item></template></a-list></a-drawer>
</template>

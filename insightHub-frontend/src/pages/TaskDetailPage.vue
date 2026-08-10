<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import message from 'ant-design-vue/es/message'
import { ArrowLeft, CircleStop, Pause, Play, RefreshCw, RotateCcw, Wifi, WifiOff } from '@lucide/vue'
import { researchTaskApi } from '@/api/researchTask'
import { ApiError } from '@/api/http'
import AppShell from '@/components/AppShell.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { readSession } from '@/services/session'
import { useStreamingReport } from '@/services/useStreamingReport'
import { useTaskEvents } from '@/services/useTaskEvents'
import { canCancelTask, canLoadReport, canPauseTask, canResumeTask, canRetryTask, formatDate, isTaskStatus, isTerminalTaskStatus } from '@/utils/display'
import type { Citation, Report, ResearchTask, TaskEvent, TaskStatus } from '@/types'
import TaskTimeline from '@/components/TaskTimeline.vue'
import TaskReport from '@/components/TaskReport.vue'
import CitationList from '@/components/CitationList.vue'

const route = useRoute(); const router = useRouter(); const workspaceId = computed(() => String(route.params.workspaceId)); const taskId = computed(() => String(route.params.taskId))
const task = ref<ResearchTask | null>(null); const report = ref<Report | null>(null); const citations = ref<Citation[]>([]); const events = ref<TaskEvent[]>([]); const activeTab = ref('activity'); const loading = ref(true); const actionLoading = ref('')
const streaming = useStreamingReport()
const streamingReport = streaming.content
const eventStream = useTaskEvents({ workspaceId: () => workspaceId.value, taskId: () => taskId.value, accessToken: () => readSession()?.accessToken, onEvent: handleEvent })
const connected = eventStream.connected
const terminal = computed(() => isTerminalTaskStatus(task.value?.status)); const canPause = computed(() => canPauseTask(task.value?.status)); const canResume = computed(() => canResumeTask(task.value?.status)); const canCancel = computed(() => canCancelTask(task.value?.status)); const canRetry = computed(() => canRetryTask(task.value?.status))

async function loadTask() {
  task.value = await researchTaskApi.get(workspaceId.value, taskId.value)
}

async function loadReport() {
  if (!task.value || !canLoadReport(task.value.status)) return
  try {
    report.value = await researchTaskApi.report(workspaceId.value, taskId.value)
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 40400) throw error
  }
}

async function loadCitations() {
  citations.value = await researchTaskApi.citations(workspaceId.value, taskId.value)
}

/** 从 REST 灌入历史事件（终态任务也能看到时间线） */
async function loadEvents() {
  const rows = await researchTaskApi.eventLog(workspaceId.value, taskId.value, 0)
  eventStream.seed(rows)
  events.value = rows.filter((event) => event.type !== 'REPORT_DELTA').sort((a, b) => (a.eventId || 0) - (b.eventId || 0))
}

async function loadAll() {
  loading.value = true
  try {
    await loadTask()
    await Promise.all([loadReport(), loadCitations(), loadEvents()])
    connectEvents()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '任务详情加载失败')
  } finally {
    loading.value = false
  }
}

/** 进行中任务再挂 SSE；已完成后仅依赖 event-log，避免空时间线 */
function connectEvents() {
  eventStream.close()
  if (!task.value || terminal.value) return
  eventStream.connect()
}

function handleEvent(event: TaskEvent) {
  if (event.type !== 'REPORT_DELTA') {
    events.value.push(event)
    events.value.sort((a, b) => a.eventId - b.eventId)
  }
  const nextStatus = (event.status || event.data?.status) as TaskStatus | undefined
  if (
    task.value
    && nextStatus
    && isTaskStatus(nextStatus)
  ) {
    task.value.status = nextStatus
  }
  if (task.value && typeof event.data?.progress === 'number') {
    task.value.progress = Number(event.data.progress)
  }
  if (event.type === 'REPORT_DELTA' && typeof event.data?.delta === 'string' && !report.value) {
    streaming.receive(event)
    if (task.value && task.value.status === 'RUNNING') {
      task.value.status = 'GENERATING'
    }
  }
  if (event.type === 'TASK_RESULT' || event.type === 'TASK_COMPLETED') {
    streaming.finish()
    void loadTask().then(() => Promise.all([loadReport(), loadCitations()]))
  }
}

async function control(action: 'pause' | 'resume' | 'cancel' | 'retry') {
  actionLoading.value = action
  try {
    const result = await researchTaskApi[action](workspaceId.value, taskId.value)
    if (task.value) task.value.status = result.status
    if (action === 'retry') {
      events.value = []
      report.value = null
      streaming.reset()
      citations.value = []
      await loadEvents()
      connectEvents()
    }
    if (['cancel', 'pause'].includes(action)) {
      message.success(action === 'cancel' ? '任务已取消' : '已发送暂停请求')
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
    await loadTask()
    await loadEvents()
    connectEvents()
  } finally {
    actionLoading.value = ''
  }
}

onMounted(loadAll)
onBeforeUnmount(() => eventStream.close())
</script>

<template><AppShell><div class="page-content task-detail-page"><PageHeader eyebrow="TASK DETAIL" :title="task?.query || '任务详情'" :description="task ? `${task.taskId} · 创建于 ${formatDate(task.createdAt)}` : '正在读取任务信息'"><template #default><a-button @click="router.push({ name: 'tasks', params: { workspaceId } })"><ArrowLeft :size="15" /> 任务列表</a-button></template></PageHeader><div v-if="loading" class="loading-section"><a-spin size="large" /></div><template v-else-if="task"><div class="task-overview soft-panel"><div class="task-state"><StatusTag :status="task.status"/><span class="task-state-copy">{{ connected ? '正在接收实时动态' : terminal ? '执行连接已结束' : '等待实时连接' }}</span><Wifi v-if="connected" :size="15" class="status-online"/><WifiOff v-else :size="15" class="status-offline"/></div><div class="task-progress"><div class="progress-heading"><span>执行进度</span><strong>{{ task.progress }}%</strong></div><a-progress :percent="task.progress" :show-info="false" status="active"/><div class="task-error" v-if="task.errorMessage"><strong>{{ task.errorCode || '执行失败' }}</strong>：{{ task.errorMessage }}</div></div><div class="task-actions"><a-button v-if="canPause" :loading="actionLoading === 'pause'" @click="control('pause')"><Pause :size="15"/> 暂停</a-button><a-button v-if="canResume" type="primary" :loading="actionLoading === 'resume'" @click="control('resume')"><Play :size="15"/> 恢复</a-button><a-popconfirm v-if="canCancel" title="确定取消这个研究任务吗？" ok-text="取消任务" cancel-text="保留任务" @confirm="control('cancel')"><a-button danger :loading="actionLoading === 'cancel'"><CircleStop :size="15"/> 取消</a-button></a-popconfirm><a-button v-if="canRetry" type="primary" :loading="actionLoading === 'retry'" @click="control('retry')"><RotateCcw :size="15"/> 重试</a-button><a-button @click="loadAll"><RefreshCw :size="15"/> 刷新</a-button></div></div><div class="mobile-tabs"><a-tabs v-model:active-key="activeTab"><a-tab-pane key="activity" tab="执行动态"/><a-tab-pane key="report" tab="研究报告"/><a-tab-pane key="citations" tab="引用来源"/></a-tabs></div><div class="detail-grid"><section class="activity-column" :class="{ 'mobile-hidden': activeTab !== 'activity' }"><div class="section-title"><div><h2>执行动态</h2><p>按事件序号实时接收，断线后自动续传。</p></div><span v-if="events.length" class="event-count">{{ events.length }} 个事件</span></div><TaskTimeline :events="events" :terminal="terminal"/></section><section class="report-column" :class="{ 'mobile-hidden': activeTab !== 'report' }"><TaskReport :report="report" :streaming-content="streamingReport" :generating="task.status === 'GENERATING'"/></section><section class="citation-column" :class="{ 'mobile-hidden': activeTab !== 'citations' }"><div class="section-title"><div><h2>引用来源</h2><p>报告中使用的可追溯材料。</p></div><span class="event-count">{{ citations.length }} 条</span></div><CitationList :citations="citations"/></section></div></template><div v-else class="empty-state"><h3>没有找到这个任务</h3><p>任务可能已被移除，或你没有访问权限。</p></div></div></AppShell></template>

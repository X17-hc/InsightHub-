<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import message from 'ant-design-vue/es/message'
import { ArrowLeft, CircleStop, FileText, Pause, Play, RefreshCw, RotateCcw, Wifi, WifiOff } from '@lucide/vue'
import { researchTaskApi } from '@/api/researchTask'
import { ApiError } from '@/api/http'
import AppShell from '@/components/AppShell.vue'
import MarkdownViewer from '@/components/MarkdownViewer.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { TaskEventSource } from '@/services/TaskEventSource'
import { readSession } from '@/services/session'
import { formatDate } from '@/utils/display'
import type { Citation, Report, ResearchTask, TaskEvent, TaskStatus } from '@/types'

const route = useRoute(); const router = useRouter(); const workspaceId = computed(() => String(route.params.workspaceId)); const taskId = computed(() => String(route.params.taskId))
const task = ref<ResearchTask | null>(null); const report = ref<Report | null>(null); const citations = ref<Citation[]>([]); const events = ref<TaskEvent[]>([]); const activeTab = ref('activity'); const loading = ref(true); const actionLoading = ref(''); const connected = ref(false); let eventSource: TaskEventSource | null = null
const terminal = computed(() => Boolean(task.value && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.value.status))); const canPause = computed(() => task.value?.status === 'RUNNING'); const canResume = computed(() => task.value?.status === 'PAUSED'); const canCancel = computed(() => Boolean(task.value && ['CREATED', 'PLANNING', 'WAITING_APPROVAL', 'RUNNING', 'PAUSING', 'PAUSED', 'REVIEWING', 'GENERATING'].includes(task.value.status))); const canRetry = computed(() => task.value?.status === 'FAILED')
const eventTitle = (event: TaskEvent) => ({ TASK_STARTED: '任务开始执行', PLAN_CREATED: '研究计划已生成', NODE_STARTED: `${event.node || '研究节点'} 开始`, NODE_COMPLETED: `${event.node || '研究节点'} 完成`, TOOL_CALLED: '调用研究工具', TOOL_COMPLETED: '工具返回结果', TASK_PAUSED: '任务已暂停', TASK_COMPLETED: '研究内容完成', TASK_FAILED: '任务执行失败', TASK_RESULT: '任务结果已写入' }[event.type] || event.type)

async function loadTask() {
  task.value = await researchTaskApi.get(workspaceId.value, taskId.value)
}

async function loadReport() {
  if (!task.value || !['COMPLETED', 'GENERATING'].includes(task.value.status)) return
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
  events.value = [...rows].sort((a, b) => (a.eventId || 0) - (b.eventId || 0))
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
  eventSource?.close()
  if (!task.value || terminal.value) return
  const session = readSession()
  if (!session?.accessToken) return
  const fromEventNo = events.value.reduce((max, item) => Math.max(max, item.eventId || 0), 0)
  eventSource = new TaskEventSource(
    workspaceId.value,
    taskId.value,
    session.accessToken,
    handleEvent,
    (value) => { connected.value = value },
    fromEventNo,
  )
  eventSource.connect()
}

function handleEvent(event: TaskEvent) {
  if (events.value.some((item) => item.eventId > 0 && item.eventId === event.eventId)) return
  events.value.push(event)
  events.value.sort((a, b) => a.eventId - b.eventId)
  const nextStatus = (event.status || event.data?.status) as TaskStatus | undefined
  if (
    task.value
    && nextStatus
    && ['CREATED', 'PLANNING', 'WAITING_APPROVAL', 'RUNNING', 'PAUSING', 'PAUSED', 'REVIEWING', 'GENERATING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(nextStatus)
  ) {
    task.value.status = nextStatus
  }
  if (task.value && typeof event.data?.progress === 'number') {
    task.value.progress = Number(event.data.progress)
  }
  if (event.type === 'TASK_RESULT' || event.type === 'TASK_COMPLETED') {
    void loadTask().then(() => loadReport())
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
onBeforeUnmount(() => eventSource?.close())
</script>

<template><AppShell><div class="page-content task-detail-page"><PageHeader eyebrow="TASK DETAIL" :title="task?.query || '任务详情'" :description="task ? `${task.taskId} · 创建于 ${formatDate(task.createdAt)}` : '正在读取任务信息'"><template #default><a-button @click="router.push({ name: 'tasks', params: { workspaceId } })"><ArrowLeft :size="15" /> 任务列表</a-button></template></PageHeader><div v-if="loading" class="loading-section"><a-spin size="large" /></div><template v-else-if="task"><div class="task-overview soft-panel"><div class="task-state"><StatusTag :status="task.status"/><span class="task-state-copy">{{ connected ? '正在接收实时动态' : terminal ? '执行连接已结束' : '等待实时连接' }}</span><Wifi v-if="connected" :size="15" class="status-online"/><WifiOff v-else :size="15" class="status-offline"/></div><div class="task-progress"><div class="progress-heading"><span>执行进度</span><strong>{{ task.progress }}%</strong></div><a-progress :percent="task.progress" :show-info="false" status="active"/><div class="task-error" v-if="task.errorMessage"><strong>{{ task.errorCode || '执行失败' }}</strong>：{{ task.errorMessage }}</div></div><div class="task-actions"><a-button v-if="canPause" :loading="actionLoading === 'pause'" @click="control('pause')"><Pause :size="15"/> 暂停</a-button><a-button v-if="canResume" type="primary" :loading="actionLoading === 'resume'" @click="control('resume')"><Play :size="15"/> 恢复</a-button><a-popconfirm v-if="canCancel" title="确定取消这个研究任务吗？" ok-text="取消任务" cancel-text="保留任务" @confirm="control('cancel')"><a-button danger :loading="actionLoading === 'cancel'"><CircleStop :size="15"/> 取消</a-button></a-popconfirm><a-button v-if="canRetry" type="primary" :loading="actionLoading === 'retry'" @click="control('retry')"><RotateCcw :size="15"/> 重试</a-button><a-button @click="loadAll"><RefreshCw :size="15"/> 刷新</a-button></div></div><div class="mobile-tabs"><a-tabs v-model:active-key="activeTab"><a-tab-pane key="activity" tab="执行动态"/><a-tab-pane key="report" tab="研究报告"/><a-tab-pane key="citations" tab="引用来源"/></a-tabs></div><div class="detail-grid"><section class="activity-column" :class="{ 'mobile-hidden': activeTab !== 'activity' }"><div class="section-title"><div><h2>执行动态</h2><p>按事件序号实时接收，断线后自动续传。</p></div><span v-if="events.length" class="event-count">{{ events.length }} 个事件</span></div><div v-if="events.length" class="timeline"><div v-for="event in events" :key="`${event.eventId}-${event.type}`" class="timeline-item"><div class="timeline-dot" :class="{ done: ['NODE_COMPLETED', 'TASK_COMPLETED', 'TASK_RESULT'].includes(event.type), failed: event.type === 'TASK_FAILED' }"/><div class="timeline-body"><div class="timeline-heading"><strong>{{ eventTitle(event) }}</strong><span>#{{ event.eventId || '—' }}</span></div><div class="timeline-meta">{{ event.node || '系统' }} · {{ formatDate(event.timestamp) }}</div><p v-if="event.data?.message" class="timeline-message">{{ event.data.message }}</p></div></div></div><div v-else class="empty-state activity-empty"><FileText :size="28"/><h3>{{ terminal ? '暂无执行事件' : '等待执行动态' }}</h3><p>{{ terminal ? '该任务未落库任何事件，或执行过程中未成功写入。' : '任务开始后，节点和工具事件会显示在这里。' }}</p></div></section><section class="report-column" :class="{ 'mobile-hidden': activeTab !== 'report' }"><div class="section-title"><div><h2>研究报告</h2><p v-if="report">版本 {{ report.version }} · 更新于 {{ formatDate(report.updatedAt) }}</p><p v-else>任务完成后在此查看 Markdown 报告。</p></div><StatusTag v-if="report" :status="report.status === 'READY' ? 'COMPLETED' : report.status"/></div><div v-if="report" class="report-content"><MarkdownViewer :content="report.markdownContent"/></div><div v-else class="empty-state report-empty"><FileText :size="30"/><h3>{{ task.status === 'GENERATING' ? '报告生成中' : '报告尚未生成' }}</h3><p>研究执行完成后，最新报告会自动出现在这里。</p></div></section><section class="citation-column" :class="{ 'mobile-hidden': activeTab !== 'citations' }"><div class="section-title"><div><h2>引用来源</h2><p>报告中使用的可追溯材料。</p></div><span class="event-count">{{ citations.length }} 条</span></div><div v-if="citations.length" class="citation-list"><article v-for="citation in citations" :key="citation.id" class="citation-item"><div class="citation-number">[{{ citation.citationNo }}]</div><div class="citation-copy"><strong>{{ citation.sourceTitle || '未命名来源' }}</strong><a v-if="citation.sourceUri" :href="citation.sourceUri" target="_blank" rel="noreferrer">{{ citation.sourceUri }}</a><p v-if="citation.quotedText">“{{ citation.quotedText }}”</p><span class="citation-type">{{ citation.sourceType || '资料' }} · {{ citation.verified ? '已核验' : '待核验' }}</span></div></article></div><div v-else class="empty-state report-empty"><FileText :size="28"/><h3>暂无引用</h3><p>报告生成后，引用来源会按编号列出。</p></div></section></div></template><div v-else class="empty-state"><h3>没有找到这个任务</h3><p>任务可能已被移除，或你没有访问权限。</p></div></div></AppShell></template>

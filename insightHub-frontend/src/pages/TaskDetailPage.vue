<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import message from 'ant-design-vue/es/message'
import { ArrowLeft, BarChart3, CircleStop, Database, Pause, Play, RefreshCw, RotateCcw, Wifi, WifiOff } from '@lucide/vue'
import { researchTaskApi } from '@/api/researchTask'
import { ApiError } from '@/api/http'
import AppShell from '@/components/AppShell.vue'
import CitationList from '@/components/CitationList.vue'
import CriticSummaryCard from '@/components/CriticSummaryCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import PlanApprovalModal from '@/components/PlanApprovalModal.vue'
import PlanHistoryDrawer from '@/components/PlanHistoryDrawer.vue'
import PlanRevisionModal from '@/components/PlanRevisionModal.vue'
import StatusTag from '@/components/StatusTag.vue'
import TaskPlanPanel from '@/components/TaskPlanPanel.vue'
import TaskReport from '@/components/TaskReport.vue'
import TaskTimeline from '@/components/TaskTimeline.vue'
import { readSession } from '@/services/session'
import { useStreamingReport } from '@/services/useStreamingReport'
import { useTaskEvents } from '@/services/useTaskEvents'
import { canCancelTask, canLoadReport, canPauseTask, canResumeTask, canRetryTask, formatDate, isTaskStatus, isTerminalTaskStatus, qualityStatusMeta } from '@/utils/display'
import type { AnalysisArtifact, Citation, CritiqueResult, CriticVerdict, PlanRevision, Report, ResearchTask, TaskEvent, TaskStatus } from '@/types'

const route = useRoute()
const router = useRouter()
const workspaceId = computed(() => String(route.params.workspaceId))
const taskId = computed(() => String(route.params.taskId))
const task = ref<ResearchTask | null>(null)
const report = ref<Report | null>(null)
const citations = ref<Citation[]>([])
const artifacts = ref<AnalysisArtifact[]>([])
const artifactState = ref<'loading' | 'disabled' | 'empty' | 'ready' | 'error'>('loading')
const artifactError = ref('')
const previewedArtifact = ref<AnalysisArtifact | null>(null)
const artifactPreviewUrl = ref('')
const artifactPreviewOpen = ref(false)
const events = ref<TaskEvent[]>([])
const currentPlan = ref<PlanRevision | null>(null)
const planHistory = ref<PlanRevision[]>([])
const critique = ref<CritiqueResult | null>(null)
const criticReviewing = ref(false)
const criticSupplementing = ref(false)
const activeTab = ref('activity')
const loading = ref(true)
const actionLoading = ref('')
const planLoading = ref(false)
const planActionLoading = ref<'' | 'approve' | 'revise'>('')
const planApprovalOpen = ref(false)
const planRevisionOpen = ref(false)
const planHistoryOpen = ref(false)
const reportVersionLoading = ref(false)

const streaming = useStreamingReport()
const streamingReport = streaming.content
const eventStream = useTaskEvents({ workspaceId: () => workspaceId.value, taskId: () => taskId.value, accessToken: () => readSession()?.accessToken, onEvent: handleEvent })
const connected = eventStream.connected
const terminal = computed(() => isTerminalTaskStatus(task.value?.status))
const isCreator = computed(() => Boolean(task.value && task.value.creatorId === readSession()?.userId))
const canPause = computed(() => canPauseTask(task.value?.status) && !planActionLoading.value)
const canResume = computed(() => canResumeTask(task.value?.status) && !planActionLoading.value)
const canCancel = computed(() => canCancelTask(task.value?.status) && !planActionLoading.value)
const canRetry = computed(() => canRetryTask(task.value?.status, task.value?.qualityStatus) && !planActionLoading.value)
const verifiedCitationCount = computed(() => citations.value.filter((item) => item.verificationStatus === 'VERIFIED').length)
const planTaskStates = computed<Record<string, string>>(() => {
  const states: Record<string, string> = {}
  const labels: Record<string, string> = { PLAN_TASK_STARTED: '执行中', PLAN_TASK_COMPLETED: '已完成', PLAN_TASK_FAILED: '失败', PLAN_TASK_SKIPPED: '依赖失败，已跳过' }
  events.value.forEach((event) => {
    const planTaskId = typeof event.data?.planTaskId === 'string' ? event.data.planTaskId : undefined
    if (planTaskId && labels[event.type]) states[planTaskId] = labels[event.type]
  })
  return states
})

function isExpectedPlanAbsence(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  return error.code === 40400 || error.code === 40900 || error.message.includes('PLAN_GENERATING') || error.message.includes('plan not found')
}

function businessReason(error: unknown): string {
  if (!(error instanceof Error)) return '操作失败'
  if (error.message.includes('PLAN_VERSION_STALE')) return '计划已更新，请确认最新版本'
  if (error.message.includes('PLAN_ALREADY_CHANGED')) return '该计划已被其他操作处理'
  if (error.message.includes('PLAN_NOT_WAITING')) return '任务状态已变化，请查看最新状态'
  if (error.message.includes('only task creator') || (error instanceof ApiError && error.code === 40300)) return '只有任务创建人可以确认或修订计划'
  return error.message
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function parseCritique(event: TaskEvent): CritiqueResult | null {
  const data = event.data || {}
  const verdict = data.verdict
  if (!['PASS', 'SUPPLEMENT', 'FAIL'].includes(String(verdict))) return null
  return {
    verdict: verdict as CriticVerdict,
    summary: typeof data.summary === 'string' ? data.summary : undefined,
    gaps: stringArray(data.gaps),
    limitations: stringArray(data.limitations),
    supplementTaskCount: typeof data.supplementTaskCount === 'number' ? data.supplementTaskCount : 0,
    criticRound: typeof data.criticRound === 'number' ? data.criticRound : undefined,
    maxCriticRounds: typeof data.maxCriticRounds === 'number' ? data.maxCriticRounds : undefined,
  }
}

function restoreCriticState(rows: TaskEvent[]) {
  critique.value = null
  criticReviewing.value = false
  criticSupplementing.value = false
  rows.forEach((event) => {
    if (event.type === 'CRITIC_STARTED') {
      criticReviewing.value = true
      criticSupplementing.value = false
    } else if (event.type === 'CRITIQUE_COMPLETED') {
      critique.value = parseCritique(event) || critique.value
      criticReviewing.value = false
      criticSupplementing.value = false
    } else if (event.type === 'SUPPLEMENT_RESEARCH_REQUESTED') {
      criticSupplementing.value = true
      criticReviewing.value = false
    } else if (event.type === 'TASK_RESULT' || event.type === 'TASK_FAILED') {
      criticReviewing.value = false
      criticSupplementing.value = false
    }
  })
}

function mergeEvent(event: TaskEvent) {
  if (event.type === 'REPORT_DELTA') return
  const existingIndex = events.value.findIndex((item) => item.eventId === event.eventId)
  if (existingIndex >= 0) events.value[existingIndex] = event
  else events.value.push(event)
  events.value.sort((left, right) => left.eventId - right.eventId)
}

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

function artifactReason(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error || '')
  if (raw.includes('SANDBOX_UNAVAILABLE')) return 'Sandbox 当前不可用，请检查 Ubuntu Agent 与 Docker 镜像。'
  if (raw.includes('AGENT_UNAVAILABLE')) return 'Ubuntu Agent 当前不可用或正在启动，请稍后重试。'
  if (raw.includes('AGENT_AUTH_FAILED')) return 'Java 与 Ubuntu Agent 的内部令牌不一致，请检查环境变量。'
  if (raw.includes('ARTIFACT_MIME_REJECTED')) return '产物类型未通过安全校验。'
  if (raw.includes('ARTIFACT_TOO_LARGE')) return '产物超过允许的下载大小。'
  if (error instanceof ApiError && error.code === 40300) return '无权访问该工作空间的产物。'
  if (error instanceof ApiError && error.code === 40400) return '产物不存在或已失效。'
  return raw || '产物服务不可用'
}

async function loadLatestReportAndCitations() {
  await loadReport()
  if (!report.value) { citations.value = []; return }
  try {
    citations.value = await researchTaskApi.reportCitations(workspaceId.value, taskId.value, report.value.version)
  } catch (error) {
    citations.value = []
    message.warning(error instanceof Error ? `引用加载失败：${error.message}` : '引用加载失败')
  }
}

async function selectReportVersion(version: number) {
  if (reportVersionLoading.value || report.value?.version === version) return
  reportVersionLoading.value = true
  try {
    const [nextReport, nextCitations] = await Promise.all([
      researchTaskApi.reportVersion(workspaceId.value, taskId.value, version),
      researchTaskApi.reportCitations(workspaceId.value, taskId.value, version),
    ])
    report.value = nextReport
    citations.value = nextCitations
  }
  catch (error) { message.error(error instanceof Error ? error.message : '报告版本加载失败') }
  finally { reportVersionLoading.value = false }
}

async function loadArtifacts() {
  artifactState.value = 'loading'; artifactError.value = ''
  if (task.value?.enableDataAnalysis === false) { artifacts.value = []; artifactState.value = 'disabled'; return }
  try {
    artifacts.value = await researchTaskApi.artifacts(workspaceId.value, taskId.value)
    artifactState.value = artifacts.value.length ? 'ready' : 'empty'
  } catch (error) {
    artifacts.value = []
    artifactState.value = 'error'
    artifactError.value = artifactReason(error)
  }
}

async function downloadArtifact(artifact: AnalysisArtifact) {
  try {
    const blob = await researchTaskApi.artifactContent(workspaceId.value, taskId.value, artifact.id, 'attachment')
    const url = URL.createObjectURL(blob); const link = document.createElement('a')
    link.href = url; link.download = artifact.fileName; link.click(); URL.revokeObjectURL(url)
  } catch (error) { message.error(artifactReason(error)) }
}

function canPreviewArtifact(artifact: AnalysisArtifact): boolean {
  return artifact.mimeType === 'image/png' || artifact.mimeType === 'image/svg+xml'
}

function releaseArtifactPreview() {
  if (artifactPreviewUrl.value) URL.revokeObjectURL(artifactPreviewUrl.value)
  artifactPreviewUrl.value = ''
  previewedArtifact.value = null
}

async function previewArtifact(artifact: AnalysisArtifact) {
  if (!canPreviewArtifact(artifact)) return
  releaseArtifactPreview()
  try {
    const blob = await researchTaskApi.artifactContent(workspaceId.value, taskId.value, artifact.id, 'inline')
    artifactPreviewUrl.value = URL.createObjectURL(blob)
    previewedArtifact.value = artifact
    artifactPreviewOpen.value = true
  } catch (error) {
    message.error(artifactReason(error))
  }
}

async function loadEvents() {
  try {
    const rows = await researchTaskApi.eventLog(workspaceId.value, taskId.value, 0)
    eventStream.seed(rows)
    events.value = rows.filter((event) => event.type !== 'REPORT_DELTA').sort((left, right) => left.eventId - right.eventId)
    restoreCriticState(events.value)
  } catch (error) {
    message.warning(error instanceof Error ? `执行动态加载失败：${error.message}` : '执行动态加载失败')
  }
}

async function loadPlans() {
  planLoading.value = true
  try {
    const historyPromise = researchTaskApi.planHistory(workspaceId.value, taskId.value)
      .then((rows) => { planHistory.value = [...rows].sort((left, right) => right.revisionNo - left.revisionNo) })
      .catch((error) => { if (!isExpectedPlanAbsence(error)) throw error })
    const currentPromise = researchTaskApi.currentPlan(workspaceId.value, taskId.value)
      .then((plan) => { currentPlan.value = plan })
      .catch((error) => { if (!isExpectedPlanAbsence(error)) throw error })
    await Promise.all([historyPromise, currentPromise])
  } catch (error) {
    message.warning(error instanceof Error ? `研究计划加载失败：${error.message}` : '研究计划加载失败')
  } finally {
    planLoading.value = false
  }
}

function selectInitialTab() {
  if (task.value?.status === 'WAITING_APPROVAL' || task.value?.status === 'PLANNING') activeTab.value = 'plan'
  else if (task.value?.status === 'COMPLETED') activeTab.value = 'report'
  else activeTab.value = 'activity'
}

async function loadAll() {
  loading.value = true
  try {
    await loadTask()
    selectInitialTab()
    await Promise.all([loadLatestReportAndCitations(), loadArtifacts(), loadEvents(), loadPlans()])
    connectEvents()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '任务详情加载失败')
  } finally {
    loading.value = false
  }
}

function connectEvents() {
  eventStream.close()
  if (!task.value || terminal.value) return
  eventStream.connect()
}

async function refreshAfterResult() {
  await loadTask()
  await Promise.all([loadLatestReportAndCitations(), loadArtifacts(), loadPlans()])
  if (terminal.value) eventStream.close()
}

function handleEvent(event: TaskEvent) {
  mergeEvent(event)
  const nextStatus = (event.status || event.data?.status) as TaskStatus | undefined
  if (task.value && nextStatus && isTaskStatus(nextStatus)) task.value.status = nextStatus
  if (task.value && typeof event.data?.progress === 'number') task.value.progress = Number(event.data.progress)
  if (event.type === 'PLAN_CREATED' || event.type === 'APPROVAL_REQUIRED') {
    if (task.value) task.value.status = 'WAITING_APPROVAL'
    activeTab.value = 'plan'
    void loadPlans()
  } else if (event.type === 'PLAN_REVISED') {
    if (task.value) task.value.status = 'PLANNING'
    currentPlan.value = null
    planLoading.value = true
    activeTab.value = 'plan'
    void loadPlans()
  } else if (event.type === 'CRITIC_STARTED') {
    criticReviewing.value = true
    criticSupplementing.value = false
  } else if (event.type === 'CRITIQUE_COMPLETED') {
    critique.value = parseCritique(event) || critique.value
    criticReviewing.value = false
    criticSupplementing.value = false
  } else if (event.type === 'SUPPLEMENT_RESEARCH_REQUESTED') {
    criticReviewing.value = false
    criticSupplementing.value = true
  }
  if (event.type === 'REPORT_DELTA' && typeof event.data?.delta === 'string' && !report.value) {
    streaming.receive(event)
    if (task.value && task.value.status === 'RUNNING') task.value.status = 'GENERATING'
  }
  if (event.type === 'TASK_FAILED') {
    streaming.finish()
    criticReviewing.value = false
    criticSupplementing.value = false
    if (task.value) {
      task.value.status = 'FAILED'
      if (typeof event.data?.code === 'string') task.value.errorCode = event.data.code
      if (typeof event.data?.message === 'string') task.value.errorMessage = event.data.message
    }
    // Keep the stream open: TASK_RESULT follows with the persisted terminal snapshot.
  }
  if (event.type === 'TASK_RESULT') {
    streaming.finish()
    criticReviewing.value = false
    criticSupplementing.value = false
    void refreshAfterResult()
  }
}

async function refreshPlanState() {
  await Promise.all([loadTask(), loadPlans(), loadEvents()])
  connectEvents()
}

async function approvePlan(remark: string) {
  if (!currentPlan.value || planActionLoading.value) return
  planActionLoading.value = 'approve'
  try {
    const result = await researchTaskApi.approvePlan(workspaceId.value, taskId.value, { expectedRevision: currentPlan.value.revisionNo, remark: remark || undefined })
    planApprovalOpen.value = false
    if (task.value) { task.value.status = result.status; task.value.runId = result.runId }
    currentPlan.value.status = 'APPROVED'
    message.success('计划已确认，研究任务开始执行')
    await refreshPlanState()
  } catch (error) {
    message.error(businessReason(error))
    if (error instanceof ApiError && (error.code === 40300 || error.code === 40900)) await refreshPlanState()
  } finally {
    planActionLoading.value = ''
  }
}

async function revisePlan(revision: string) {
  if (!currentPlan.value || planActionLoading.value) return
  planActionLoading.value = 'revise'
  try {
    const result = await researchTaskApi.revisePlan(workspaceId.value, taskId.value, { expectedRevision: currentPlan.value.revisionNo, revision })
    planRevisionOpen.value = false
    if (task.value) { task.value.status = result.status; task.value.runId = result.runId }
    currentPlan.value = null
    planLoading.value = true
    message.success('修订意见已提交，Planner 正在生成新计划')
    await Promise.all([loadEvents(), loadPlans()])
    connectEvents()
  } catch (error) {
    message.error(businessReason(error))
    if (error instanceof ApiError && (error.code === 40300 || error.code === 40900)) await refreshPlanState()
  } finally {
    planActionLoading.value = ''
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
      critique.value = null
      streaming.reset()
      citations.value = []
      await Promise.all([loadEvents(), loadPlans()])
      connectEvents()
    }
    if (['cancel', 'pause'].includes(action)) message.success(action === 'cancel' ? '任务已取消' : '已发送暂停请求')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '操作失败')
    await Promise.all([loadTask(), loadEvents()])
    connectEvents()
  } finally {
    actionLoading.value = ''
  }
}

onMounted(loadAll)
onBeforeUnmount(() => {
  eventStream.close()
  releaseArtifactPreview()
})
</script>

<template>
  <AppShell>
    <div class="page-content task-detail-page">
      <PageHeader eyebrow="TASK DETAIL" :title="task?.query || '任务详情'" :description="task ? `${task.taskId} · 创建于 ${formatDate(task.createdAt)}` : '正在读取任务信息'">
        <template #default><a-button @click="router.push({ name: 'tasks', params: { workspaceId } })"><ArrowLeft :size="15" /> 任务列表</a-button></template>
      </PageHeader>
      <div v-if="loading" class="loading-section"><a-spin size="large" /></div>
      <template v-else-if="task">
        <div class="task-overview soft-panel">
          <div class="task-state"><StatusTag :status="task.status"/><a-tag :color="qualityStatusMeta[task.qualityStatus]?.color">{{ qualityStatusMeta[task.qualityStatus]?.label || task.qualityStatus }}</a-tag><span class="task-state-copy">{{ task.status === 'WAITING_APPROVAL' ? '等待创建人确认计划' : connected ? '正在接收实时动态' : terminal ? '执行连接已结束' : '等待实时连接' }}</span><Wifi v-if="connected" :size="15" class="status-online"/><WifiOff v-else :size="15" class="status-offline"/></div>
          <div class="task-progress"><div class="progress-heading"><span>执行进度</span><strong>{{ task.progress }}%</strong></div><a-progress :percent="task.progress" :show-info="false" status="active"/><div v-if="task.errorMessage" class="task-error"><strong>{{ task.errorCode || '执行失败' }}</strong>：{{ task.errorMessage }}</div><div v-else-if="task.qualityStatus === 'FAIL' && task.qualitySummary" class="task-error"><strong>执行完成 · 质量未通过</strong>：{{ task.qualitySummary }}</div></div>
          <div class="task-actions"><a-button v-if="canPause" :loading="actionLoading === 'pause'" @click="control('pause')"><Pause :size="15"/> 暂停</a-button><a-button v-if="canResume" type="primary" :loading="actionLoading === 'resume'" @click="control('resume')"><Play :size="15"/> 恢复</a-button><a-popconfirm v-if="canCancel" title="确定取消这个研究任务吗？" ok-text="取消任务" cancel-text="保留任务" @confirm="control('cancel')"><a-button danger :loading="actionLoading === 'cancel'"><CircleStop :size="15"/> 取消</a-button></a-popconfirm><a-button v-if="canRetry" type="primary" :loading="actionLoading === 'retry'" @click="control('retry')"><RotateCcw :size="15"/> 重试</a-button><a-button :disabled="Boolean(planActionLoading)" @click="loadAll"><RefreshCw :size="15"/> 刷新</a-button></div>
        </div>
        <div class="mobile-tabs"><a-tabs v-model:active-key="activeTab"><a-tab-pane key="plan" tab="计划"/><a-tab-pane key="activity" tab="动态"/><a-tab-pane key="report" tab="报告"/><a-tab-pane key="sources" tab="来源"/></a-tabs></div>
        <div class="detail-grid detail-grid-week5">
          <section class="plan-activity-column" :class="{ 'mobile-hidden': !['plan', 'activity'].includes(activeTab) }">
            <div :class="{ 'mobile-hidden': activeTab !== 'plan' }"><TaskPlanPanel :plan="currentPlan" :task-status="task.status" :loading="planLoading" :is-creator="isCreator" :action-loading="planActionLoading" :history-count="planHistory.length" :task-states="planTaskStates" @approve="planApprovalOpen = true" @revise="planRevisionOpen = true" @show-history="planHistoryOpen = true"/></div>
            <div class="activity-section" :class="{ 'mobile-hidden': activeTab !== 'activity' }"><div class="section-title"><div><h2>执行动态</h2><p>按事件序号实时接收，断线后自动续传。</p></div><span v-if="events.length" class="event-count">{{ events.length }} 个事件</span></div><TaskTimeline :events="events" :terminal="terminal"/></div>
          </section>
          <section class="report-column" :class="{ 'mobile-hidden': activeTab !== 'report' }"><div v-if="report?.qualityStatus === 'LEGACY_SYNTHETIC'" class="task-error"><strong>历史演示数据</strong>：该报告使用合成来源，不代表真实研究结果。请使用真实检索重试。</div><TaskReport :report="report" :streaming-content="streamingReport" :status="task.status" :workspace-id="workspaceId" :task-id="taskId" :version-loading="reportVersionLoading" @select-version="selectReportVersion"/></section>
          <section class="quality-column" :class="{ 'mobile-hidden': activeTab !== 'sources' }">
            <CriticSummaryCard :critique="critique" :reviewing="criticReviewing" :supplementing="criticSupplementing" :terminal="terminal"/>
            <div class="section-title citation-heading"><div><h2>引用来源</h2><p>当前报告版本的可追溯材料。</p></div><span class="event-count">真实来源 {{ verifiedCitationCount }} 条</span></div>
            <CitationList :citations="citations"/>
            <div class="future-features"><div class="soft-panel"><div class="section-title"><div><h2><BarChart3 :size="18"/> 分析产物</h2><p>受限 Sandbox 生成的表格与图表。</p></div><span>{{ artifacts.length }} 项</span></div><a-spin v-if="artifactState === 'loading'"/><a-list v-else-if="artifactState === 'ready'" size="small" :data-source="artifacts"><template #renderItem="{ item }"><a-list-item><span>{{ item.title || item.fileName }} · {{ item.mimeType }}</span><div><a-button v-if="canPreviewArtifact(item)" type="link" @click="previewArtifact(item)">预览</a-button><a-button type="link" @click="downloadArtifact(item)">下载</a-button></div></a-list-item></template></a-list><p v-else-if="artifactState === 'disabled'">本任务创建时未启用分析产物。</p><p v-else-if="artifactState === 'empty'">分析已启用，但没有生成可用产物。</p><p v-else class="task-error"><strong>产物加载失败</strong>：{{ artifactError }}</p></div><div class="soft-panel"><div class="section-title"><div><h2><Database :size="18"/> 报告版本与导出</h2><p>历史版本和 HTML/PDF 导出位于报告区域。</p></div></div></div></div>
          </section>
        </div>
      </template>
      <div v-else class="empty-state"><h3>没有找到这个任务</h3><p>任务可能已被移除，或你没有访问权限。</p></div>
    </div>
    <PlanApprovalModal v-model:open="planApprovalOpen" :plan="currentPlan" :loading="planActionLoading === 'approve'" @submit="approvePlan"/>
    <PlanRevisionModal v-model:open="planRevisionOpen" :plan="currentPlan" :loading="planActionLoading === 'revise'" @submit="revisePlan"/>
    <PlanHistoryDrawer v-model:open="planHistoryOpen" :plans="planHistory" :current-id="currentPlan?.id"/>
    <a-modal v-model:open="artifactPreviewOpen" :title="previewedArtifact?.title || previewedArtifact?.fileName" :footer="null" @after-close="releaseArtifactPreview"><img v-if="artifactPreviewUrl" :src="artifactPreviewUrl" :alt="previewedArtifact?.title || previewedArtifact?.fileName" style="display:block;max-width:100%;max-height:70vh;margin:auto" /></a-modal>
  </AppShell>
</template>

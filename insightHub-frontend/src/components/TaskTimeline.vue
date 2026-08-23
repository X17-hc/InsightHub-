<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileText } from '@lucide/vue'
import type { TaskEvent } from '@/types'
import { formatDate } from '@/utils/display'

const props = defineProps<{ events: TaskEvent[]; terminal: boolean }>()
const filter = ref<'all' | 'warnings' | 'tools'>('all')
const title = (event: TaskEvent) => ({
  TASK_STARTED: '任务开始执行',
  PLAN_CREATED: '研究计划已生成',
  APPROVAL_REQUIRED: '等待计划确认',
  PLAN_REVISED: '已提交计划修订',
  PLAN_APPROVED: '研究计划已批准',
  PLAN_TASK_STARTED: '计划节点开始',
  PLAN_TASK_COMPLETED: '计划节点完成',
  PLAN_TASK_FAILED: '计划节点失败',
  PLAN_TASK_SKIPPED: '计划节点跳过',
  WEB_SEARCH_FAILED: '真实网络检索失败',
  SOURCE_FETCH_FAILED: '候选网页抓取失败',
  NODE_STARTED: `${event.node || '研究节点'} 开始`,
  NODE_COMPLETED: `${event.node || '研究节点'} 完成`,
  TOOL_CALLED: '调用研究工具',
  TOOL_COMPLETED: '工具返回结果',
  CRITIC_STARTED: 'Critic 开始评审',
  CRITIQUE_COMPLETED: 'Critic 评审完成',
  SUPPLEMENT_RESEARCH_REQUESTED: '请求补充研究',
  REPORT_VERSION_CREATED: '报告版本已创建',
  SANDBOX_STARTED: '数据分析已开始',
  SANDBOX_COMPLETED: '数据分析已完成',
  SANDBOX_FAILED: '数据分析失败',
  REPORT_DELTA: '报告内容更新',
  TASK_PAUSED: '任务已暂停',
  TASK_COMPLETED: '研究内容完成',
  TASK_FAILED: '任务执行失败',
  TASK_RESULT: '任务结果已写入',
}[event.type] || event.type)

const detail = (event: TaskEvent) => {
  const data = event.data || {}
  if (typeof data.message === 'string') return data.message
  if (event.type === 'CRITIQUE_COMPLETED' && typeof data.verdict === 'string') {
    const round = typeof data.criticRound === 'number' ? `第 ${data.criticRound} 轮 · ` : ''
    const supplement = typeof data.supplementTaskCount === 'number' && data.supplementTaskCount > 0 ? ` · ${data.supplementTaskCount} 个补充任务` : ''
    return `${round}评审结果 ${data.verdict}${supplement}`
  }
  if (event.type === 'PLAN_REVISED') return `修订版 ${String(data.previousRevision ?? '—')} → ${String(data.nextRevision ?? '—')}`
  if (event.type === 'APPROVAL_REQUIRED' && data.planRevision) return `修订版 ${String(data.planRevision)} 等待创建人确认`
  if (event.type === 'SANDBOX_FAILED' && typeof data.error === 'string') return data.error
  return ''
}

const tone = (event: TaskEvent) => ({
  done: ['NODE_COMPLETED', 'TASK_COMPLETED', 'TASK_RESULT', 'SANDBOX_COMPLETED'].includes(event.type),
  failed: ['TASK_FAILED', 'SANDBOX_FAILED', 'WEB_SEARCH_FAILED', 'PLAN_TASK_FAILED'].includes(event.type),
  warning: ['APPROVAL_REQUIRED', 'SUPPLEMENT_RESEARCH_REQUESTED', 'PLAN_TASK_SKIPPED', 'SOURCE_FETCH_FAILED'].includes(event.type),
})

const warningTypes = new Set(['TASK_FAILED', 'SANDBOX_FAILED', 'WEB_SEARCH_FAILED', 'SOURCE_FETCH_FAILED', 'PLAN_TASK_FAILED', 'PLAN_TASK_SKIPPED', 'APPROVAL_REQUIRED', 'SUPPLEMENT_RESEARCH_REQUESTED'])
const toolTypes = new Set(['TOOL_CALLED', 'TOOL_COMPLETED'])
const stage = (event: TaskEvent) => {
  if (/PLAN|APPROVAL/.test(event.type)) return '计划与审批'
  if (/SEARCH|TOOL|EVIDENCE/.test(event.type) || ['knowledge_research', 'web_research', 'merge_evidence'].includes(event.node || '')) return '证据检索'
  if (/CRITIC|CRITIQUE|SUPPLEMENT/.test(event.type)) return '质量评审'
  return '报告与产物'
}
const groups = computed(() => {
  const selected = props.events.filter((event) => filter.value === 'all' || (filter.value === 'warnings' ? warningTypes.has(event.type) : toolTypes.has(event.type)))
  return ['计划与审批', '证据检索', '质量评审', '报告与产物'].map((name) => ({ name, events: selected.filter((event) => stage(event) === name) })).filter((group) => group.events.length)
})
</script>
<template>
  <div v-if="events.length"><a-radio-group v-model:value="filter" size="small" style="margin-bottom:12px"><a-radio-button value="all">全部</a-radio-button><a-radio-button value="warnings">警告与失败</a-radio-button><a-radio-button value="tools">工具调用</a-radio-button></a-radio-group><div v-if="groups.length"><details v-for="group in groups" :key="group.name" :open="group.name !== '证据检索'" class="timeline-group"><summary>{{ group.name }}（{{ group.events.length }}）</summary><div class="timeline"><div v-for="event in group.events" :key="`${event.eventId}-${event.type}`" class="timeline-item"><div class="timeline-dot" :class="tone(event)"/><div class="timeline-body"><div class="timeline-heading"><strong>{{ title(event) }}</strong><span>#{{ event.eventId || '—' }}</span></div><div class="timeline-meta">{{ event.node || '系统' }} · {{ formatDate(event.timestamp) }}</div><p v-if="detail(event)" class="timeline-message">{{ detail(event) }}</p></div></div></div></details></div><div v-else class="empty-state activity-empty"><p>当前筛选条件下没有事件。</p></div></div>
  <div v-else class="empty-state activity-empty"><FileText :size="28"/><h3>{{ terminal ? '暂无执行事件' : '等待执行动态' }}</h3><p>{{ terminal ? '该任务未落库任何事件，或执行过程中未成功写入。' : '任务开始后，节点和工具事件会显示在这里。' }}</p></div>
</template>

<style scoped>.timeline-group { margin-bottom: 10px; }.timeline-group > summary { cursor: pointer; margin-bottom: 8px; color: #506d5e; font-size: 12px; font-weight: 700; }</style>

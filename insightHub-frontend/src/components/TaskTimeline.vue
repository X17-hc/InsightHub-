<script setup lang="ts">
import { FileText } from '@lucide/vue'
import type { TaskEvent } from '@/types'
import { formatDate } from '@/utils/display'

defineProps<{ events: TaskEvent[]; terminal: boolean }>()
const title = (event: TaskEvent) => ({
  TASK_STARTED: '任务开始执行',
  PLAN_CREATED: '研究计划已生成',
  APPROVAL_REQUIRED: '等待计划确认',
  NODE_STARTED: `${event.node || '研究节点'} 开始`,
  NODE_COMPLETED: `${event.node || '研究节点'} 完成`,
  TOOL_CALLED: '调用研究工具',
  TOOL_COMPLETED: '工具返回结果',
  CRITIC_STARTED: 'Critic 开始评审',
  CRITIQUE_COMPLETED: 'Critic 评审完成',
  SUPPLEMENT_RESEARCH_REQUESTED: '请求补充研究',
  REPORT_DELTA: '报告内容更新',
  TASK_PAUSED: '任务已暂停',
  TASK_COMPLETED: '研究内容完成',
  TASK_FAILED: '任务执行失败',
  TASK_RESULT: '任务结果已写入',
}[event.type] || event.type)
</script>
<template>
  <div v-if="events.length" class="timeline"><div v-for="event in events" :key="`${event.eventId}-${event.type}`" class="timeline-item"><div class="timeline-dot" :class="{ done: ['NODE_COMPLETED', 'TASK_COMPLETED', 'TASK_RESULT'].includes(event.type), failed: event.type === 'TASK_FAILED' }"/><div class="timeline-body"><div class="timeline-heading"><strong>{{ title(event) }}</strong><span>#{{ event.eventId || '—' }}</span></div><div class="timeline-meta">{{ event.node || '系统' }} · {{ formatDate(event.timestamp) }}</div><p v-if="event.data?.message" class="timeline-message">{{ event.data.message }}</p></div></div></div>
  <div v-else class="empty-state activity-empty"><FileText :size="28"/><h3>{{ terminal ? '暂无执行事件' : '等待执行动态' }}</h3><p>{{ terminal ? '该任务未落库任何事件，或执行过程中未成功写入。' : '任务开始后，节点和工具事件会显示在这里。' }}</p></div>
</template>

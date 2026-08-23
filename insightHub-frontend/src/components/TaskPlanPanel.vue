<script setup lang="ts">
import { BookOpen, CheckCircle2, Clock3, Globe2, History, ListChecks, PencilLine, Sparkles } from '@lucide/vue'
import type { PlanRevision, TaskStatus } from '@/types'
import { formatDate, planStatusMeta, researchTaskTypeLabel } from '@/utils/display'

const props = defineProps<{
  plan: PlanRevision | null
  taskStatus: TaskStatus
  loading: boolean
  isCreator: boolean
  actionLoading: '' | 'approve' | 'revise'
  historyCount: number
  taskStates?: Record<string, string>
}>()

defineEmits<{
  approve: []
  revise: []
  showHistory: []
}>()

const isWaiting = () => props.taskStatus === 'WAITING_APPROVAL'
</script>

<template>
  <section class="plan-panel soft-panel" :class="{ 'plan-panel-waiting': isWaiting() }">
    <div class="plan-panel-heading">
      <div class="plan-heading-copy">
        <span class="plan-heading-icon"><ListChecks :size="18" /></span>
        <div>
          <div class="plan-heading-title">
            <h2>研究计划</h2>
            <a-tag v-if="plan" :color="planStatusMeta[plan.status]?.color || 'default'">
              {{ planStatusMeta[plan.status]?.label || plan.status }}
            </a-tag>
          </div>
          <p v-if="plan">{{ plan.revisionNo === 1 ? '版本 1' : `修订版 ${plan.revisionNo}` }} · {{ formatDate(plan.createdAt) }}</p>
          <p v-else-if="loading">Planner 正在整理研究路径。</p>
          <p v-else>查看 Agent 将如何完成这项研究。</p>
        </div>
      </div>
      <a-button v-if="historyCount" type="text" class="plan-history-button" @click="$emit('showHistory')">
        <History :size="15" /> {{ historyCount }} 个版本
      </a-button>
    </div>

    <div v-if="loading" class="plan-skeleton" aria-label="计划生成中">
      <div class="skeleton-line skeleton-title" />
      <div class="skeleton-block" />
      <div v-for="index in 3" :key="index" class="skeleton-task"><span /><div /></div>
    </div>

    <template v-else-if="plan">
      <div class="plan-body">
        <div class="plan-name-row">
          <Sparkles :size="17" />
          <h3>{{ plan.plan.title }}</h3>
        </div>
        <div class="plan-objective">
          <span>研究目标</span>
          <p>{{ plan.plan.objective }}</p>
        </div>
        <div v-if="plan.plan.researchDimensions?.length" class="plan-requirements"><strong>研究维度</strong><span v-for="dimension in plan.plan.researchDimensions" :key="dimension">{{ dimension }}</span></div>
        <div v-if="plan.plan.sourceRequirements" class="plan-source-rule">来源要求：至少 {{ plan.plan.sourceRequirements.minVerifiedSources }} 个已核验来源<span v-if="plan.plan.sourceRequirements.requireOfficialSources">，且包含官方来源</span></div>
        <div class="plan-steps" aria-label="计划任务">
          <article v-for="(item, index) in plan.plan.tasks" :key="item.id" class="plan-step">
            <div class="plan-step-marker">{{ index + 1 }}</div>
            <div class="plan-step-card">
              <div class="plan-step-type">
                <BookOpen v-if="item.type === 'knowledge_research'" :size="14" />
                <Globe2 v-else :size="14" />
                <span>{{ researchTaskTypeLabel(item.type) }}</span>
                <a-tag v-if="taskStates?.[item.id]" size="small">{{ taskStates[item.id] }}</a-tag>
              </div>
              <p>{{ item.description }}</p>
              <small v-if="item.dependsOn?.length">依赖：{{ item.dependsOn.join('、') }}</small>
            </div>
          </article>
        </div>
      </div>

      <div v-if="isWaiting()" class="plan-decision-bar">
        <div class="plan-decision-note">
          <Clock3 :size="16" />
          <span v-if="isCreator">确认后，Agent 将严格按照这个版本继续执行。</span>
          <span v-else>等待任务创建人确认计划，你可以先查看计划内容。</span>
        </div>
        <div v-if="isCreator" class="plan-actions">
          <a-button :loading="actionLoading === 'revise'" :disabled="Boolean(actionLoading)" @click="$emit('revise')">
            <PencilLine :size="15" /> 提出修订
          </a-button>
          <a-button type="primary" :loading="actionLoading === 'approve'" :disabled="Boolean(actionLoading)" @click="$emit('approve')">
            <CheckCircle2 :size="15" /> 确认并开始执行
          </a-button>
        </div>
      </div>
    </template>

    <div v-else class="plan-empty">
      <ListChecks :size="28" />
      <h3>{{ taskStatus === 'PLANNING' ? '计划正在生成' : '暂无结构化计划' }}</h3>
      <p>{{ taskStatus === 'PLANNING' ? 'Planner 完成后会自动展示研究目标与步骤。' : '该任务可能创建于计划审批功能启用之前。' }}</p>
      <a-button v-if="historyCount" type="link" @click="$emit('showHistory')">查看历史版本</a-button>
    </div>
  </section>
</template>

<style scoped>
.plan-panel { overflow: hidden; margin-bottom: 22px; box-shadow: 0 10px 28px rgba(47, 87, 67, .045); }
.plan-panel-waiting { border-left: 3px solid #d4a646; }
.plan-panel-heading { min-height: 72px; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 16px 18px; border-bottom: 1px solid #edf2ee; }
.plan-heading-copy { min-width: 0; display: flex; align-items: center; gap: 11px; }
.plan-heading-icon { width: 34px; height: 34px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 9px; color: #37745f; background: #e9f4ee; }
.plan-heading-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.plan-heading-title h2 { margin: 0; color: #2e4d3d; font-size: 15px; }
.plan-heading-title :deep(.ant-tag) { margin: 0; }
.plan-heading-copy p { margin: 4px 0 0; color: #91a097; font-size: 11px; }
.plan-history-button { color: #668075; display: inline-flex; align-items: center; gap: 5px; font-size: 11px; }
.plan-body { padding: 18px; }
.plan-name-row { display: flex; align-items: flex-start; gap: 8px; color: #4c8b73; }
.plan-name-row h3 { margin: 0; color: #355445; font-size: 15px; line-height: 1.5; }
.plan-objective { margin: 15px 0 18px; padding: 13px 14px; border: 1px solid #dcebe2; border-radius: 7px; background: #f4f9f6; }
.plan-objective span { color: #4b7e68; font-size: 10px; font-weight: 700; letter-spacing: .06em; }
.plan-objective p { margin: 6px 0 0; color: #60776b; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.plan-requirements { display: flex; flex-wrap: wrap; gap: 6px; margin: -7px 0 10px; align-items: center; font-size: 10px; color: #637a6d; }.plan-requirements strong { margin-right: 2px; }.plan-requirements span { padding: 3px 7px; border-radius: 10px; background: #edf5f0; }.plan-source-rule { margin-bottom: 14px; color: #778b80; font-size: 10px; }
.plan-steps { display: grid; }
.plan-step { position: relative; display: grid; grid-template-columns: 26px minmax(0, 1fr); gap: 10px; padding-bottom: 13px; }
.plan-step:not(:last-child)::before { content: ''; position: absolute; left: 12px; top: 26px; bottom: 0; border-left: 1px solid #dce9e1; }
.plan-step-marker { z-index: 1; width: 25px; height: 25px; display: grid; place-items: center; border-radius: 50%; color: #37745f; background: #e8f3ed; border: 1px solid #d5e7dd; font-size: 10px; font-weight: 700; }
.plan-step-card { min-width: 0; padding: 11px 12px; border: 1px solid #e3ece6; border-radius: 7px; background: #fbfcfb; }
.plan-step-type { display: flex; align-items: center; gap: 5px; color: #53836e; font-size: 10px; font-weight: 700; }
.plan-step-card p { margin: 7px 0 0; color: #536d60; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.plan-step-card small { display: block; margin-top: 7px; color: #98a69f; font-size: 10px; }
.plan-decision-bar { padding: 15px 18px 17px; border-top: 1px solid #edf2ee; background: #fffdf8; }
.plan-decision-note { display: flex; align-items: flex-start; gap: 7px; color: #8b7651; font-size: 11px; line-height: 1.55; }
.plan-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 13px; }
.plan-actions :deep(.ant-btn) { display: inline-flex; align-items: center; gap: 6px; }
.plan-empty { padding: 36px 20px; color: #9aaba2; text-align: center; }
.plan-empty h3 { margin: 12px 0 6px; color: #536e60; font-size: 14px; }
.plan-empty p { margin: 0 auto; max-width: 320px; font-size: 11px; line-height: 1.6; }
.plan-skeleton { padding: 20px 18px 24px; }
.skeleton-line, .skeleton-block, .skeleton-task span, .skeleton-task div { background: linear-gradient(90deg, #edf3ef 25%, #f7faf8 50%, #edf3ef 75%); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
.skeleton-title { width: 42%; height: 15px; border-radius: 4px; }
.skeleton-block { height: 65px; border-radius: 7px; margin: 16px 0; }
.skeleton-task { display: grid; grid-template-columns: 25px 1fr; gap: 10px; margin-top: 11px; }
.skeleton-task span { width: 25px; height: 25px; border-radius: 50%; }
.skeleton-task div { height: 54px; border-radius: 7px; }
@keyframes shimmer { to { background-position: -200% 0; } }
@media (max-width: 700px) { .plan-panel-heading { align-items: flex-start; }.plan-history-button { padding-right: 0; }.plan-actions { display: grid; grid-template-columns: 1fr; }.plan-actions :deep(.ant-btn) { justify-content: center; }.plan-actions :deep(.ant-btn-primary) { order: -1; } }
</style>

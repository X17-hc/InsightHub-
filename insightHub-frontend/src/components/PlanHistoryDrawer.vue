<script setup lang="ts">
import { computed } from 'vue'
import Drawer from 'ant-design-vue/es/drawer'
import { BookOpen, CalendarDays, CheckCircle2, Clock3, GitCommitHorizontal } from '@lucide/vue'
import type { PlanRevision } from '@/types'
import { formatDate, planStatusMeta } from '@/utils/display'

const props = defineProps<{ open: boolean; plans: PlanRevision[]; currentId?: string }>()
defineEmits<{ 'update:open': [value: boolean] }>()
const orderedPlans = computed(() => [...props.plans].sort((left, right) => right.revisionNo - left.revisionNo))
</script>

<template>
  <Drawer :open="open" title="计划历史" width="min(540px, 100vw)" @close="$emit('update:open', false)">
    <div class="history-intro"><GitCommitHorizontal :size="17" /><span>每次修订都会生成不可变版本，旧计划不会被覆盖。</span></div>
    <div v-if="orderedPlans.length" class="plan-history-list">
      <details v-for="item in orderedPlans" :key="item.id" class="history-item" :open="item.id === currentId">
        <summary>
          <div>
            <strong>{{ item.revisionNo === 1 ? '版本 1' : `修订版 ${item.revisionNo}` }}</strong>
            <span><CalendarDays :size="12" /> {{ formatDate(item.createdAt) }}</span>
          </div>
          <a-tag :color="planStatusMeta[item.status]?.color || 'default'">{{ planStatusMeta[item.status]?.label || item.status }}</a-tag>
        </summary>
        <div class="history-content">
          <h3>{{ item.plan.title }}</h3>
          <p class="history-objective">{{ item.plan.objective }}</p>
          <div v-if="item.revisionInstruction" class="history-instruction"><strong>修订意见</strong><p>{{ item.revisionInstruction }}</p></div>
          <div v-if="item.approvalRemark" class="history-instruction"><strong>审批备注</strong><p>{{ item.approvalRemark }}</p></div>
          <div class="history-meta">
            <span><BookOpen :size="13" /> {{ item.plan.tasks.length }} 个研究任务</span>
            <span v-if="item.approvedAt"><CheckCircle2 :size="13" /> {{ formatDate(item.approvedAt) }} 批准</span>
            <span v-if="item.approvedBy">批准人：{{ item.approvedBy }}</span>
            <span v-else><Clock3 :size="13" /> 尚未批准</span>
          </div>
        </div>
      </details>
    </div>
    <div v-else class="history-empty"><GitCommitHorizontal :size="30" /><h3>暂无历史版本</h3><p>第一个研究计划生成后会显示在这里。</p></div>
  </Drawer>
</template>

<style scoped>
.history-intro { display: flex; gap: 8px; align-items: flex-start; padding: 12px 13px; margin-bottom: 16px; border-radius: 7px; color: #6e8377; background: #f4f8f5; font-size: 11px; line-height: 1.55; }
.plan-history-list { display: grid; gap: 11px; }.history-item { border: 1px solid #e1ebe4; border-radius: 8px; overflow: hidden; background: #fff; }
.history-item summary { list-style: none; cursor: pointer; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px; }
.history-item summary::-webkit-details-marker { display: none; }.history-item summary strong { color: #365544; font-size: 13px; }.history-item summary span { display: flex; gap: 5px; align-items: center; color: #91a098; font-size: 10px; margin-top: 4px; }
.history-item summary :deep(.ant-tag) { margin: 0; }.history-content { padding: 0 14px 15px; border-top: 1px solid #edf2ef; }.history-content h3 { color: #456252; font-size: 13px; margin: 14px 0 7px; }.history-objective { color: #71847a; font-size: 11px; line-height: 1.65; margin: 0; }
.history-instruction { margin-top: 12px; padding: 10px 11px; border-radius: 6px; background: #fbf8f0; }.history-instruction strong { color: #96783f; font-size: 10px; }.history-instruction p { margin: 5px 0 0; color: #7f745e; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.history-meta { display: flex; align-items: center; gap: 13px; flex-wrap: wrap; margin-top: 13px; color: #87978f; font-size: 10px; }.history-meta span { display: inline-flex; align-items: center; gap: 4px; }
.history-empty { text-align: center; padding: 70px 18px; color: #9caaa3; }.history-empty h3 { margin: 12px 0 6px; color: #587064; font-size: 14px; }.history-empty p { margin: 0; font-size: 11px; }
</style>

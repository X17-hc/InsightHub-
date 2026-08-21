<script setup lang="ts">
import { ref, watch } from 'vue'
import Modal from 'ant-design-vue/es/modal'
import { CheckCircle2, ShieldCheck } from '@lucide/vue'
import type { PlanRevision } from '@/types'

const props = defineProps<{ open: boolean; plan: PlanRevision | null; loading: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; submit: [remark: string] }>()
const remark = ref('')

watch(() => props.open, (open) => { if (open) remark.value = '' })
</script>

<template>
  <Modal :open="open" title="确认研究计划" :confirm-loading="loading" :closable="!loading" :mask-closable="!loading" ok-text="确认并开始执行" cancel-text="暂不确认" @cancel="emit('update:open', false)" @ok="emit('submit', remark.trim())">
    <div class="approval-summary">
      <span class="approval-icon"><ShieldCheck :size="21" /></span>
      <div>
        <strong>{{ plan?.plan.title || '当前研究计划' }}</strong>
        <p>修订版 {{ plan?.revisionNo || '—' }} · 批准后 Agent 将基于该不可变版本继续执行。</p>
      </div>
    </div>
    <a-form layout="vertical" class="modal-form">
      <a-form-item label="审批备注（可选）">
        <a-textarea v-model:value="remark" :rows="3" :maxlength="500" show-count placeholder="例如：计划范围清晰，可以开始执行。" />
      </a-form-item>
    </a-form>
    <div class="approval-note"><CheckCircle2 :size="15" /> 执行开始后仍可在计划历史中查看这个版本。</div>
  </Modal>
</template>

<style scoped>
.approval-summary { display: flex; gap: 12px; align-items: flex-start; padding: 14px; margin: 8px 0 18px; border: 1px solid #dcebe2; border-radius: 8px; background: #f4f9f6; }
.approval-icon { width: 35px; height: 35px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 9px; background: #dff0e7; color: #34745d; }
.approval-summary strong { color: #345443; font-size: 14px; }.approval-summary p { color: #71877b; font-size: 11px; line-height: 1.6; margin: 5px 0 0; }
.approval-note { display: flex; align-items: center; gap: 6px; color: #7e9187; font-size: 11px; }
</style>

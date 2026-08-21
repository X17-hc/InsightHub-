<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Modal from 'ant-design-vue/es/modal'
import { Lightbulb, PencilLine } from '@lucide/vue'
import type { PlanRevision } from '@/types'

const props = defineProps<{ open: boolean; plan: PlanRevision | null; loading: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; submit: [revision: string] }>()
const revision = ref('')
const valid = computed(() => Boolean(revision.value.trim()))

watch(() => props.open, (open) => { if (open) revision.value = '' })
</script>

<template>
  <Modal :open="open" title="提出计划修订" :confirm-loading="loading" :ok-button-props="{ disabled: !valid }" :closable="!loading" :mask-closable="!loading" ok-text="提交并重新规划" cancel-text="取消" @cancel="emit('update:open', false)" @ok="valid && emit('submit', revision.trim())">
    <div class="revision-intro">
      <span><PencilLine :size="18" /></span>
      <p>请用文字说明希望调整的方向。系统会保留修订版 {{ plan?.revisionNo || '—' }}，并由 Planner 创建新版本。</p>
    </div>
    <a-form layout="vertical" class="modal-form">
      <a-form-item label="修订意见" required>
        <a-textarea v-model:value="revision" :rows="7" :maxlength="2000" show-count autofocus placeholder="例如：增加竞品定价比较，并优先引用所选知识库中的内部材料。" />
      </a-form-item>
    </a-form>
    <div class="revision-examples">
      <Lightbulb :size="15" />
      <span>可以增加研究方向、删除无关任务、调整来源优先级或明确结论关注点。</span>
    </div>
  </Modal>
</template>

<style scoped>
.revision-intro { display: flex; gap: 10px; align-items: flex-start; margin: 8px 0 17px; padding: 12px 13px; border-radius: 7px; color: #6f8378; background: #f6f9f7; }
.revision-intro > span { color: #4d8a72; }.revision-intro p { margin: 0; font-size: 11px; line-height: 1.65; }
.revision-examples { display: flex; gap: 7px; align-items: flex-start; color: #8a9a92; font-size: 10px; line-height: 1.55; }
</style>

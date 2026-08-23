<script setup lang="ts">
import { computed } from 'vue'
import { FileText } from '@lucide/vue'
import type { Citation } from '@/types'

const props = defineProps<{ citations: Citation[] }>()
const verified = computed(() => props.citations.filter((item) => item.verificationStatus === 'VERIFIED'))
const candidates = computed(() => props.citations.filter((item) => item.verificationStatus === 'CANDIDATE'))
const synthetic = computed(() => props.citations.filter((item) => item.verificationStatus === 'SYNTHETIC'))
</script>
<template>
  <div v-if="citations.length" class="citation-groups">
    <section v-if="verified.length"><h3>已核验来源 <span>{{ verified.length }}</span></h3><div class="citation-list"><CitationItem v-for="citation in verified" :key="citation.id" :citation="citation" /></div></section>
    <section v-if="candidates.length"><h3>候选来源 <span>{{ candidates.length }}</span></h3><p class="group-warning">未通过内容核验，不作为报告结论依据。</p><div class="citation-list"><CitationItem v-for="citation in candidates" :key="citation.id" :citation="citation" /></div></section>
    <details v-if="synthetic.length" class="synthetic-section"><summary>历史演示数据（{{ synthetic.length }}）</summary><p class="group-warning">仅用于历史审计，不计入真实来源数，也不能支撑结论。</p><div class="citation-list"><CitationItem v-for="citation in synthetic" :key="citation.id" :citation="citation" /></div></details>
  </div>
  <div v-else class="empty-state report-empty"><FileText :size="28"/><h3>暂无引用</h3><p>报告生成后，引用来源会按版本列出。</p></div>
</template>

<script lang="ts">
import { defineComponent, h, type PropType } from 'vue'
import type { Citation as CitationModel } from '@/types'

export const CitationItem = defineComponent({
  props: { citation: { type: Object as PropType<CitationModel>, required: true } },
  setup(props) {
    return () => {
      const item = props.citation
      const uri = item.finalUri || item.canonicalUri || item.sourceUri
      const link = uri && /^https?:\/\//i.test(uri)
        ? h('a', { href: uri, target: '_blank', rel: 'noopener noreferrer' }, uri)
        : uri ? h('span', { class: 'citation-uri' }, uri) : null
      return h('article', { class: 'citation-item' }, [h('div', { class: 'citation-number' }, `[${item.citationNo}]`), h('div', { class: 'citation-copy' }, [
        h('strong', item.sourceTitle || '未命名来源'), link, item.quotedText ? h('p', `“${item.quotedText}”`) : null,
        h('div', { class: 'citation-badges' }, [h('span', { class: 'citation-type' }, item.sourceType || '资料'), h('span', { class: ['verification-badge', { verified: item.verificationStatus === 'VERIFIED' }] }, item.verificationStatus)]),
        item.verificationReason ? h('small', { class: 'verification-detail' }, item.verificationReason) : null,
        item.retrievedAt || item.httpStatus ? h('small', { class: 'verification-detail' }, `${item.retrievedAt ? `抓取于 ${new Date(item.retrievedAt).toLocaleString('zh-CN')}` : ''}${item.httpStatus ? ` · HTTP ${item.httpStatus}` : ''}`) : null,
      ])])
    }
  },
})
</script>

<style scoped>
.citation-groups { display: grid; gap: 18px; }.citation-groups h3 { margin: 0 0 8px; color: #355445; font-size: 13px; }.citation-groups h3 span { color: #87998f; font-weight: 400; }.group-warning { margin: 0 0 8px; color: #9a6e3e; font-size: 11px; line-height: 1.5; }.synthetic-section { padding: 10px; border: 1px solid #eadfc8; border-radius: 7px; background: #fffdf8; }.synthetic-section summary { cursor: pointer; color: #8a6c3d; font-weight: 600; font-size: 12px; }:deep(.verification-detail) { display: block; margin-top: 5px; color: #8b9991; font-size: 10px; line-height: 1.45; }
</style>

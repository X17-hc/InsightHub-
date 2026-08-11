<script setup lang="ts">
import { FileText } from '@lucide/vue'
import type { Citation } from '@/types'

defineProps<{ citations: Citation[] }>()
</script>
<template>
  <div v-if="citations.length" class="citation-list"><article v-for="citation in citations" :key="citation.id" class="citation-item"><div class="citation-number">[{{ citation.citationNo }}]</div><div class="citation-copy"><strong>{{ citation.sourceTitle || '未命名来源' }}</strong><a v-if="citation.sourceUri" :href="citation.sourceUri" target="_blank" rel="noreferrer">{{ citation.sourceUri }}</a><p v-if="citation.quotedText">“{{ citation.quotedText }}”</p><span class="citation-type">{{ citation.sourceType || '资料' }} · {{ citation.verified ? '已核验' : '待核验' }}</span></div></article></div>
  <div v-else class="empty-state report-empty"><FileText :size="28"/><h3>暂无引用</h3><p>报告生成后，引用来源会按编号列出。</p></div>
</template>

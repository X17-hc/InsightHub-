<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronUp, SearchCheck, ShieldAlert } from '@lucide/vue'
import type { CritiqueResult } from '@/types'
import { criticVerdictMeta } from '@/utils/display'
import { PerformativeIsland, WibblingSpinner } from '@/integrations/performative'

const props = defineProps<{ critique: CritiqueResult | null; reviewing: boolean; supplementing: boolean; terminal: boolean }>()
const expanded = ref(false)
const meta = computed(() => props.critique ? criticVerdictMeta[props.critique.verdict] : null)
const icon = computed(() => props.critique?.verdict === 'PASS' ? CheckCircle2 : props.critique?.verdict === 'FAIL' ? ShieldAlert : AlertTriangle)
const visibleGaps = computed(() => expanded.value ? props.critique?.gaps || [] : (props.critique?.gaps || []).slice(0, 3))
const visibleLimitations = computed(() => expanded.value ? props.critique?.limitations || [] : (props.critique?.limitations || []).slice(0, 3))
const hasMore = computed(() => ((props.critique?.gaps.length || 0) + (props.critique?.limitations.length || 0)) > 6)
</script>

<template>
  <section class="critic-card soft-panel" :class="meta?.tone">
    <div class="critic-heading">
      <span class="critic-icon"><component :is="critique ? icon : SearchCheck" :size="18" /></span>
      <div><h2>质量评审</h2><p>Critic 检查证据覆盖、冲突与结论可信度。</p></div>
    </div>
    <div v-if="reviewing" class="critic-pending"><PerformativeIsland :component="WibblingSpinner" :component-props="{ verbs: ['评审中'], glyphColor: 'var(--pui-success)' }" /><div><strong>Critic 正在评审</strong><p>正在核对计划覆盖和已验证证据。</p></div></div>
    <div v-else-if="supplementing" class="critic-pending warning"><PerformativeIsland :component="WibblingSpinner" :component-props="{ verbs: ['补充研究'], glyphColor: 'var(--pui-warning)' }" /><div><strong>补充研究进行中</strong><p>评审发现证据缺口，Agent 正在补充检索。</p></div></div>
    <div v-else-if="critique" class="critic-result">
      <div class="critic-verdict"><component :is="icon" :size="18" /><strong>{{ meta?.label }}</strong></div>
      <div v-if="critique.criticRound" class="critic-round">第 {{ critique.criticRound }} / {{ critique.maxCriticRounds || 2 }} 轮</div>
      <p v-if="critique.summary" class="critic-summary">{{ critique.summary }}</p>
      <div v-if="critique.supplementTaskCount" class="supplement-count">已请求 {{ critique.supplementTaskCount }} 个补充任务</div>
      <div v-if="visibleGaps.length" class="critic-list"><strong>发现的缺口</strong><ul><li v-for="item in visibleGaps" :key="item">{{ item }}</li></ul></div>
      <div v-if="visibleLimitations.length" class="critic-list limitations"><strong>限制说明</strong><ul><li v-for="item in visibleLimitations" :key="item">{{ item }}</li></ul></div>
      <button v-if="hasMore" class="critic-expand" @click="expanded = !expanded"><component :is="expanded ? ChevronUp : ChevronDown" :size="14" />{{ expanded ? '收起' : '查看全部' }}</button>
    </div>
    <div v-else class="critic-empty"><SearchCheck :size="25" /><strong>{{ terminal ? '没有结构化评审记录' : '等待质量评审' }}</strong><p>{{ terminal ? '这项任务可能完成于 Critic 功能启用之前。' : '研究证据合并后，评审结果会显示在这里。' }}</p></div>
  </section>
</template>

<style scoped>
.critic-card { overflow: hidden; margin-bottom: 18px; }.critic-heading { display: flex; gap: 10px; align-items: center; padding: 15px 16px; border-bottom: 1px solid #edf2ef; }.critic-icon { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 8px; color: #39775f; background: #e9f4ee; }.critic-heading h2 { margin: 0; color: #345143; font-size: 14px; }.critic-heading p { margin: 4px 0 0; color: #91a097; font-size: 10px; line-height: 1.45; }
.critic-pending { display: flex; gap: 10px; align-items: flex-start; padding: 17px 16px; }.critic-pulse { width: 9px; height: 9px; margin-top: 4px; border-radius: 50%; background: #57987c; box-shadow: 0 0 0 0 rgba(87,152,124,.35); animation: pulse 1.5s infinite; }.critic-pending.warning .critic-pulse { background: #c59a43; }.critic-pending strong { color: #4b6758; font-size: 12px; }.critic-pending p { margin: 4px 0 0; color: #899990; font-size: 10px; line-height: 1.5; }
.critic-result { position: relative; padding: 16px; }.critic-verdict { display: flex; align-items: center; gap: 7px; color: #3d8067; }.critic-card.warning .critic-verdict { color: #a77d2f; }.critic-card.danger .critic-verdict { color: #b65b4c; }.critic-verdict strong { font-size: 12px; }.critic-round { position: absolute; right: 16px; top: 17px; color: #9aa79f; font-size: 10px; }.critic-summary { margin: 11px 0 0; color: #657a6f; font-size: 11px; line-height: 1.6; }.supplement-count { display: inline-block; margin-top: 10px; padding: 4px 7px; border-radius: 4px; color: #98722e; background: #fbf3df; font-size: 10px; }.critic-list { margin-top: 13px; }.critic-list strong { color: #60766a; font-size: 10px; }.critic-list ul { margin: 7px 0 0; padding-left: 17px; }.critic-list li { color: #788b81; font-size: 10px; line-height: 1.55; margin-top: 4px; overflow-wrap: anywhere; }.critic-list.limitations { padding: 9px 10px; border-radius: 6px; background: #fbf6f4; }.critic-expand { border: 0; background: transparent; color: #4f816d; padding: 8px 0 0; display: inline-flex; align-items: center; gap: 4px; font-size: 10px; }
.critic-empty { text-align: center; padding: 25px 16px; color: #9ba9a2; }.critic-empty strong { display: block; margin-top: 9px; color: #657a6e; font-size: 12px; }.critic-empty p { margin: 5px 0 0; font-size: 10px; line-height: 1.5; }
@keyframes pulse { 70% { box-shadow: 0 0 0 7px rgba(87,152,124,0); } 100% { box-shadow: 0 0 0 0 rgba(87,152,124,0); } }
</style>

<script setup lang="ts">
import { PerformativeIsland, GlassCard } from '@/integrations/performative'
defineProps<{ title: string; description: string; features: string[]; status?: string }>()
</script>

<template>
  <section class="unavailable-card">
    <PerformativeIsland class="unavailable-glass-accent" :component="GlassCard" :component-props="{ glowOnHover: true, 'aria-hidden': true }" />
    <div class="unavailable-icon"><slot name="icon" /></div>
    <div class="unavailable-copy"><div class="unavailable-title"><strong>{{ title }}</strong><span>{{ status || '服务端能力待接入' }}</span></div><p>{{ description }}</p><div class="unavailable-tags"><span v-for="item in features" :key="item">{{ item }}</span></div></div>
  </section>
</template>

<style scoped>
.unavailable-card { display: flex; gap: 11px; padding: 14px; border: 1px dashed #d5e2da; border-radius: 8px; background: #fafcfb; }.unavailable-icon { width: 34px; height: 34px; flex: 0 0 auto; display: grid; place-items: center; border-radius: 8px; color: #7a9587; background: #edf4ef; }.unavailable-copy { min-width: 0; flex: 1; }.unavailable-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; }.unavailable-title strong { color: #536d60; font-size: 12px; }.unavailable-title > span { color: #9ba8a1; font-size: 9px; white-space: nowrap; }.unavailable-copy p { margin: 5px 0 9px; color: #889890; font-size: 10px; line-height: 1.5; }.unavailable-tags { display: flex; flex-wrap: wrap; gap: 5px; }.unavailable-tags span { padding: 3px 6px; border-radius: 4px; color: #7a8e83; background: #edf3ef; font-size: 9px; }
@media (max-width: 420px) { .unavailable-title { display: block; }.unavailable-title > span { display: block; margin-top: 3px; } }
</style>

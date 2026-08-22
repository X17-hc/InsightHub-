<script setup lang="ts">
import { PerformativeIsland, Sparkle } from '@/integrations/performative'
defineProps<{ eyebrow?: string; title: string; description?: string }>()
</script>

<template>
  <header class="page-header">
    <div>
      <div v-if="eyebrow" class="eyebrow">{{ eyebrow }}</div>
      <h1><PerformativeIsland :component="Sparkle" :component-props="{ static: true }" class="header-sparkle" />{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="page-header-actions"><slot /></div>
  </header>
</template>

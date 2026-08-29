<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { mountPerformative } from './mountPerformative'
import type { PerformativeComponent } from './performativeTypes'

const props = withDefaults(defineProps<{
  component: PerformativeComponent
  componentProps?: Record<string, unknown>
  fallback?: 'static' | 'hide' | 'error'
  class?: string
}>(), { fallback: 'static' })

const host = ref<HTMLElement | null>(null)
let dispose: (() => void) | undefined
const failed = ref(false)

function renderIsland() {
  if (!host.value) return
  try {
    dispose?.()
    failed.value = false
    dispose = mountPerformative(host.value, props.component, props.componentProps || {})
  } catch (error) {
    failed.value = true
    if (import.meta.env.DEV) console.warn('[performative-ui] component fallback', error)
  }
}

onMounted(renderIsland)
watch(() => [props.component, props.componentProps], async () => {
  await nextTick()
  renderIsland()
}, { deep: true })
onBeforeUnmount(() => dispose?.())
</script>

<template>
  <span ref="host" :class="props.class" :data-performative-fallback="failed || undefined">
    <span v-if="failed && props.fallback === 'error'" class="performative-fallback-error">组件暂时不可用</span>
    <slot v-if="failed && props.fallback === 'static'" name="fallback" />
  </span>
</template>

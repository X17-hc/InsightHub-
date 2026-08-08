<script setup lang="ts">
import { computed } from 'vue'
import Tag from 'ant-design-vue/es/tag'
import { documentStatusMeta, taskStatusMeta } from '@/utils/display'
import type { DocumentStatus, TaskStatus } from '@/types'

const props = defineProps<{ status: TaskStatus | DocumentStatus | string }>()
const meta = computed(() => taskStatusMeta[props.status as TaskStatus] || documentStatusMeta[props.status as DocumentStatus] || { label: props.status, color: 'default' })
</script>

<template>
  <Tag :color="meta.color" class="status-tag">{{ meta.label }}</Tag>
</template>

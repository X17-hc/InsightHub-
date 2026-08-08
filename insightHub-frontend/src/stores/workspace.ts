import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { workspaceApi } from '@/api/workspace'
import type { Workspace } from '@/types'

const WORKSPACE_KEY = 'insighthub.workspace'
export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([])
  const currentId = ref(localStorage.getItem(WORKSPACE_KEY) || '')
  const loading = ref(false)
  const current = computed(() => workspaces.value.find((item) => item.id === currentId.value) || null)
  function select(id: string) { currentId.value = id; id ? localStorage.setItem(WORKSPACE_KEY, id) : localStorage.removeItem(WORKSPACE_KEY) }
  async function load() {
    loading.value = true
    try { workspaces.value = await workspaceApi.list(); if (!workspaces.value.some((item) => item.id === currentId.value)) select(workspaces.value[0]?.id || ''); return workspaces.value }
    finally { loading.value = false }
  }
  async function create(payload: { name: string; description?: string }) { const workspace = await workspaceApi.create(payload); workspaces.value.unshift(workspace); select(workspace.id); return workspace }
  return { workspaces, currentId, current, loading, select, load, create }
})

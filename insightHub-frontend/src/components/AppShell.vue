<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import Modal from 'ant-design-vue/es/modal'
import message from 'ant-design-vue/es/message'
import { BookOpen, FileSearch, LogOut, Menu, Plus, Settings, Users, X } from '@lucide/vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { PerformativeIsland, Sparkle, StatusDot } from '@/integrations/performative'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const workspace = useWorkspaceStore()
const mobileOpen = ref(false)
const creating = ref(false)
const createLoading = ref(false)
const workspaceForm = ref({ name: '', description: '' })
const workspaceId = computed(() => String(route.params.workspaceId || workspace.currentId || ''))
const displayName = computed(() => auth.profile?.displayName || auth.profile?.username || auth.session?.username || '研究员')
const initials = computed(() => displayName.value.slice(0, 1).toUpperCase())
const navItems = computed(() => [
  { label: '研究任务', to: `/workspaces/${workspaceId.value}/tasks`, icon: FileSearch },
  { label: '知识库', to: `/workspaces/${workspaceId.value}/knowledge`, icon: BookOpen },
  { label: '成员与 Agent', to: `/workspaces/${workspaceId.value}/settings`, icon: Users },
])

onMounted(async () => {
  if (!auth.profile && auth.authenticated) {
    try { await auth.loadProfile() } catch { /* the request layer handles expired sessions */ }
  }
  if (!workspace.workspaces.length) {
    try {
      await workspace.load()
      const routeWorkspaceId = String(route.params.workspaceId || '')
      if (workspace.workspaces.some((item) => item.id === routeWorkspaceId)) workspace.select(routeWorkspaceId)
    } catch { /* empty state is rendered by the page */ }
  }
})

function switchWorkspace(id: string) {
  workspace.select(id)
  const name = route.name === 'knowledge' ? 'knowledge' : route.name === 'settings' ? 'settings' : 'tasks'
  router.push({ name, params: { workspaceId: id } })
  mobileOpen.value = false
}

async function createWorkspace() {
  if (!workspaceForm.value.name.trim()) return
  createLoading.value = true
  try {
    const created = await workspace.create({ ...workspaceForm.value, name: workspaceForm.value.name.trim() })
    creating.value = false; workspaceForm.value = { name: '', description: '' }
    switchWorkspace(created.id)
    message.success('工作空间已创建')
  } catch { message.error('工作空间创建失败') }
  finally { createLoading.value = false }
}

function logout() { auth.logout(); router.replace({ name: 'login' }) }
</script>

<template>
  <div class="app-frame">
    <div v-if="mobileOpen" class="mobile-scrim" @click="mobileOpen = false" />
    <aside class="side-nav" :class="{ 'side-nav-open': mobileOpen }">
      <div class="brand-lockup">
        <div class="brand-mark">I</div><PerformativeIsland :component="Sparkle" class="brand-sparkle" :component-props="{ size: 12 }" />
        <div><strong>InsightHub</strong><span>研究工作台</span></div>
        <button class="icon-button nav-close" aria-label="关闭导航" @click="mobileOpen = false"><X :size="18" /></button>
      </div>
      <div class="workspace-switcher">
        <span class="switcher-label">当前工作空间</span>
        <select :value="workspaceId" aria-label="选择工作空间" @change="switchWorkspace(($event.target as HTMLSelectElement).value)">
          <option v-for="item in workspace.workspaces" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <button class="new-workspace" @click="creating = true"><Plus :size="14" /> 新建工作空间</button>
      </div>
      <nav class="main-nav" aria-label="主导航">
        <RouterLink v-for="item in navItems" :key="item.label" :to="item.to" class="nav-item" :class="{ active: route.path === item.to }" @click="mobileOpen = false">
          <component :is="item.icon" :size="18" stroke-width="1.8" /><span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="side-nav-footer">
        <div class="trust-note"><PerformativeIsland :component="StatusDot" :component-props="{ color: 'var(--pui-success)' }" /> 数据连接正常</div>
        <div class="user-block"><div class="avatar">{{ initials }}</div><div class="user-copy"><strong>{{ displayName }}</strong><span>{{ auth.profile?.email || '工作空间成员' }}</span></div><button class="icon-button" aria-label="退出登录" title="退出登录" @click="logout"><LogOut :size="16" /></button></div>
      </div>
    </aside>

    <main class="main-area">
      <header class="top-bar">
        <button class="icon-button mobile-menu" aria-label="打开导航" @click="mobileOpen = true"><Menu :size="20" /></button>
        <div class="breadcrumbs"><span>InsightHub</span><span class="crumb-separator">/</span><strong>{{ workspace.current?.name || '工作空间' }}</strong></div>
        <div class="top-bar-actions"><span class="connection-label"><PerformativeIsland :component="StatusDot" :component-props="{ color: 'var(--pui-success)' }" /> 实时同步</span><button class="icon-button" aria-label="设置" title="设置" @click="router.push({ name: 'settings', params: { workspaceId } })"><Settings :size="17" /></button><div class="top-avatar">{{ initials }}</div></div>
      </header>
      <div class="content-scroll"><slot /></div>
    </main>

    <Modal v-model:open="creating" title="新建工作空间" :confirm-loading="createLoading" ok-text="创建" cancel-text="取消" @ok="createWorkspace">
      <a-form layout="vertical" class="modal-form" @submit.prevent="createWorkspace"><a-form-item label="名称" required><a-input v-model:value="workspaceForm.name" maxlength="64" placeholder="例如：市场洞察组" /></a-form-item><a-form-item label="描述"><a-textarea v-model:value="workspaceForm.description" :rows="3" maxlength="256" placeholder="可选，描述这个空间的研究范围" /></a-form-item></a-form>
    </Modal>
  </div>
</template>

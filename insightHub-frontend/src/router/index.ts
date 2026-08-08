import { createRouter, createWebHistory } from 'vue-router'
import { readSession } from '@/services/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/pages/LoginPage.vue'), meta: { public: true } },
    { path: '/register', name: 'register', component: () => import('@/pages/RegisterPage.vue'), meta: { public: true } },
    { path: '/', name: 'home', component: () => import('@/pages/HomeRedirect.vue') },
    { path: '/workspaces/:workspaceId/tasks', name: 'tasks', component: () => import('@/pages/TaskListPage.vue') },
    { path: '/workspaces/:workspaceId/tasks/new', name: 'task-new', component: () => import('@/pages/TaskCreatePage.vue') },
    { path: '/workspaces/:workspaceId/tasks/:taskId', name: 'task-detail', component: () => import('@/pages/TaskDetailPage.vue') },
    { path: '/workspaces/:workspaceId/knowledge', name: 'knowledge', component: () => import('@/pages/KnowledgePage.vue') },
    { path: '/workspaces/:workspaceId/settings', name: 'settings', component: () => import('@/pages/SettingsPage.vue') },
  ],
})

router.beforeEach((to) => {
  const authenticated = Boolean(readSession()?.accessToken)
  if (!to.meta.public && !authenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.public && authenticated) return { name: 'home' }
  return true
})

export default router

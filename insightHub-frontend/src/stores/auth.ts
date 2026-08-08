import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { AUTH_CHANGED_EVENT, readSession, saveSession } from '@/services/session'
import type { AuthSession, UserProfile } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(readSession())
  const profile = ref<UserProfile | null>(null)
  const authenticated = computed(() => Boolean(session.value?.accessToken))
  window.addEventListener(AUTH_CHANGED_EVENT, (event) => { session.value = (event as CustomEvent<AuthSession | null>).detail })

  async function login(username: string, password: string) {
    const next = await authApi.login({ username, password })
    saveSession(next); session.value = next; await loadProfile()
  }
  async function register(payload: { username: string; password: string; email?: string; displayName?: string }) {
    const next = await authApi.register(payload)
    saveSession(next); session.value = next; await loadProfile()
  }
  async function loadProfile() { profile.value = await authApi.me() }
  function logout() { saveSession(null); session.value = null; profile.value = null }
  return { session, profile, authenticated, login, register, loadProfile, logout }
})

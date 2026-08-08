import type { AuthSession } from '@/types'

const SESSION_KEY = 'insighthub.auth'
export const AUTH_CHANGED_EVENT = 'insighthub:auth-changed'

export function readSession(): AuthSession | null {
  try {
    const value = localStorage.getItem(SESSION_KEY)
    return value ? (JSON.parse(value) as AuthSession) : null
  } catch {
    return null
  }
}

export function saveSession(session: AuthSession | null): void {
  if (session) localStorage.setItem(SESSION_KEY, JSON.stringify(session))
  else localStorage.removeItem(SESSION_KEY)
  window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT, { detail: session }))
}

import { http } from './http'
import type { AuthSession, UserProfile } from '@/types'

export const authApi = {
  login: (payload: { username: string; password: string }) => http.post<AuthSession>('/v1/auth/login', payload),
  register: (payload: { username: string; password: string; email?: string; displayName?: string }) => http.post<AuthSession>('/v1/auth/register', payload),
  me: () => http.get<UserProfile>('/v1/auth/me'),
}

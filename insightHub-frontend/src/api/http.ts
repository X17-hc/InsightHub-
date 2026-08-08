import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import { readSession, saveSession } from '@/services/session'
import type { ApiEnvelope, AuthSession } from '@/types'

export class ApiError extends Error {
  constructor(message: string, public readonly code: number | string = 'REQUEST_FAILED', public readonly status?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

const transport = axios.create({ baseURL: '/api', timeout: 30_000 })
let refreshPromise: Promise<AuthSession> | null = null

function isEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  return Boolean(value && typeof value === 'object' && 'code' in value)
}

function redirectToLogin(): void {
  saveSession(null)
  if (window.location.pathname !== '/login') {
    const redirect = encodeURIComponent(window.location.pathname + window.location.search)
    window.location.assign(`/login?redirect=${redirect}`)
  }
}

async function refreshSession(): Promise<AuthSession> {
  if (refreshPromise) return refreshPromise
  const session = readSession()
  if (!session?.refreshToken) {
    redirectToLogin()
    throw new ApiError('登录状态已失效，请重新登录', 'UNAUTHORIZED', 401)
  }
  refreshPromise = transport
    .post<ApiEnvelope<AuthSession>>('/v1/auth/refresh', { refreshToken: session.refreshToken })
    .then(({ data }) => {
      if (!isEnvelope(data) || Number(data.code) !== 0 || !data.data) {
        throw new ApiError(data?.message || '刷新登录状态失败', data?.code || 'UNAUTHORIZED', 401)
      }
      const next = { ...session, ...data.data }
      saveSession(next)
      return next
    })
    .catch((error) => {
      redirectToLogin()
      throw error
    })
    .finally(() => { refreshPromise = null })
  return refreshPromise
}

async function execute<T>(config: AxiosRequestConfig, retried = false): Promise<T> {
  const session = readSession()
  const headers: Record<string, string> = { ...(config.headers as Record<string, string> | undefined) }
  if (session?.accessToken) headers.Authorization = `Bearer ${session.accessToken}`
  try {
    const response = await transport.request<ApiEnvelope<T>>({ ...config, headers })
    const body = response.data
    if (!isEnvelope(body)) return body as T
    if (Number(body.code) === 0) return body.data as T
    if (Number(body.code) === 40100 && !retried) {
      await refreshSession()
      return execute<T>(config, true)
    }
    throw new ApiError(body.message || '请求失败', body.code, response.status)
  } catch (error) {
    if (error instanceof ApiError) throw error
    const axiosError = error as AxiosError<{ code?: number | string; message?: string }>
    if (axiosError.response?.status === 401 && !retried) {
      await refreshSession()
      return execute<T>(config, true)
    }
    throw new ApiError(
      axiosError.response?.data?.message || axiosError.message || '网络请求失败',
      axiosError.response?.data?.code || 'HTTP_ERROR',
      axiosError.response?.status,
    )
  }
}

export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig) => execute<T>({ ...config, method: 'GET', url }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => execute<T>({ ...config, method: 'POST', url, data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) => execute<T>({ ...config, method: 'PUT', url, data }),
  delete: <T>(url: string, config?: AxiosRequestConfig) => execute<T>({ ...config, method: 'DELETE', url }),
}

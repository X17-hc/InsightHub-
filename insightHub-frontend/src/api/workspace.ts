import { http } from './http'
import type { Workspace, WorkspaceMember } from '@/types'

export const workspaceApi = {
  list: () => http.get<Workspace[]>('/v1/workspaces'),
  get: (workspaceId: string) => http.get<Workspace>(`/v1/workspaces/${workspaceId}`),
  create: (payload: { name: string; description?: string }) => http.post<Workspace>('/v1/workspaces', payload),
  members: (workspaceId: string) => http.get<WorkspaceMember[]>(`/v1/workspaces/${workspaceId}/members`),
  addMember: (workspaceId: string, payload: { userId: string; role: string }) => http.post<WorkspaceMember>(`/v1/workspaces/${workspaceId}/members`, payload),
  removeMember: (workspaceId: string, userId: string) => http.delete<void>(`/v1/workspaces/${workspaceId}/members/${userId}`),
}

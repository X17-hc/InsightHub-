import { http } from './http'
import type { AgentDefinition } from '@/types'

export interface AgentPayload {
  name: string
  agentType: string
  runtime: string
  promptVersion: string
  systemPrompt?: string
  enabled: boolean
}

export const agentApi = {
  list: (workspaceId: string) => http.get<AgentDefinition[]>(`/v1/workspaces/${workspaceId}/agents`),
  create: (workspaceId: string, payload: AgentPayload) => http.post<AgentDefinition>(`/v1/workspaces/${workspaceId}/agents`, payload),
  update: (workspaceId: string, agentId: string, payload: Pick<AgentPayload, 'name' | 'promptVersion' | 'systemPrompt'>) => http.put<AgentDefinition>(`/v1/workspaces/${workspaceId}/agents/${agentId}`, payload),
  enable: (workspaceId: string, agentId: string, enabled: boolean) => http.post<AgentDefinition>(`/v1/workspaces/${workspaceId}/agents/${agentId}/${enabled ? 'enable' : 'disable'}`),
}

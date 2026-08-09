import { http } from './http'
import type { Citation, Report, ResearchTask, TaskAccepted, TaskControl, TaskEvent } from '@/types'

const base = (workspaceId: string) => `/v1/workspaces/${workspaceId}/research/tasks`

export const researchTaskApi = {
  list: (workspaceId: string) => http.get<ResearchTask[]>(base(workspaceId)),
  get: (workspaceId: string, taskId: string) => http.get<ResearchTask>(`${base(workspaceId)}/${taskId}`),
  create: (workspaceId: string, payload: { query: string; knowledgeBaseIds: string[] }) => http.post<TaskAccepted>(base(workspaceId), payload),
  pause: (workspaceId: string, taskId: string) => http.post<TaskControl>(`${base(workspaceId)}/${taskId}/pause`),
  resume: (workspaceId: string, taskId: string) => http.post<TaskControl>(`${base(workspaceId)}/${taskId}/resume`),
  cancel: (workspaceId: string, taskId: string) => http.post<TaskControl>(`${base(workspaceId)}/${taskId}/cancel`),
  retry: (workspaceId: string, taskId: string) => http.post<TaskAccepted>(`${base(workspaceId)}/${taskId}/retry`),
  report: (workspaceId: string, taskId: string) => http.get<Report>(`${base(workspaceId)}/${taskId}/report`),
  citations: (workspaceId: string, taskId: string) => http.get<Citation[]>(`${base(workspaceId)}/${taskId}/citations`),
  /** 历史事件（详情首屏灌入；与 SSE /events 分离） */
  eventLog: (workspaceId: string, taskId: string, fromEventNo = 0) =>
    http.get<TaskEvent[]>(`${base(workspaceId)}/${taskId}/event-records`, { params: { fromEventNo } }),
}

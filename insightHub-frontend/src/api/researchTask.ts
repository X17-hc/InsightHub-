import { http } from './http'
import type {
  ApprovePlanPayload,
  Citation,
  PlanActionResponse,
  PlanRevision,
  Report,
  ResearchTask,
  RevisePlanPayload,
  TaskAccepted,
  TaskControl,
  TaskEvent,
  ReportVersion,
  AnalysisArtifact,
} from '@/types'

const base = (workspaceId: string) => `/v1/workspaces/${workspaceId}/research/tasks`

export const researchTaskApi = {
  list: (workspaceId: string) => http.get<ResearchTask[]>(base(workspaceId)),
  get: (workspaceId: string, taskId: string) => http.get<ResearchTask>(`${base(workspaceId)}/${taskId}`),
  delete: (workspaceId: string, taskId: string) => http.delete<void>(`${base(workspaceId)}/${taskId}`),
  create: (workspaceId: string, payload: { query: string; knowledgeBaseIds: string[]; enableDataAnalysis?: boolean }) => http.post<TaskAccepted>(base(workspaceId), payload),
  pause: (workspaceId: string, taskId: string) => http.post<TaskControl>(`${base(workspaceId)}/${taskId}/pause`),
  resume: (workspaceId: string, taskId: string) => http.post<TaskControl>(`${base(workspaceId)}/${taskId}/resume`),
  cancel: (workspaceId: string, taskId: string) => http.post<TaskControl>(`${base(workspaceId)}/${taskId}/cancel`),
  retry: (workspaceId: string, taskId: string) => http.post<TaskAccepted>(`${base(workspaceId)}/${taskId}/retry`),
  report: (workspaceId: string, taskId: string) => http.get<Report>(`${base(workspaceId)}/${taskId}/report`),
  reportVersions: (workspaceId: string, taskId: string) => http.get<ReportVersion[]>(`${base(workspaceId)}/${taskId}/reports`),
  reportVersion: (workspaceId: string, taskId: string, version: number) => http.get<Report>(`${base(workspaceId)}/${taskId}/reports/${version}`),
  reportExport: (workspaceId: string, taskId: string, version: number, type: 'html' | 'pdf') =>
    http.get<Blob>(`${base(workspaceId)}/${taskId}/reports/${version}/exports/${type}`, { responseType: 'blob' }),
  artifacts: (workspaceId: string, taskId: string) => http.get<AnalysisArtifact[]>(`${base(workspaceId)}/${taskId}/artifacts`),
  artifactContent: (workspaceId: string, taskId: string, artifactId: string, disposition: 'inline' | 'attachment') =>
    http.get<Blob>(`${base(workspaceId)}/${taskId}/artifacts/${artifactId}/content`, { params: { disposition }, responseType: 'blob' }),
  citations: (workspaceId: string, taskId: string) => http.get<Citation[]>(`${base(workspaceId)}/${taskId}/citations`),
  reportCitations: (workspaceId: string, taskId: string, version: number) =>
    http.get<Citation[]>(`${base(workspaceId)}/${taskId}/reports/${version}/citations`),
  currentPlan: (workspaceId: string, taskId: string) =>
    http.get<PlanRevision>(`${base(workspaceId)}/${taskId}/plan`),
  planHistory: (workspaceId: string, taskId: string) =>
    http.get<PlanRevision[]>(`${base(workspaceId)}/${taskId}/plans`),
  approvePlan: (workspaceId: string, taskId: string, payload: ApprovePlanPayload) =>
    http.post<PlanActionResponse>(`${base(workspaceId)}/${taskId}/plan/approve`, payload),
  revisePlan: (workspaceId: string, taskId: string, payload: RevisePlanPayload) =>
    http.post<PlanActionResponse>(`${base(workspaceId)}/${taskId}/plan/revise`, payload),
  /** 历史事件（详情首屏灌入；与 SSE /events 分离） */
  eventLog: (workspaceId: string, taskId: string, fromEventNo = 0) =>
    http.get<TaskEvent[]>(`${base(workspaceId)}/${taskId}/event-records`, { params: { fromEventNo } }),
}

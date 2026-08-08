import { http } from './http'
import type { KnowledgeBase, KnowledgeDocument } from '@/types'

export const knowledgeApi = {
  list: (workspaceId: string) => http.get<KnowledgeBase[]>(`/v1/workspaces/${workspaceId}/knowledge-bases`),
  create: (workspaceId: string, payload: { name: string; description?: string }) => http.post<KnowledgeBase>(`/v1/workspaces/${workspaceId}/knowledge-bases`, payload),
  disable: (workspaceId: string, kbId: string) => http.delete<KnowledgeBase>(`/v1/workspaces/${workspaceId}/knowledge-bases/${kbId}`),
  documents: (workspaceId: string, kbId: string) => http.get<KnowledgeDocument[]>(`/v1/workspaces/${workspaceId}/knowledge-bases/${kbId}/documents`),
  upload: (workspaceId: string, kbId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post<KnowledgeDocument>(`/v1/workspaces/${workspaceId}/knowledge-bases/${kbId}/documents`, form)
  },
  reindex: (workspaceId: string, kbId: string, docId: string) => http.post<KnowledgeDocument>(`/v1/workspaces/${workspaceId}/knowledge-bases/${kbId}/documents/${docId}/reindex`),
}

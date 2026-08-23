export interface ApiEnvelope<T> {
  code: number | string
  data: T
  message: string
}

export interface AuthSession {
  accessToken: string
  refreshToken: string
  tokenType: string
  userId: string
  username: string
}

export interface UserProfile {
  userId: string
  username: string
  email?: string
  displayName?: string
}

export interface Workspace {
  id: string
  name: string
  description?: string
  ownerId: string
  status: number
}

export interface WorkspaceMember {
  id: string
  workspaceId: string
  userId: string
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  username: string
  displayName?: string
}

export type TaskStatus =
  | 'CREATED'
  | 'PLANNING'
  | 'WAITING_APPROVAL'
  | 'RUNNING'
  | 'PAUSING'
  | 'PAUSED'
  | 'REVIEWING'
  | 'GENERATING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export type QualityStatus = 'PENDING' | 'PASS' | 'FAIL' | 'NOT_EVALUATED' | 'LEGACY_SYNTHETIC'

export interface ResearchTask {
  taskId: string
  workspaceId: string
  creatorId: string
  query: string
  status: TaskStatus
  progress: number
  traceId?: string
  runId?: string
  errorCode?: string
  errorMessage?: string
  qualityStatus: QualityStatus
  qualitySummary?: string
  verifiedCitationCount: number
  totalCitationCount: number
  enableDataAnalysis?: boolean
  createdAt: string
}

export interface TaskAccepted {
  taskId: string
  status: TaskStatus
  traceId: string
}

export interface TaskControl {
  taskId: string
  status: TaskStatus
}

export interface TaskEvent {
  schemaVersion?: string
  eventId: number
  taskId: string
  runId?: string
  node?: string
  type: string
  timestamp?: string
  status?: TaskStatus
  data?: Record<string, unknown>
}

export type ResearchPlanTaskType = 'web_research' | 'knowledge_research'

export interface ResearchPlanTask {
  id: string
  type: ResearchPlanTaskType | string
  description: string
  dependsOn: string[]
}

export interface ResearchPlan {
  title: string
  objective: string
  researchDimensions?: string[]
  sourceRequirements?: {
    minVerifiedSources: number
    requireOfficialSources: boolean
  }
  tasks: ResearchPlanTask[]
}

export type PlanRevisionStatus = 'PENDING' | 'APPROVED' | 'SUPERSEDED'

export interface PlanRevision {
  id: string
  taskId: string
  workspaceId: string
  revisionNo: number
  status: PlanRevisionStatus
  plan: ResearchPlan
  planHash: string
  revisionInstruction?: string
  createdBy: string
  approvedBy?: string
  createdAt: string
  approvedAt?: string
  approvalRemark?: string
}

export interface PlanActionResponse {
  taskId: string
  revisionNo: number
  status: TaskStatus
  runId: string
}

export interface ApprovePlanPayload {
  expectedRevision: number
  remark?: string
}

export interface RevisePlanPayload {
  expectedRevision: number
  revision: string
}

export type CriticVerdict = 'PASS' | 'SUPPLEMENT' | 'FAIL'

export interface CritiqueResult {
  verdict: CriticVerdict
  summary?: string
  gaps: string[]
  limitations: string[]
  supplementTaskCount: number
  criticRound?: number
  maxCriticRounds?: number
}

export interface Report {
  id: string
  taskId: string
  workspaceId: string
  version: number
  title: string
  markdownContent: string
  status: string
  qualityStatus: QualityStatus
  qualitySummary?: string
  verifiedCitationCount: number
  candidateCitationCount: number
  createdAt: string
  updatedAt: string
}

export interface ReportVersion {
  id: string
  version: number
  title: string
  status: string
  qualityStatus: QualityStatus
  qualitySummary?: string
  verifiedCitationCount: number
  candidateCitationCount: number
  createdAt: string
  updatedAt: string
}

export interface AnalysisArtifact {
  id: string
  taskId: string
  workspaceId: string
  runId?: string
  artifactType: string
  title?: string
  fileName: string
  mimeType: string
  size: number
  status: string
  createdAt: string
}

export interface Citation {
  id: string
  reportId: string
  taskId: string
  citationNo: number
  sourceTitle?: string
  sourceUri?: string
  sourceType?: string
  documentId?: string
  chunkId?: string
  quotedText?: string
  verified: boolean | number
  verificationStatus: 'VERIFIED' | 'CANDIDATE' | 'SYNTHETIC'
  verificationReason?: string
  canonicalUri?: string
  finalUri?: string
  retrievedAt?: string
  contentHash?: string
  httpStatus?: number
  createdAt: string
}

export interface KnowledgeBase {
  id: string
  workspaceId: string
  name: string
  description?: string
  embeddingModel?: string
  chunkStrategy?: string
  status: string
  docCount: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

export type DocumentStatus = 'PENDING' | 'PARSING' | 'INDEXED' | 'FAILED'

export interface KnowledgeDocument {
  id: string
  knowledgeBaseId: string
  workspaceId: string
  fileName: string
  contentType: string
  fileSize: number
  contentHash: string
  sourceUri?: string
  parseStatus: DocumentStatus
  chunkCount: number
  errorMessage?: string
  uploadedBy: string
  createdAt: string
  updatedAt: string
}

export interface AgentDefinition {
  id: string
  workspaceId: string
  name: string
  agentType: string
  runtime: 'PYTHON' | 'JAVA'
  promptVersion: string
  systemPrompt?: string
  enabled: boolean
  version: number
}

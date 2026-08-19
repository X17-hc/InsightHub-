import type { TaskEvent, TaskStatus } from '@/types'
import { isTerminalTaskStatus } from '@/utils/display'

type EventSourceFactory = (url: string) => EventSource
const EVENT_TYPES = [
  'TASK_STARTED',
  'PLAN_CREATED',
  'APPROVAL_REQUIRED',
  'NODE_STARTED',
  'NODE_COMPLETED',
  'TOOL_CALLED',
  'TOOL_COMPLETED',
  'CRITIC_STARTED',
  'CRITIQUE_COMPLETED',
  'SUPPLEMENT_RESEARCH_REQUESTED',
  'REPORT_DELTA',
  'TASK_PAUSED',
  'TASK_COMPLETED',
  'TASK_FAILED',
  'TASK_RESULT',
]

export class TaskEventSource {
  private source: EventSource | null = null
  private retryTimer: number | null = null
  private retryCount = 0
  private closed = false
  private maxEventNo: number
  constructor(private readonly workspaceId: string, private readonly taskId: string, private readonly accessToken: string, private readonly onEvent: (event: TaskEvent) => void, private readonly onConnectionChange?: (connected: boolean) => void, fromEventNo = 0, private readonly factory: EventSourceFactory = (url) => new EventSource(url)) { this.maxEventNo = fromEventNo }
  connect(): void {
    if (this.closed || this.source) return
    const params = new URLSearchParams({ access_token: this.accessToken, fromEventNo: String(this.maxEventNo) })
    const url = `/api/v1/workspaces/${encodeURIComponent(this.workspaceId)}/research/tasks/${encodeURIComponent(this.taskId)}/events?${params}`
    const source = this.factory(url); this.source = source
    source.onopen = () => { this.retryCount = 0; this.onConnectionChange?.(true) }
    source.onerror = () => { this.onConnectionChange?.(false); this.reconnect() }
    const handler = (raw: Event) => this.handle(raw as MessageEvent<string>)
    source.onmessage = handler; EVENT_TYPES.forEach((type) => source.addEventListener(type, handler))
  }
  close(): void {
    this.closed = true; this.source?.close(); this.source = null
    if (this.retryTimer !== null) window.clearTimeout(this.retryTimer)
    this.retryTimer = null; this.onConnectionChange?.(false)
  }
  getLastEventNo(): number { return this.maxEventNo }
  private handle(raw: MessageEvent<string>): void {
    try {
      const payload = JSON.parse(raw.data) as Partial<TaskEvent>; const eventNo = Number(raw.lastEventId || payload.eventId || 0)
      if (eventNo > 0 && eventNo <= this.maxEventNo) return
      if (eventNo > 0) this.maxEventNo = eventNo
      const event = { ...payload, eventId: eventNo, taskId: payload.taskId || this.taskId, type: payload.type || raw.type || 'message' } as TaskEvent
      this.onEvent(event)
      const status = (event.status || event.data?.status) as TaskStatus | undefined
      if ((status && isTerminalTaskStatus(status)) || event.type === 'TASK_FAILED') this.close()
    } catch { /* malformed heartbeat is intentionally ignored */ }
  }
  private reconnect(): void {
    this.source?.close(); this.source = null
    if (this.closed || this.retryTimer !== null) return
    const delay = Math.min(30_000, 1_000 * 2 ** this.retryCount); this.retryCount += 1
    this.retryTimer = window.setTimeout(() => { this.retryTimer = null; this.connect() }, delay)
  }
}

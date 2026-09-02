import type { TaskEvent, TaskStatus } from '@/types'
import { isTerminalTaskStatus } from '@/utils/display'

type FetchFactory = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

interface ParsedSseEvent {
  data: string
  id: string
  type: string
}

/**
 * 使用 fetch 读取 SSE，以便通过 Authorization header 认证。
 *
 * 原生 EventSource 不能设置请求头，把 JWT 放入 query string 会泄露到代理日志、
 * 浏览器历史和监控系统。该实现保留 fromEventNo 续传语义。
 */
export class TaskEventSource {
  private controller: AbortController | null = null
  private retryTimer: number | null = null
  private retryCount = 0
  private closed = false
  private maxEventNo: number

  constructor(
    private readonly workspaceId: string,
    private readonly taskId: string,
    private readonly accessToken: string,
    private readonly onEvent: (event: TaskEvent) => void,
    private readonly onConnectionChange?: (connected: boolean) => void,
    fromEventNo = 0,
    private readonly fetchFactory: FetchFactory = (input, init) => fetch(input, init),
  ) {
    this.maxEventNo = fromEventNo
  }

  connect(): void {
    if (this.closed || this.controller) return
    const controller = new AbortController()
    this.controller = controller
    void this.open(controller)
  }

  close(): void {
    this.closed = true
    this.controller?.abort()
    this.controller = null
    if (this.retryTimer !== null) window.clearTimeout(this.retryTimer)
    this.retryTimer = null
    this.onConnectionChange?.(false)
  }

  getLastEventNo(): number {
    return this.maxEventNo
  }

  private async open(controller: AbortController): Promise<void> {
    const params = new URLSearchParams({ fromEventNo: String(this.maxEventNo) })
    const url = `/api/v1/workspaces/${encodeURIComponent(this.workspaceId)}/research/tasks/${encodeURIComponent(this.taskId)}/events?${params}`
    try {
      const response = await this.fetchFactory(url, {
        headers: {
          Accept: 'text/event-stream',
          Authorization: `Bearer ${this.accessToken}`,
          'Last-Event-ID': String(this.maxEventNo),
        },
        signal: controller.signal,
      })
      if (!response.ok || !response.body) throw new Error(`SSE_HTTP_${response.status}`)
      this.retryCount = 0
      this.onConnectionChange?.(true)
      await this.consume(response.body, controller.signal)
      if (!this.closed) throw new Error('SSE_STREAM_ENDED')
    } catch {
      if (!controller.signal.aborted && !this.closed) this.reconnect()
    } finally {
      if (this.controller === controller) this.controller = null
    }
  }

  private async consume(body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    try {
      while (!signal.aborted) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          const frame = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          const parsed = this.parseFrame(frame)
          if (parsed) this.handle(parsed)
          boundary = buffer.indexOf('\n\n')
        }
      }
    } finally {
      reader.releaseLock()
    }
  }

  private parseFrame(frame: string): ParsedSseEvent | null {
    let type = 'message'
    let id = ''
    const data: string[] = []
    for (const line of frame.split('\n')) {
      if (!line || line.startsWith(':')) continue
      const separator = line.indexOf(':')
      const field = separator < 0 ? line : line.slice(0, separator)
      const value = separator < 0 ? '' : line.slice(separator + 1).replace(/^ /, '')
      if (field === 'event') type = value || 'message'
      else if (field === 'id') id = value
      else if (field === 'data') data.push(value)
    }
    return data.length ? { data: data.join('\n'), id, type } : null
  }

  private handle(raw: ParsedSseEvent): void {
    try {
      const payload = JSON.parse(raw.data) as Partial<TaskEvent>
      const eventNo = Number(raw.id || payload.eventId || 0)
      if (eventNo > 0 && eventNo <= this.maxEventNo) return
      if (eventNo > 0) this.maxEventNo = eventNo
      const event = {
        ...payload,
        eventId: eventNo,
        taskId: payload.taskId || this.taskId,
        type: payload.type || raw.type || 'message',
      } as TaskEvent
      this.onEvent(event)
      const status = (event.status || event.data?.status) as TaskStatus | undefined
      // TASK_FAILED 先于持久化的 TASK_RESULT；只在规范终态携带 terminal status 时关闭。
      if (status && isTerminalTaskStatus(status)) this.close()
    } catch {
      // 心跳和格式不完整的帧不应中断后续事件。
    }
  }

  private reconnect(): void {
    this.controller?.abort()
    this.controller = null
    this.onConnectionChange?.(false)
    if (this.closed || this.retryTimer !== null) return
    const delay = Math.min(30_000, 1_000 * 2 ** this.retryCount)
    this.retryCount += 1
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null
      this.connect()
    }, delay)
  }
}

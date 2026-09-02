import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TaskEventSource } from './TaskEventSource'
import type { TaskEvent } from '@/types'

function responseWithFrames(frames: string[]): Response {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) controller.enqueue(encoder.encode(frame))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

async function flush(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('TaskEventSource', () => {
  beforeEach(() => vi.stubGlobal('window', { setTimeout, clearTimeout }))

  it('uses Bearer header, resumes, deduplicates and closes on terminal result', async () => {
    const received: TaskEvent[] = []
    const fetcher = vi.fn(async () => responseWithFrames([
      'id: 8\nevent: NODE_STARTED\ndata: {"eventId":8,"type":"NODE_STARTED"}\n\n',
      'id: 8\nevent: NODE_STARTED\ndata: {"eventId":8,"type":"NODE_STARTED"}\n\n',
      'id: 9\nevent: TASK_RESULT\ndata: {"eventId":9,"type":"TASK_RESULT","status":"COMPLETED"}\n\n',
    ]))
    const stream = new TaskEventSource('workspace demo', 'task/1', 'access token', (event) => received.push(event), undefined, 7, fetcher)
    stream.connect()
    await flush()

    const [url, init] = fetcher.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toContain('fromEventNo=7')
    expect(url).not.toContain('access_token')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer access token')
    expect(received.map((event) => event.eventId)).toEqual([8, 9])
    expect(stream.getLastEventNo()).toBe(9)
  })

  it('parses named events and keeps failure open until canonical task result', async () => {
    const received: TaskEvent[] = []
    const fetcher = vi.fn(async () => responseWithFrames([
      'id: 1\nevent: TASK_FAILED\ndata: {"eventId":1,"type":"TASK_FAILED","data":{"code":"SEARCH_NO_RESULTS"}}\n\n',
      'id: 2\nevent: SANDBOX_COMPLETED\ndata: {"eventId":2,"type":"SANDBOX_COMPLETED"}\n\n',
      'id: 3\nevent: TASK_RESULT\ndata: {"eventId":3,"type":"TASK_RESULT","status":"FAILED"}\n\n',
    ]))
    const stream = new TaskEventSource('workspace', 'task', 'token', (event) => received.push(event), undefined, 0, fetcher)
    stream.connect()
    await flush()

    expect(received.map((event) => event.type)).toEqual(['TASK_FAILED', 'SANDBOX_COMPLETED', 'TASK_RESULT'])
  })
})

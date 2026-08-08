import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TaskEventSource } from './TaskEventSource'
import type { TaskEvent } from '@/types'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent<string>) => void) | null = null
  listeners = new Map<string, (event: Event) => void>()
  closed = false
  constructor(public readonly url: string) { FakeEventSource.instances.push(this) }
  addEventListener(type: string, listener: EventListenerOrEventListenerObject) { this.listeners.set(type, listener as (event: Event) => void) }
  close() { this.closed = true }
  emit(type: string, payload: object, id: number) {
    const event = { type, data: JSON.stringify(payload), lastEventId: String(id) } as MessageEvent<string>
    if (type === 'message') this.onmessage?.(event)
    else this.listeners.get(type)?.(event)
  }
}

describe('TaskEventSource', () => {
  beforeEach(() => { FakeEventSource.instances = []; vi.stubGlobal('window', { setTimeout, clearTimeout }) })
  it('resumes, deduplicates and closes on a terminal result', () => {
    const received: TaskEvent[] = []
    const stream = new TaskEventSource('workspace demo', 'task/1', 'access token', (event) => received.push(event), undefined, 7, (url) => new FakeEventSource(url) as unknown as EventSource)
    stream.connect()
    const source = FakeEventSource.instances[0]
    expect(source.url).toContain('fromEventNo=7')
    expect(source.url).toContain('access_token=access+token')
    source.emit('NODE_STARTED', { eventId: 8, type: 'NODE_STARTED' }, 8)
    source.emit('NODE_STARTED', { eventId: 8, type: 'NODE_STARTED' }, 8)
    source.emit('TASK_RESULT', { eventId: 9, type: 'TASK_RESULT', status: 'COMPLETED' }, 9)
    expect(received.map((event) => event.eventId)).toEqual([8, 9])
    expect(stream.getLastEventNo()).toBe(9)
    expect(source.closed).toBe(true)
  })
})

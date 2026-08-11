import { onBeforeUnmount, ref } from 'vue'
import { TaskEventSource } from '@/services/TaskEventSource'
import type { TaskEvent } from '@/types'

interface TaskEventOptions {
  workspaceId: () => string
  taskId: () => string
  accessToken: () => string | undefined
  onEvent: (event: TaskEvent) => void
}

/** SSE 生命周期和断线续传封装。 */
export function useTaskEvents(options: TaskEventOptions) {
  const connected = ref(false)
  const lastEventNo = ref(0)
  let source: TaskEventSource | null = null

  const seed = (events: TaskEvent[]) => {
    lastEventNo.value = events.reduce((max, event) => Math.max(max, event.eventId || 0), 0)
  }

  const connect = () => {
    source?.close()
    const accessToken = options.accessToken()
    if (!accessToken) return
    source = new TaskEventSource(options.workspaceId(), options.taskId(), accessToken, (event) => {
      lastEventNo.value = source?.getLastEventNo() ?? event.eventId
      options.onEvent(event)
    }, (value) => { connected.value = value }, lastEventNo.value)
    source.connect()
  }

  const close = () => {
    source?.close()
    source = null
    connected.value = false
  }

  onBeforeUnmount(close)
  return { connected, lastEventNo, seed, connect, close }
}

import { onBeforeUnmount, ref } from 'vue'
import type { TaskEvent } from '@/types'

const FLUSH_MS = 100

/** 报告流缓冲器：限制 Markdown 组件的重渲染频率。 */
export function useStreamingReport() {
  const content = ref('')
  let pending = ''
  let timer: number | undefined

  const flush = () => {
    if (pending) {
      content.value += pending
      pending = ''
    }
    timer = undefined
  }

  const receive = (event: TaskEvent) => {
    const delta = event.data?.delta
    if (event.type !== 'REPORT_DELTA' || typeof delta !== 'string') return
    pending += delta
    if (timer === undefined) timer = window.setTimeout(flush, FLUSH_MS)
  }

  const finish = () => {
    if (timer !== undefined) window.clearTimeout(timer)
    flush()
  }

  const reset = () => {
    if (timer !== undefined) window.clearTimeout(timer)
    timer = undefined
    pending = ''
    content.value = ''
  }

  onBeforeUnmount(reset)
  return { content, receive, finish, reset }
}

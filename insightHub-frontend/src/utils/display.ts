import type { TaskStatus } from '@/types'
export const taskStatusMeta: Record<TaskStatus, { label: string; color: string }> = {
  CREATED: { label: '已创建', color: 'default' }, PLANNING: { label: '规划中', color: 'blue' }, WAITING_APPROVAL: { label: '待确认', color: 'gold' }, RUNNING: { label: '运行中', color: 'blue' }, PAUSING: { label: '暂停中', color: 'gold' }, PAUSED: { label: '已暂停', color: 'gold' }, REVIEWING: { label: '复核中', color: 'cyan' }, GENERATING: { label: '生成报告', color: 'blue' }, COMPLETED: { label: '已完成', color: 'green' }, FAILED: { label: '失败', color: 'red' }, CANCELLED: { label: '已取消', color: 'default' },
}
export const documentStatusMeta = { PENDING: { label: '等待解析', color: 'default' }, PARSING: { label: '解析中', color: 'blue' }, INDEXED: { label: '已索引', color: 'green' }, FAILED: { label: '解析失败', color: 'red' } } as const
export function formatDate(value?: string): string { if (!value) return '--'; const date = new Date(value); if (Number.isNaN(date.getTime())) return value; return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date) }
export function formatBytes(bytes?: number): string { if (!bytes) return '0 B'; if (bytes < 1024) return `${bytes} B`; if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`; return `${(bytes / 1024 / 1024).toFixed(1)} MB` }

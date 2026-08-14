import type { TaskStatus } from '@/types'

/** 任务状态 → 展示文案 + el-tag 类型 */
export const TASK_STATUS_MAP: Record<TaskStatus, { label: string; tag: 'success' | 'warning' | 'info' | 'danger' }> = {
  PENDING: { label: '待处理', tag: 'info' },
  RUNNING: { label: '执行中', tag: 'warning' },
  SUCCESS: { label: '成功', tag: 'success' },
  FAILED: { label: '失败', tag: 'danger' },
}

/** 任务是否处于进行中（需要轮询刷新） */
export function isActive(status: TaskStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

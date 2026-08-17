import type { TaskStatus } from '@/types'

export type DisplayTaskStatus = TaskStatus | 'MANUAL_REVIEW'

/** 任务/报销状态 → 展示文案 + el-tag 类型 */
export const TASK_STATUS_MAP: Record<DisplayTaskStatus, { label: string; tag: 'success' | 'warning' | 'info' | 'danger' }> = {
  PENDING: { label: '待处理', tag: 'info' },
  RUNNING: { label: '执行中', tag: 'warning' },
  SUCCESS: { label: '成功', tag: 'success' },
  FAILED: { label: '失败', tag: 'danger' },
  APPROVAL_PENDING: { label: '待审批', tag: 'warning' },
  REJECTED: { label: '已驳回', tag: 'danger' },
  MANUAL_REVIEW: { label: '人工复核', tag: 'warning' },
}

/** 角色展示文案 */
export const AGENT_ROLE_MAP: Record<string, string> = {
  SCHEDULER: '统筹调度',
  DOCUMENT_PARSER: '票据解析',
  BUDGET_CALCULATOR: '预算核算',
  RULE_VALIDATOR: '规则校验',
  RISK_AUDITOR: '风控审计',
}

/** 任务是否处于进行中（需要轮询刷新） */
export function isActive(status: TaskStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

/** 从任务取关联报销单 ID（仅 REIMBURSEMENT 业务任务，入参快照含 reimbId） */
export function reimbIdOf(task: { taskType?: string; inputParams?: Record<string, unknown> }): number | null {
  if (task.taskType !== 'REIMBURSEMENT') return null
  const v = task.inputParams?.reimbId
  return typeof v === 'number' ? v : null
}

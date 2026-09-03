import type { AuditAction, AuditTicketStatus, AuditTriggerType, TaskStatus } from '@/types'

export type DisplayTaskStatus = TaskStatus | 'MANUAL_REVIEW'

/** 状态戳语义色（StatusStamp）：running 墨青蓝 / success 账簿绿 / danger 朱砂 / pending 赭金 / muted 中性 */
export type StampTone = 'running' | 'success' | 'danger' | 'pending' | 'muted'

/** 任务/报销状态 → 展示文案 + 状态戳语义色（tag 字段保留给兼容 el-tag 的场景） */
export const TASK_STATUS_MAP: Record<DisplayTaskStatus, { label: string; tag: 'success' | 'warning' | 'info' | 'danger'; tone: StampTone }> = {
  PENDING: { label: '待处理', tag: 'info', tone: 'muted' },
  RUNNING: { label: '执行中', tag: 'warning', tone: 'running' },
  SUCCESS: { label: '成功', tag: 'success', tone: 'success' },
  FAILED: { label: '失败', tag: 'danger', tone: 'danger' },
  APPROVAL_PENDING: { label: '待审批', tag: 'warning', tone: 'pending' },
  REJECTED: { label: '已驳回', tag: 'danger', tone: 'danger' },
  CANCELLED: { label: '已作废', tag: 'info', tone: 'muted' },
  MANUAL_REVIEW: { label: '人工复核', tag: 'warning', tone: 'pending' },
}

/** 角色展示文案 */
export const AGENT_ROLE_MAP: Record<string, string> = {
  SCHEDULER: '统筹调度',
  DOCUMENT_PARSER: '票据解析',
  BUDGET_CALCULATOR: '预算核算',
  RULE_VALIDATOR: '规则校验',
  RISK_AUDITOR: '风控审计',
}

/** 审批工单状态 → 展示文案 + 状态戳语义色 */
export const AUDIT_STATUS_MAP: Record<AuditTicketStatus, { label: string; tag: 'success' | 'warning' | 'info' | 'danger'; tone: StampTone }> = {
  PENDING: { label: '待审批', tag: 'warning', tone: 'pending' },
  APPROVED: { label: '已通过', tag: 'success', tone: 'success' },
  REJECTED: { label: '已驳回', tag: 'danger', tone: 'danger' },
  AMENDED: { label: '已修改重跑中', tag: 'info', tone: 'running' },
  TERMINATED: { label: '已终止', tag: 'danger', tone: 'danger' },
  WITHDRAW_PENDING: { label: '撤销待审', tag: 'warning', tone: 'pending' },
  WITHDRAWN: { label: '已撤回/撤销', tag: 'info', tone: 'muted' },
}

/** 工单触发类型 → 展示文案 + 状态戳语义色 */
export const AUDIT_TRIGGER_MAP: Record<AuditTriggerType, { label: string; tag: 'success' | 'warning' | 'info' | 'danger'; tone: StampTone }> = {
  OVER_LIMIT: { label: '金额超限', tag: 'danger', tone: 'danger' },
  RULE_FAIL: { label: '规则不通过', tag: 'warning', tone: 'pending' },
  RISK_HIT: { label: '风控存疑', tag: 'warning', tone: 'pending' },
}

/** 审批动作 → 展示文案 */
export const AUDIT_ACTION_MAP: Record<AuditAction, string> = {
  SUBMIT: '生成工单',
  APPROVE: '审批通过',
  REJECT: '审批驳回',
  AMEND: '修改重跑',
  TERMINATE: '终止工单',
  RERUN: '重跑复位',
  RERUN_FAILED: '重跑失败复位',
  WITHDRAW: '提交人撤回',
  WITHDRAW_REQ: '发起撤销申请',
  WITHDRAW_AGREE: '同意撤销',
  WITHDRAW_REFUSE: '拒绝撤销',
}

/** 任务是否处于进行中（需要轮询刷新） */
export function isActive(status: TaskStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

/**
 * 任务是否可手动续跑：仅未启动的 PENDING。
 * <p>RUNNING 执行中不提供——正常推进中点击续跑会强制重置在途步骤并重驱流水线（后端 resume
 * 允许 RUNNING 仅用于卡死恢复），界面不宜暴露；卡死任务的手动恢复暂走接口层（TODO：服务端陈旧任务检测）。</p>
 */
export function canResume(status: TaskStatus): boolean {
  return status === 'PENDING'
}

/** 从任务取关联报销单 ID（仅 REIMBURSEMENT 业务任务，入参快照含 reimbId） */
export function reimbIdOf(task: { taskType?: string; inputParams?: Record<string, unknown> }): number | null {
  if (task.taskType !== 'REIMBURSEMENT') return null
  const v = task.inputParams?.reimbId
  return typeof v === 'number' ? v : null
}

import { http } from './request'
import type {
  AuditTicketVO,
  PageResult,
  ReimbursementDetailVO,
  ReimbursementResubmitRequest,
  ReimbursementSubmitRequest,
  ReimbursementVO,
} from '@/types'

export interface ReimbursementPageQuery {
  pageNum?: number
  pageSize?: number
  status?: string
}

/**
 * 提交报销单（生成审核任务）。
 * 服务端对明细求和（Decimal），总金额不信任客户端；走网关由 JWT 注入 X-Tenant-Id / X-User-Id。
 */
export function submitReimbursement(data: ReimbursementSubmitRequest): Promise<ReimbursementVO> {
  return http.post<ReimbursementVO>('/reimbursements', data)
}

/** 报销单分页（仅本人，按 X-User-Id 过滤） */
export function getReimbursementPage(
  params: ReimbursementPageQuery,
): Promise<PageResult<ReimbursementVO>> {
  return http.get<PageResult<ReimbursementVO>>('/reimbursements', { params })
}

/** 报销单详情（基础信息 + 明细 + 附件预签名 URL） */
export function getReimbursementDetail(id: number | string): Promise<ReimbursementDetailVO> {
  return http.get<ReimbursementDetailVO>(`/reimbursements/${id}`)
}

/**
 * 修改明细重跑（P3b 提交人动作）：标题/部门不可改（服务端沿用库内旧值），返回关联任务 ID。
 */
export function resubmitReimbursement(
  id: number | string,
  data: ReimbursementResubmitRequest,
): Promise<number> {
  return http.post<number>(`/reimbursements/${id}/resubmit`, data)
}

/** 撤回报销单（待审批时直接撤回；工单 WITHDRAWN，任务/报销单作废） */
export function withdrawReimbursement(id: number | string): Promise<AuditTicketVO> {
  return http.post<AuditTicketVO>(`/reimbursements/${id}/withdraw`)
}

/** 发起撤销申请（已通过后发起，财务同意/拒绝后生效；幂等） */
export function withdrawRequestReimbursement(id: number | string): Promise<AuditTicketVO> {
  return http.post<AuditTicketVO>(`/reimbursements/${id}/withdraw-request`)
}

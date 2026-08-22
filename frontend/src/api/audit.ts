import { http } from './request'
import type { AuditActionRequest, AuditRecordVO, AuditTicketDetailVO, AuditTicketVO, PageResult } from '@/types'

export interface AuditTicketPageQuery {
  pageNum?: number
  pageSize?: number
  /** 状态筛选（PENDING / APPROVED / REJECTED / AMENDED / TERMINATED） */
  status?: string
  /** 按任务过滤（任务/报销单详情「查看审批工单」入口预过滤） */
  taskId?: number
}

/** 工单分页（非财务角色仅返回本人提交的工单） */
export function getAuditTicketPage(params: AuditTicketPageQuery): Promise<PageResult<AuditTicketVO>> {
  return http.get<PageResult<AuditTicketVO>>('/audit/tickets', { params })
}

/** 工单详情（工单 + 关联报销单详情 + 留痕时间线） */
export function getAuditTicketDetail(id: number | string): Promise<AuditTicketDetailVO> {
  return http.get<AuditTicketDetailVO>(`/audit/tickets/${id}`)
}

/** 工单留痕时间线 */
export function getAuditRecords(id: number | string): Promise<AuditRecordVO[]> {
  return http.get<AuditRecordVO[]>(`/audit/tickets/${id}/records`)
}

/** 审批通过 */
export function approveAuditTicket(id: number | string, data?: AuditActionRequest): Promise<AuditTicketVO> {
  return http.post<AuditTicketVO>(`/audit/tickets/${id}/approve`, data)
}

/** 审批驳回 */
export function rejectAuditTicket(id: number | string, data?: AuditActionRequest): Promise<AuditTicketVO> {
  return http.post<AuditTicketVO>(`/audit/tickets/${id}/reject`, data)
}

/** 终止工单 */
export function terminateAuditTicket(id: number | string, data?: AuditActionRequest): Promise<AuditTicketVO> {
  return http.post<AuditTicketVO>(`/audit/tickets/${id}/terminate`, data)
}

/** 同意撤销（提交人已发起撤销申请后财务同意：工单 WITHDRAWN，任务/报销单作废） */
export function withdrawAgreeAuditTicket(id: number | string, data?: AuditActionRequest): Promise<AuditTicketVO> {
  return http.post<AuditTicketVO>(`/audit/tickets/${id}/withdraw-agree`, data)
}

/** 拒绝撤销（财务拒绝：工单回 APPROVED 原地返回） */
export function withdrawRefuseAuditTicket(id: number | string, data?: AuditActionRequest): Promise<AuditTicketVO> {
  return http.post<AuditTicketVO>(`/audit/tickets/${id}/withdraw-refuse`, data)
}

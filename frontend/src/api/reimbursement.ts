import { http } from './request'
import type {
  PageResult,
  ReimbursementDetailVO,
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

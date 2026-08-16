import { http } from './request'
import type { RuleSaveRequest, RuleVO } from '@/types'

/**
 * 财务规则配置（P2c）：列表/新增/修改/启停/发布。
 * 走网关 9080，由 JWT 注入 X-Tenant-Id / X-User-Id；发布写 Nacos 生效集，改规则不重启即时生效。
 */
export function getRules(): Promise<RuleVO[]> {
  return http.get<RuleVO[]>('/rules')
}

export function createRule(data: RuleSaveRequest): Promise<RuleVO> {
  return http.post<RuleVO>('/rules', data)
}

export function updateRule(id: number, data: RuleSaveRequest): Promise<RuleVO> {
  return http.put<RuleVO>(`/rules/${id}`, data)
}

export function toggleRule(id: number): Promise<RuleVO> {
  return http.post<RuleVO>(`/rules/${id}/toggle`)
}

export function publishRule(id: number): Promise<RuleVO> {
  return http.post<RuleVO>(`/rules/${id}/publish`)
}

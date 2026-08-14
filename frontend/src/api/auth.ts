import { http } from './request'
import type { LoginResult, UserVO } from '@/types'

export interface LoginRequest {
  username: string
  password: string
  /** 租户编码，为空默认 default */
  tenantCode?: string
}

/** 登录（网关白名单，无需鉴权） */
export function login(data: LoginRequest): Promise<LoginResult> {
  return http.post<LoginResult>('/auth/login', data)
}

/** 登出：网关将当前 token 写入 Redis 黑名单，此后该 token 全部请求 401 */
export function logout(): Promise<null> {
  return http.post<null>('/auth/logout')
}

/** 当前用户信息（网关注入 X-User-Id） */
export function getMe(): Promise<UserVO> {
  return http.get<UserVO>('/auth/me')
}

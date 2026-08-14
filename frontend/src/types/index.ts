/**
 * 类型定义：与后端接口契约一一对应（见 docs/api/*.md）。
 * 注意：本项目 tsconfig 开启 erasableSyntaxOnly，禁止 enum，一律用字符串字面量联合类型。
 */

/** 后端统一响应体 R<T> */
export interface R<T = unknown> {
  code: number
  message: string
  data: T
}

/** MyBatis-Plus 分页响应 */
export interface PageResult<T> {
  total: number
  size: number
  current: number
  records: T[]
  [key: string]: unknown
}

/** 用户信息（登录 / /auth/me 返回的 user） */
export interface UserVO {
  id: number
  tenantId: number
  username: string
  realName: string | null
  phone: string | null
  roles: string[]
}

/** 登录结果 */
export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

/** 任务状态（状态机：PENDING → RUNNING → SUCCESS / FAILED） */
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

/** 任务 */
export interface TaskVO {
  id: number
  tenantId: number
  taskNo: string
  title: string
  inputParams: Record<string, unknown>
  status: TaskStatus
  totalSteps: number
  finishedSteps: number
  result: Record<string, unknown> | null
  errorMsg: string | null
  createdAt: string
}

/** 任务步骤 */
export interface TaskStepVO {
  id: number
  stepNo: number
  stepName: string
  stepType: string
  toolName: string | null
  inputParams: Record<string, unknown> | null
  output: Record<string, unknown> | null
  status: TaskStatus
  errorMsg: string | null
  retryCount: number
}

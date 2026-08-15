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
  /** 业务类型：REIMBURSEMENT 报销审核 / GENERIC 通用分析 */
  taskType?: string
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

/** 文件（file-service 上传/详情返回） */
export interface FileVO {
  id: number
  tenantId: number
  fileName: string
  objectName: string
  contentType: string
  size: number
  /** 预签名预览 URL */
  url: string
}

/** 费用类型 */
export type ExpenseType = 'TRAVEL' | 'ENTERTAINMENT' | 'OFFICE'

/** 报销单审核状态（对齐任务状态机） */
export type ReimbStatus = TaskStatus

/** 报销单（列表 / 提交返回） */
export interface ReimbursementVO {
  id: number
  tenantId: number
  reimbNo: string
  title: string
  expenseType: ExpenseType
  applicantId: number
  deptName: string
  totalAmount: number
  /** 关联审核任务 ID（提交后反写） */
  taskId: number | null
  status: ReimbStatus
  claimDate: string
  remark: string | null
  createdAt: string | null
}

/** 报销明细项 */
export interface ReimbursementItemVO {
  name: string
  amount: number
  amountType?: string | null
  quantity?: number | null
  unitPrice?: number | null
  date?: string | null
}

/** 报销附件（业务字段 + file-service 元数据 + 预签名 URL） */
export interface AttachmentVO {
  id: number
  tenantId: number
  reimbId: number
  fileRecordId: number
  fileName: string | null
  objectName: string | null
  fileType: string
  ocrStatus: string
  url: string
}

/** 报销单详情（基础信息 + 明细 + 附件） */
export interface ReimbursementDetailVO {
  reimbursement: ReimbursementVO
  items: ReimbursementItemVO[]
  attachments: AttachmentVO[]
}

/** 报销单提交请求（明细 + 附件 file_record id 列表；总金额由服务端求和） */
export interface ReimbursementSubmitRequest {
  title: string
  expenseType: ExpenseType
  deptName: string
  claimDate: string
  remark?: string
  items: { name: string; amount: number; amountType?: string; quantity?: number; unitPrice?: number; date?: string }[]
  fileRecordIds: number[]
}

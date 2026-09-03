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
  /** 权限标识符列表（P3.5：前端菜单/路由/按钮动态渲染依据） */
  perms: string[]
}

/** 登录结果 */
export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  user: UserVO
}

/** 任务状态（状态机：PENDING → RUNNING → SUCCESS / FAILED / APPROVAL_PENDING / REJECTED / CANCELLED） */
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'APPROVAL_PENDING' | 'REJECTED' | 'CANCELLED'

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
  agentRole: string | null
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

/** 报销单审核状态（任务状态 + 报销域 MANUAL_REVIEW） */
export type ReimbStatus = TaskStatus | 'MANUAL_REVIEW'

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
  /** P2c 差旅/补贴评估字段 */
  city?: string | null
  hotelDays?: number | null
  hotelAmount?: number | null
  transportAmount?: number | null
  subsidyAmount?: number | null
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
  /** OCR 抽取字段（P3b 工单详情对照展示；无 OCR 为 null） */
  ocrResult: Record<string, unknown> | null
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
  /** 部门（提交时快照；P3.5b 部门树选择器取权威名） */
  deptName: string
  /** 部门 ID（P3.5b，树选择器提交；为空则后端不落 dept_id） */
  deptId?: number | null
  claimDate: string
  remark?: string
  items: {
    name: string
    amount: number
    amountType?: string
    quantity?: number
    unitPrice?: number
    date?: string
    /** P2c 差旅/补贴评估字段 */
    city?: string
    hotelDays?: number
    hotelAmount?: number
    transportAmount?: number
    subsidyAmount?: number
  }[]
  fileRecordIds: number[]
}

/** 财务规则类型（P2c 四类全结构化） */
export type RuleType = 'AMOUNT_LIMIT' | 'REIMBURSE_EXPIRE' | 'TRAVEL_STANDARD' | 'SUBSIDY_LIMIT'

/** 财务规则（配置管理响应；published=1 生效 / 0 草稿） */
export interface RuleVO {
  id: number
  ruleCode: string
  ruleName: string
  ruleType: RuleType
  ruleConfig: Record<string, unknown>
  enabled: number
  published: number
  version: string
  createdAt: string | null
  updatedAt: string | null
}

/** 财务规则新增/修改请求 */
export interface RuleSaveRequest {
  ruleCode: string
  ruleName: string
  ruleType: RuleType
  ruleConfig: Record<string, unknown>
  enabled?: number
}

/** 审批工单状态（P3b 状态机：PENDING → APPROVED / REJECTED / AMENDED / TERMINATED / WITHDRAWN；APPROVED → WITHDRAW_PENDING 撤销待审） */
export type AuditTicketStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'AMENDED' | 'TERMINATED' | 'WITHDRAW_PENDING' | 'WITHDRAWN'

/** 审批动作（留痕 action） */
export type AuditAction =
  | 'SUBMIT'
  | 'APPROVE'
  | 'REJECT'
  | 'AMEND'
  | 'TERMINATE'
  | 'RERUN'
  | 'RERUN_FAILED'
  | 'WITHDRAW'
  | 'WITHDRAW_REQ'
  | 'WITHDRAW_AGREE'
  | 'WITHDRAW_REFUSE'

/** 工单触发类型（P3b 确定性映射：OVER_LIMIT > RULE_FAIL > RISK_HIT） */
export type AuditTriggerType = 'OVER_LIMIT' | 'RULE_FAIL' | 'RISK_HIT'

/** 审批工单（列表 / 详情基础信息） */
export interface AuditTicketVO {
  id: number
  tenantId: number
  taskId: number
  ticketNo: string
  title: string
  triggerType: AuditTriggerType
  riskDesc: string
  originAmount: number
  adjustedAmount: number | null
  status: AuditTicketStatus
  rerunCount: number
  reviewReasons: string[] | null
  auditorId: number | null
  auditComment: string | null
  createdAt: string
}

/** 审批留痕（append-only，审计溯源；P3b 起携带变更前后数据快照） */
export interface AuditRecordVO {
  id: number
  action: AuditAction
  beforeAmount: number | null
  afterAmount: number | null
  comment: string | null
  operatorId: number | null
  operatorName: string | null
  operatorRoles: string | null
  /** 变更前数据快照（首条 SUBMIT 为 null；无预签名 URL/OSS 路径） */
  beforeData: Record<string, unknown> | null
  /** 变更后数据快照（每次动作落一条，审批时点数据现场） */
  afterData: Record<string, unknown> | null
  createdAt: string
}

/** 工单详情（工单 + 关联报销单详情 + 留痕时间线） */
export interface AuditTicketDetailVO {
  ticket: AuditTicketVO
  reimbursement: ReimbursementDetailVO | null
  records: AuditRecordVO[]
}

/** 审批动作请求（approve/reject/terminate/withdraw-agree|refuse 共用；仅意见） */
export interface AuditActionRequest {
  comment?: string
}

/** 修改明细重跑请求（提交人 resubmit；标题/部门服务端沿用库内旧值，请求体不携带） */
export interface ReimbursementResubmitRequest {
  expenseType: ExpenseType
  claimDate: string
  remark?: string
  items: ReimbursementItemVO[]
  fileRecordIds: number[]
}

// ===================== P3.5 系统管理 =====================

/** 部门树节点（sys_dept；报销单创建页选择器 / 用户管理） */
export interface DeptVO {
  id: number
  parentId: number
  deptName: string
  status: number
  children: DeptVO[]
}

/** 权限目录项（sys_permission；角色分配界面勾选，按 groupName 分区） */
export interface PermissionVO {
  id: number
  permCode: string
  permName: string
  permType: 'MENU' | 'API'
  groupName: string
}

/** 角色（管理页列表） */
export interface RoleVO {
  id: number
  tenantId: number
  roleCode: string
  roleName: string
  createdAt: string | null
}

/** 系统管理·用户（后端 /api/v1/users 列表项；区别于登录 UserVO） */
export interface SystemUserVO {
  id: number
  tenantId: number
  username: string
  realName: string | null
  phone: string | null
  deptId: number | null
  deptName: string | null
  status: number
  createdAt: string | null
}

/** 系统管理·用户详情（含角色） */
export interface SystemUserDetailVO extends SystemUserVO {
  roles: RoleVO[]
}

/** 新增用户请求（dept_id 可空） */
export interface UserCreateRequest {
  username: string
  password: string
  realName?: string
  phone?: string
  deptId?: number | null
  status?: number
  roleIds?: number[]
}

/** 更新用户请求（字段空则不修改；deptId=0 解绑部门） */
export interface UserUpdateRequest {
  realName?: string
  phone?: string
  deptId?: number | null
  status?: number
  password?: string
}

/** 用户角色分配（替换式） */
export interface UserRoleAssignRequest {
  roleIds: number[]
}

/** 角色新增 */
export interface RoleCreateRequest {
  roleCode: string
  roleName: string
}

/** 角色编辑 */
export interface RoleUpdateRequest {
  roleName?: string
}

/** 角色权限分配（替换式，permIds=[] 清空） */
export interface RolePermAssignRequest {
  permIds: number[]
}

/** 部门新增 */
export interface DeptCreateRequest {
  deptName: string
  parentId?: number
}

/** 部门编辑（字段空则不修改；parentId=0 移到根） */
export interface DeptUpdateRequest {
  deptName?: string
  parentId?: number
  status?: number
}

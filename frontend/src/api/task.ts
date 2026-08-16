import { http } from './request'
import type { PageResult, TaskStepVO, TaskVO } from '@/types'

export interface SubmitTaskRequest {
  title: string
  /** 业务类型：REIMBURSEMENT / GENERIC（缺省 GENERIC）；报销单走 /reimbursements 自动标记 REIMBURSEMENT */
  taskType?: string
  inputParams: Record<string, unknown>
}

export interface TaskPageQuery {
  pageNum?: number
  pageSize?: number
  status?: string
}

/** 提交任务（落库 PENDING，异步经 MQ 触发编排） */
export function submitTask(data: SubmitTaskRequest): Promise<TaskVO> {
  return http.post<TaskVO>('/tasks', data)
}

/** 分页查询任务 */
export function getTaskPage(params: TaskPageQuery): Promise<PageResult<TaskVO>> {
  return http.get<PageResult<TaskVO>>('/tasks', { params })
}

/** 任务详情 */
export function getTaskDetail(id: number | string): Promise<TaskVO> {
  return http.get<TaskVO>(`/tasks/${id}`)
}

/** 步骤明细 */
export function getTaskSteps(id: number | string): Promise<TaskStepVO[]> {
  return http.get<TaskStepVO[]>(`/tasks/${id}/steps`)
}

/** 断点续跑（仅 PENDING/RUNNING 有效；SUCCESS/FAILED 后端返回 400「任务已终结，无需续跑」） */
export function resumeTask(id: number | string): Promise<null> {
  return http.post<null>(`/tasks/${id}/resume`)
}

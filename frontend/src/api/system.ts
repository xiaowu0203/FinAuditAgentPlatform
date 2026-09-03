import { http } from './request'
import type {
  DeptCreateRequest,
  DeptUpdateRequest,
  DeptVO,
  PageResult,
  PermissionVO,
  RoleCreateRequest,
  RolePermAssignRequest,
  RoleUpdateRequest,
  RoleVO,
  SystemUserDetailVO,
  SystemUserVO,
  UserCreateRequest,
  UserRoleAssignRequest,
  UserUpdateRequest,
} from '@/types'

/**
 * 系统管理接口（P3.5 R3）：用户/角色/权限/部门。
 * 走网关 9080，权限由后端 @RequirePerm 校验（403 fail-closed）；
 * 部门树 GET 登录即可（报销选择器公用），写操作挂 dept:* 码。
 */

// ===================== 用户 =====================

export function getUsers(params: { pageNum?: number; pageSize?: number; keyword?: string }): Promise<PageResult<SystemUserVO>> {
  return http.get<PageResult<SystemUserVO>>('/users', { params })
}

export function getUserDetail(id: number): Promise<SystemUserDetailVO> {
  return http.get<SystemUserDetailVO>(`/users/${id}`)
}

export function createUser(data: UserCreateRequest): Promise<SystemUserVO> {
  return http.post<SystemUserVO>('/users', data)
}

export function updateUser(id: number, data: UserUpdateRequest): Promise<SystemUserVO> {
  return http.put<SystemUserVO>(`/users/${id}`, data)
}

export function assignUserRoles(id: number, data: UserRoleAssignRequest): Promise<null> {
  return http.put<null>(`/users/${id}/roles`, data)
}

export function deleteUser(id: number): Promise<null> {
  return http.delete<null>(`/users/${id}`)
}

// ===================== 角色 =====================

export function getRoles(): Promise<RoleVO[]> {
  return http.get<RoleVO[]>('/roles')
}

export function createRole(data: RoleCreateRequest): Promise<RoleVO> {
  return http.post<RoleVO>('/roles', data)
}

export function updateRole(id: number, data: RoleUpdateRequest): Promise<RoleVO> {
  return http.put<RoleVO>(`/roles/${id}`, data)
}

export function deleteRole(id: number): Promise<null> {
  return http.delete<null>(`/roles/${id}`)
}

export function getRolePermIds(id: number): Promise<number[]> {
  return http.get<number[]>(`/roles/${id}/permissions`)
}

/** 替换式分配角色权限（permIds=[] 清空） */
export function assignRolePerms(id: number, data: RolePermAssignRequest): Promise<null> {
  return http.put<null>(`/roles/${id}/permissions`, data)
}

// ===================== 权限目录 =====================

export function getPermissions(): Promise<PermissionVO[]> {
  return http.get<PermissionVO[]>('/permissions')
}

// ===================== 部门 =====================

export function getDeptTree(): Promise<DeptVO[]> {
  return http.get<DeptVO[]>('/depts')
}

export function createDept(data: DeptCreateRequest): Promise<DeptVO> {
  return http.post<DeptVO>('/depts', data)
}

export function updateDept(id: number, data: DeptUpdateRequest): Promise<DeptVO> {
  return http.put<DeptVO>(`/depts/${id}`, data)
}

export function deleteDept(id: number): Promise<null> {
  return http.delete<null>(`/depts/${id}`)
}
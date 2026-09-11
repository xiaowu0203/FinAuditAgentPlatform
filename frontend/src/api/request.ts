import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import { pinia } from '@/stores/pinia'
import { fetchCurrentUserRaw } from './authRaw'
import type { AxiosResponse } from 'axios'
import type { R } from '@/types'

/**
 * axios 实例：
 * - baseURL=/api/v1，Vite dev 代理转发到网关 9080（网关不剥前缀，后端映射 /api/v1/**）
 * - 请求：自动注入 Authorization: Bearer <token>
 * - 响应：业务错误（HTTP 2xx + code!=0）统一 ElMessage 提示；HTTP 401（登出/被踢/过期）清登录态跳登录
 */
/** API 基础路径：网关不剥前缀，后端 Controller 映射 /api/v1/** */
export const API_BASE_URL = '/api/v1'

const instance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
})

/**
 * 在拦截器里安全取 auth store（P3.8 / R0-12）。
 *
 * 拦截器可能在两类"Pinia 未必就绪"的场景下执行：① 应用挂载前的早期请求；
 * ② 浏览器扩展（自身也跑 Vue）或 HMR 异常扰乱模块时序时。直接 `useAuthStore()` 会抛
 * `getActivePinia() was called but there was no active Pinia` 并中断请求链路。
 * 故显式传入实例，并在极端情况下回退到 localStorage —— 与守卫保持同一份真相，绝不抛错。
 */
const tokenFromStorage = (): string => localStorage.getItem('finaudit_token') || ''

function currentToken(): string {
  try {
    return useAuthStore(pinia).token || tokenFromStorage()
  } catch {
    return tokenFromStorage()
  }
}

function clearAuthSafely(): void {
  try {
    useAuthStore(pinia).clear()
  } catch {
    localStorage.removeItem('finaudit_token')
    localStorage.removeItem('finaudit_user')
  }
}

instance.interceptors.request.use((config) => {
  const token = currentToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (response) => {
    const body = response.data as R | undefined
    // 后端业务错误返回 HTTP 2xx + code!=0（见 common-code GlobalExceptionHandler）
    if (body && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    // 解包 R.data 返回业务数据；http.* 封装再断言为具体类型
    return (body?.data ?? null) as unknown as AxiosResponse
  },
  (error) => {
    const status: number | undefined = error.response?.status
    if (status === 401) {
      clearAuthSafely()
      ElMessage.error('登录已失效，请重新登录')
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    } else if (status === 403) {
      // P3.5 承诺：权限被回收后菜单/按钮即时收敛。
      // ⚠️ P3.8 修复：原实现用 instance.get('/auth/me')，但本拦截器已把 R<T> 解包为 data，
      //      再取 resp.data / body.code 恒为 undefined → refreshPerms 永不执行（死代码）。
      //      现改用绕过拦截器的裸请求（authRaw.fetchCurrentUserRaw），在拦截器内部是唯一安全做法。
      const token = currentToken()
      if (token) {
        fetchCurrentUserRaw(token)
          .then((user) => {
            if (!user) return
            try {
              useAuthStore(pinia).refreshPerms(user)
            } catch {
              localStorage.setItem('finaudit_user', JSON.stringify(user))
            }
          })
          .catch(() => {
            /* 刷新失败不影响本次错误提示 */
          })
      }
      const msg = error.response?.data?.message || '无权限访问'
      ElMessage.error(msg)
    } else {
      const msg = error.response?.data?.message || error.message || '网络异常，请稍后再试'
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  },
)

/** 类型化请求封装：响应拦截器已解包 R.data，直接返回业务数据 */
export const http = {
  get<T>(url: string, config?: object): Promise<T> {
    return instance.get(url, config) as Promise<T>
  },
  post<T>(url: string, data?: unknown, config?: object): Promise<T> {
    return instance.post(url, data, config) as Promise<T>
  },
  put<T>(url: string, data?: unknown, config?: object): Promise<T> {
    return instance.put(url, data, config) as Promise<T>
  },
  delete<T>(url: string, config?: object): Promise<T> {
    return instance.delete(url, config) as Promise<T>
  },
}

export default instance

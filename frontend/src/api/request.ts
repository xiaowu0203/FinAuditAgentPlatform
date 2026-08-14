import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'
import type { AxiosResponse } from 'axios'
import type { R } from '@/types'

/**
 * axios 实例：
 * - baseURL=/api/v1，Vite dev 代理转发到网关 9080（网关不剥前缀，后端映射 /api/v1/**）
 * - 请求：自动注入 Authorization: Bearer <token>
 * - 响应：业务错误（HTTP 2xx + code!=0）统一 ElMessage 提示；HTTP 401（登出/被踢/过期）清登录态跳登录
 */
const instance = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

instance.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
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
      const auth = useAuthStore()
      auth.clear()
      ElMessage.error('登录已失效，请重新登录')
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
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

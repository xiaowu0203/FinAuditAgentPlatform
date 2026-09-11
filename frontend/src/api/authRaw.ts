import axios from 'axios'
import { API_BASE_URL } from './request'
import type { R, UserVO } from '@/types'

/**
 * 绕过拦截器的裸请求：拉取当前用户信息（**仅供响应拦截器的 403 刷新使用**）。
 *
 * <p><b>为什么不放在 `api/auth.ts`</b>：`auth.ts` 依赖 `request.ts`；若把本函数放在 `auth.ts` 再由
 * `request.ts` 反向导入，会形成 `request.ts → auth.ts → request.ts` 的模块循环依赖。
 * 独立成文件后依赖方向单向：本文件 → request.ts（仅取常量），业务侧 401/403 逻辑无需感知。</p>
 *
 * <p><b>为什么要绕过拦截器</b>：带拦截器的实例已把 {@code R<T>} 解包为 {@code data}，
 * 调用方拿不到 {@code code} 字段；而 403 刷新恰好发生在响应拦截器内部，复用带拦截器的实例
 * 既取不到 {@code R} 结构、也可能递归触发错误处理。业务层请统一使用 `api/auth.ts` 的 {@link getMe}。</p>
 */
export async function fetchCurrentUserRaw(token: string): Promise<UserVO | null> {
  if (!token) {
    return null
  }
  const resp = await axios.get<R<UserVO>>('/auth/me', {
    baseURL: API_BASE_URL,
    headers: { Authorization: `Bearer ${token}` },
  })
  const body = resp.data
  if (!body || body.code !== 0) {
    return null
  }
  return body.data ?? null
}

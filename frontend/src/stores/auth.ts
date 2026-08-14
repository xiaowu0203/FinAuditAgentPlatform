import { defineStore } from 'pinia'
import type { UserVO } from '@/types'

const TOKEN_KEY = 'finaudit_token'
const USER_KEY = 'finaudit_user'

interface AuthState {
  token: string
  user: UserVO | null
}

/** 从 localStorage 安全读取缓存的用户信息（损坏时返回 null） */
function readStoredUser(): UserVO | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as UserVO) : null
  } catch {
    return null
  }
}

/**
 * 登录态：token + 用户信息，持久化到 localStorage。
 * 登出 / 被踢（网关 401）时由 clear() 清空并跳登录页。
 */
export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: readStoredUser(),
  }),
  actions: {
    setLogin(token: string, user: UserVO) {
      this.token = token
      this.user = user
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify(user))
    },
    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    },
  },
})

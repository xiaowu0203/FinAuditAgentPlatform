import { createPinia } from 'pinia'

/**
 * 全局单例 Pinia 实例（P3.8 / R0-12）。
 *
 * 为什么要单独抽一个模块：
 * 1. `main.ts` 需要在 `app.use(router)` **之前** `setActivePinia`，而 `router/index.ts` 的守卫又要在
 *    setup 之外使用 store——两边必须拿到**同一个**实例。
 * 2. 若放到 `stores/auth.ts` 里导出，则形成 `router → stores/auth → (pinia)` 的耦合；
 *    单独成文件后依赖方向清晰：`stores/pinia.ts → pinia`，被 main 与 router 共同引用，无循环。
 *
 * 守卫里用 `useAuthStore(pinia)` 显式传实例，就**不依赖**「全局 activePinia 是否已设置」，
 * 从根上避免 `getActivePinia() was called but there was no active Pinia` 造成的路由初始化失败。
 */
export const pinia = createPinia()

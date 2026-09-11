import { createApp } from 'vue'
import { setActivePinia } from 'pinia'

// Element Plus 按需引入（vite 插件装配模板组件）；此处只补三类全局资源：
// ① 深色变量基底  ② 函数式组件样式（不经过模板解析器，须手动引入）
// ③ 全局设计令牌与基座样式
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/notification/style/css'
import 'element-plus/es/components/loading/style/css'
import './styles/tokens.css'
import './styles/base.css'

import App from './App.vue'
import router from './router'
import { pinia } from './stores/pinia'
import perm from './directives/perm'

const app = createApp(App)

/**
 * ⚠️ 必须先激活 Pinia，再 use(router)。
 *
 * 原因（P3.8 / R0-12）：`app.use(router)` 会**立即触发首次导航**，而 `router.beforeEach` 第一行就要取
 * auth store。虽然 `pinia.install()` 内部会 `setActivePinia(pinia)`，但只要初始导航在该赋值
 * **之前/竞态**执行（实测叠加浏览器扩展注入、HMR WebSocket 反复重连时会命中），守卫就会抛：
 *   Error: [🍍]: "getActivePinia()" was called but there was no active Pinia.
 *   at useStore (pinia) ← at router/index.ts 的 beforeEach
 * 后果是**路由初始化失败**：页面停在原地/白屏，既不渲染也不跳登录
 * （并连带出现 `injection "Symbol(router view location)" not found`、`Cannot read properties of undefined`）。
 *
 * 显式 `setActivePinia` 是 Pinia 官方对「setup 之外使用 store」的推荐做法，消除该时序依赖。
 * 同时 router/index.ts 的守卫改为 `useAuthStore(pinia)`（显式传入实例），双重保险、彻底不依赖全局态。
 */
setActivePinia(pinia)

app.use(pinia)
app.use(router)
// v-perm 按钮级权限动态渲染（P3.5；无权限元素不渲染，updated 钩子响应权限刷新）
app.directive('perm', perm)
// locale 由 App.vue 的 <el-config-provider> 提供（按需引入模式下不再全量 app.use(ElementPlus)）

app.mount('#app')

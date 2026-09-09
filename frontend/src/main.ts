import { createApp } from 'vue'
import { createPinia } from 'pinia'

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
import perm from './directives/perm'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// v-perm 按钮级权限动态渲染（P3.5；无权限元素不渲染，updated 钩子响应权限刷新）
app.directive('perm', perm)
// locale 由 App.vue 的 <el-config-provider> 提供（按需引入模式下不再全量 app.use(ElementPlus)）

app.mount('#app')

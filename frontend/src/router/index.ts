import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/login/index.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/DefaultLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/dashboard/index.vue'),
          meta: { title: '任务工作台' },
        },
        {
          path: 'tasks',
          name: 'TaskList',
          component: () => import('@/views/task/list.vue'),
          meta: { title: '任务列表' },
        },
        {
          path: 'tasks/:id',
          name: 'TaskDetail',
          component: () => import('@/views/task/detail.vue'),
          meta: { title: '任务详情', parentPath: '/tasks', parentTitle: '任务列表' },
        },
        {
          path: 'reimbursements',
          name: 'ReimbursementList',
          component: () => import('@/views/reimbursement/list.vue'),
          meta: { title: '我的报销单' },
        },
        {
          path: 'reimbursements/create',
          name: 'ReimbursementCreate',
          component: () => import('@/views/reimbursement/create.vue'),
          meta: { title: '提交报销单', parentPath: '/reimbursements', parentTitle: '我的报销单' },
        },
        {
          path: 'reimbursements/:id',
          name: 'ReimbursementDetail',
          component: () => import('@/views/reimbursement/detail.vue'),
          meta: { title: '报销单详情', parentPath: '/reimbursements', parentTitle: '我的报销单' },
        },
        {
          path: 'reimbursements/:id/edit',
          name: 'ReimbursementEdit',
          component: () => import('@/views/reimbursement/edit.vue'),
          meta: { title: '修改明细并重跑', parentPath: '/reimbursements', parentTitle: '我的报销单' },
        },
        {
          path: 'rules',
          name: 'RuleConfig',
          component: () => import('@/views/rule/list.vue'),
          meta: { title: '规则配置', perm: 'rule:manage' },
        },
        {
          path: 'system/users',
          name: 'UserManage',
          component: () => import('@/views/system/user.vue'),
          meta: { title: '用户管理', perm: 'user:list' },
        },
        {
          path: 'system/roles',
          name: 'RoleManage',
          component: () => import('@/views/system/role.vue'),
          meta: { title: '角色管理', perm: 'role:list' },
        },
        {
          path: 'system/depts',
          name: 'DeptManage',
          component: () => import('@/views/system/dept.vue'),
          meta: { title: '部门管理', perm: 'dept:manage' },
        },
        {
          path: 'audits',
          name: 'AuditList',
          component: () => import('@/views/audit/list.vue'),
          // 任意已登录用户可访问：后端按 createdBy 过滤本人数据；财务角色另经菜单/操作按钮管控
          meta: { title: '审批工单' },
        },
        {
          path: 'audits/:id',
          name: 'AuditDetail',
          component: () => import('@/views/audit/detail.vue'),
          meta: { title: '审批工单详情', parentPath: '/audits', parentTitle: '审批工单' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
})

/**
 * 登录守卫：未登录访问受保护页 → /login（带 redirect）；已登录访问 /login → /dashboard。
 *
 * <b>为什么不在这里取 Pinia store（P3.8 / R0-12）</b>：
 * `app.use(router)` 会**立即触发首次导航**（`vue-router install()` → `push()` →
 * `runWithContext()` → 本守卫）。此阶段只要时序稍偏——实测在装有浏览器扩展（扩展自身也跑 Vue，
 * 会干扰模块执行/注入上下文）或 HMR 反复重连时即命中——`getActivePinia()` 就会返回 undefined：
 * ```
 * Error: [🍍]: "getActivePinia()" was called but there was no active Pinia.
 * [Vue Router warn]: Unexpected error when starting the router
 * ```
 * 后果是**路由初始化整体失败**：页面既不渲染也不跳登录（并连带
 * `injection "Symbol(router view location)" not found`、`Cannot read properties of undefined`）。
 *
 * 因此守卫改为**只读 localStorage**（token 本就持久化在那里，是唯一真相），完全不碰 store：
 * 既避开 Pinia 时序，也顺带消除 `router → stores/auth → api/request → router` 的循环依赖。
 * 权限码 `meta.perm` 取自同一个持久化 user 对象；解析失败按"无权限"处理（fail-closed，交后端兜底）。
 */
const TOKEN_KEY = 'finaudit_token'
const USER_KEY = 'finaudit_user'

/** 已过期（或无 exp 之外可判定）的本地 JWT 视为未登录，避免先闪一下受保护页再被 401 弹回。 */
function isTokenUsable(raw: string | null): boolean {
  if (!raw) return false
  const payload = raw.split('.')[1]
  if (!payload) return true // 结构异常，交给后端 401 兜底，不误拦
  try {
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
    if (!json.exp) return true
    return json.exp * 1000 > Date.now()
  } catch {
    return true // 解析失败不误拦，交后端判定
  }
}

/** 读取本地持久化的权限码（用于 meta.perm 路由守卫），任何异常都按空集合处理。 */
function localPerms(): string[] {
  try {
    const user = JSON.parse(localStorage.getItem(USER_KEY) || 'null')
    return Array.isArray(user?.perms) ? user.perms : []
  } catch {
    return []
  }
}

router.beforeEach((to) => {
  const token = localStorage.getItem(TOKEN_KEY)
  const loggedIn = isTokenUsable(token)
  if (to.path !== '/login' && !loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && loggedIn) {
    return { path: '/dashboard' }
  }
  // 数据可见性由后端承担：审批工单按 createdBy 过滤本人、财务可见全部；
  // 审批动作按钮在详情页按 audit:approve（v-perm）控制展示
  // 权限受限路由（meta.perm）：无权限直达时拦回工作台（后端 @RequirePerm 403 fail-closed 兜底）
  const requiredPerm = to.meta.perm as string | string[] | undefined
  if (requiredPerm) {
    const codes = Array.isArray(requiredPerm) ? requiredPerm : [requiredPerm]
    const held = localPerms()
    if (!codes.some((c) => held.includes(c))) {
      ElMessage?.warning?.('无权限访问该页面')
      return { path: '/dashboard' }
    }
  }
  const title = to.meta.title
  document.title = title ? `${title} · FinAudit 财务智能审核` : 'FinAudit 财务智能审核'
})

export default router

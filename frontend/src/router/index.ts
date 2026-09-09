import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

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

// 登录守卫：未登录访问受保护页 → /login（带 redirect）；已登录访问 /login → /dashboard
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.path !== '/login' && !auth.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && auth.token) {
    return { path: '/dashboard' }
  }
  // 数据可见性由后端承担：审批工单按 createdBy 过滤本人、财务可见全部；
  // 审批动作按钮在详情页按 audit:approve（v-perm）控制展示
  // 权限受限路由（meta.perm）：无权限直达时拦回工作台（后端 @RequirePerm 403 fail-closed 兜底）
  const requiredPerm = to.meta.perm as string | string[] | undefined
  if (requiredPerm) {
    const codes = Array.isArray(requiredPerm) ? requiredPerm : [requiredPerm]
    if (!auth.hasAnyPerm(codes)) {
      ElMessage?.warning?.('无权限访问该页面')
      return { path: '/dashboard' }
    }
  }
  const title = to.meta.title
  document.title = title ? `${title} · FinAudit 财务智能审核` : 'FinAudit 财务智能审核'
})

export default router

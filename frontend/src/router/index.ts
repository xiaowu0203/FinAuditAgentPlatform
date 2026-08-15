import { createRouter, createWebHistory } from 'vue-router'
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
          meta: { title: '任务详情' },
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
          meta: { title: '提交报销单' },
        },
        {
          path: 'reimbursements/:id',
          name: 'ReimbursementDetail',
          component: () => import('@/views/reimbursement/detail.vue'),
          meta: { title: '报销单详情' },
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
  const title = to.meta.title
  document.title = title ? `${title} · FinAudit 财务智能审核` : 'FinAudit 财务智能审核'
})

export default router

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Moon, Sunny } from '@element-plus/icons-vue'
import { logout as apiLogout } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => {
  if (route.path.startsWith('/tasks')) return '/tasks'
  if (route.path.startsWith('/reimbursements')) return '/reimbursements'
  if (route.path.startsWith('/rules')) return '/rules'
  if (route.path.startsWith('/audits')) return '/audits'
  if (route.path.startsWith('/system')) return '/system'
  return '/dashboard'
})
const pageTitle = computed(() => (route.meta.title as string | undefined) || '')
const displayName = computed(() => auth.user?.realName || auth.user?.username || '未登录')
/** 系统管理菜单组可见性：任一管理页权限即可（权限码 P3.5 取代角色字符串） */
const canManageSystem = computed(() => auth.hasAnyPerm(['user:list', 'role:list', 'dept:manage']))

/** 面包屑：明细/表单页经 meta.parent* 挂到父列表，其余只有当前页 */
const crumbs = computed(() => {
  const items: Array<{ title: string; path?: string }> = []
  const parentPath = route.meta.parentPath as string | undefined
  const parentTitle = route.meta.parentTitle as string | undefined
  if (parentPath && parentTitle) items.push({ title: parentTitle, path: parentPath })
  if (pageTitle.value) items.push({ title: pageTitle.value })
  return items
})

/* ---- 深色模式：首次访问跟随系统，此后记住用户选择 ---- */
type Theme = 'light' | 'dark'
const stored = localStorage.getItem('finaudit-theme')
const theme = ref<Theme>(
  stored === 'dark' || stored === 'light'
    ? stored
    : window.matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light',
)
function applyTheme(t: Theme) {
  document.documentElement.classList.toggle('dark', t === 'dark')
}
applyTheme(theme.value)
watch(theme, (t) => {
  applyTheme(t)
  localStorage.setItem('finaudit-theme', t)
})

async function handleCommand(command: string) {
  if (command !== 'logout') return
  try {
    // 通知网关写 JWT 黑名单；失败不阻塞本地退出
    await apiLogout()
  } catch {
    /* 拦截器已提示，忽略 */
  }
  auth.clear()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<template>
  <div class="shell">
    <aside class="aside">
      <div class="brand">
        <span class="brand-seal display">审</span>
        <span class="brand-name">
          <strong class="display">FinAudit</strong>
          <small>财务费用智能审核</small>
        </span>
      </div>

      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/dashboard">
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <span>审核任务</span>
        </el-menu-item>
        <el-menu-item index="/reimbursements">
          <span>报销单</span>
        </el-menu-item>
        <el-menu-item index="/audits">
          <span>审批工单</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPerm('rule:manage')" index="/rules">
          <span>规则配置</span>
        </el-menu-item>
        <!-- 系统管理（P3.5）：按管理页权限码显隐，子项按各自权限 -->
        <el-sub-menu v-if="canManageSystem" index="/system">
          <template #title>
            <span>系统管理</span>
          </template>
          <el-menu-item v-if="auth.hasPerm('user:list')" index="/system/users">用户</el-menu-item>
          <el-menu-item v-if="auth.hasPerm('role:list')" index="/system/roles">角色</el-menu-item>
          <el-menu-item v-if="auth.hasPerm('dept:manage')" index="/system/depts">部门</el-menu-item>
        </el-sub-menu>
      </el-menu>

      <div class="aside-foot">
        <span class="foot-line" />
        <span class="foot-text">账簿 · 印章 · 留痕</span>
      </div>
    </aside>

    <div class="frame">
      <header class="topbar">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item
            v-for="(c, i) in crumbs"
            :key="i"
            :to="c.path ? { path: c.path } : undefined"
          >
            {{ c.title }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div class="topbar-right">
          <button
            class="theme-toggle"
            type="button"
            :title="theme === 'dark' ? '切换为浅色' : '切换为深色'"
            @click="theme = theme === 'dark' ? 'light' : 'dark'"
          >
            <el-icon><Sunny v-if="theme === 'dark'" /><Moon v-else /></el-icon>
          </button>

          <el-dropdown trigger="click" @command="handleCommand">
            <span class="who">
              <span class="who-seal display">{{ (displayName || 'U').slice(0, 1) }}</span>
              <span class="who-name">{{ displayName }}</span>
              <span class="who-caret">▾</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100%;
}

/* ---- 墨青侧栏 ---- */

.aside {
  position: relative;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: var(--aside-width);
  background: var(--aside-bg);
  border-right: 1px solid var(--aside-line);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 18px 18px;
}

/* 印章式品牌标：方章「审」 */
.brand-seal {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1.5px solid var(--ledger);
  border-radius: 6px;
  color: var(--ledger);
  font-size: 18px;
  line-height: 1;
  transform: rotate(-4deg);
}

.brand-name {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-name strong {
  color: var(--aside-text-strong);
  font-size: 17px;
}

.brand-name small {
  color: var(--aside-text);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.menu {
  flex: 1;
  margin-top: 6px;
  padding: 0 10px;
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--aside-text);
  --el-menu-active-color: var(--aside-text-strong);
  --el-menu-hover-bg-color: rgba(244, 246, 243, 0.06);
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  height: 42px;
  margin-bottom: 2px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  transition: background-color 0.15s ease;
}

/* 选中项：左缘账簿绿细条 + 微衬底（印章压痕感），不用渐变与阴影 */
.menu :deep(.el-menu-item.is-active) {
  position: relative;
  background: var(--aside-active-bg);
  color: var(--aside-text-strong);
}

.menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 2px;
  background: var(--ledger);
}

.menu :deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  padding-left: 40px !important;
}

.aside-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
}

.foot-line {
  flex: 1;
  height: 1px;
  background: var(--aside-line);
}

.foot-text {
  color: var(--aside-text);
  font-size: 11px;
  letter-spacing: 0.08em;
}

/* ---- 右侧框架 ---- */

.frame {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 52px;
  padding: 0 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.theme-toggle {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
}

.theme-toggle:hover {
  color: var(--ledger);
  border-color: var(--ledger);
}

.who {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--ink);
  outline: none;
}

.who-seal {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--ledger);
  border-radius: var(--radius-sm);
  color: var(--ledger);
  font-size: 14px;
  transform: rotate(-4deg);
}

.who-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.who-caret {
  color: var(--ink-3);
  font-size: 10px;
}

/* ---- 内容区：纸面底 + 宽度约束 ---- */

.content {
  flex: 1;
  padding: 22px 24px 40px;
  overflow-x: hidden;
}

.content > :deep(*) {
  max-width: var(--shell-max);
  margin-left: auto;
  margin-right: auto;
}

@media (max-width: 992px) {
  .aside {
    width: 190px;
  }

  .content {
    padding: 16px 14px 32px;
  }
}
</style>

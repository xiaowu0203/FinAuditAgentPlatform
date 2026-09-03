<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
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
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <div class="brand-mark">FA</div>
        <div>
          <div class="brand-title">FinAudit</div>
          <div class="brand-subtitle">财务智能审核平台</div>
        </div>
      </div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <span>任务工作台</span>
        </el-menu-item>
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          <span>任务列表</span>
        </el-menu-item>
        <el-menu-item index="/reimbursements">
          <el-icon><Tickets /></el-icon>
          <span>报销单</span>
        </el-menu-item>
        <el-menu-item v-if="auth.hasPerm('rule:manage')" index="/rules">
          <el-icon><Setting /></el-icon>
          <span>规则配置</span>
        </el-menu-item>
        <!-- 审批工单：所有登录用户可见——申请人只读查看本人工单（后端按 createdBy 过滤），财务角色执行审批操作 -->
        <el-menu-item index="/audits">
          <el-icon><Checked /></el-icon>
          <span>审批工单</span>
        </el-menu-item>
        <!-- 系统管理（P3.5）：按管理页权限码显隐，子项按各自权限 -->
        <el-sub-menu v-if="canManageSystem" index="/system">
          <template #title>
            <el-icon><Menu /></el-icon>
            <span>系统管理</span>
          </template>
          <el-menu-item v-if="auth.hasPerm('user:list')" index="/system/users">用户管理</el-menu-item>
          <el-menu-item v-if="auth.hasPerm('role:list')" index="/system/roles">角色管理</el-menu-item>
          <el-menu-item v-if="auth.hasPerm('dept:manage')" index="/system/depts">部门管理</el-menu-item>
        </el-sub-menu>
      </el-menu>
      <div class="aside-footer">
        <div class="aside-tip">更清晰的任务、报销与规则视图</div>
      </div>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div>
          <div class="page-title">{{ pageTitle }}</div>
          <div class="page-subtitle">统一查看审核任务、报销单和规则配置</div>
        </div>
        <el-dropdown @command="handleCommand">
          <span class="user-info">
            <span class="avatar-badge">{{ (displayName || 'U').slice(0, 1) }}</span>
            <span class="user-meta">
              <strong>{{ displayName }}</strong>
              <small>当前已登录</small>
            </span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 18px 14px 14px;
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  background:
    radial-gradient(circle at top, rgba(96, 165, 250, 0.22), transparent 32%),
    linear-gradient(180deg, #0f172a 0%, #111c35 45%, #0f172a 100%);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px 18px;
  color: #fff;
}

.brand-mark {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 14px;
  background: linear-gradient(135deg, #60a5fa, #4f46e5);
  box-shadow: 0 12px 24px rgba(37, 99, 235, 0.28);
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.brand-title {
  font-size: 18px;
  font-weight: 700;
}

.brand-subtitle {
  margin-top: 2px;
  color: rgba(255, 255, 255, 0.64);
  font-size: 12px;
}

.menu {
  flex: 1;
  border-right: none;
  background: transparent;
  /* 深色侧边栏统一菜单配色（el-sub-menu 标题/展开内联子列表同样吃到） */
  --el-menu-bg-color: transparent;
  --el-menu-text-color: rgba(255, 255, 255, 0.72);
  --el-menu-active-color: #fff;
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.08);
}

.menu :deep(.el-menu-item),
.menu :deep(.el-sub-menu__title) {
  height: 48px;
  margin-bottom: 8px;
  border-radius: 14px;
  transition: all 0.22s ease;
}

.menu :deep(.el-menu-item:hover),
.menu :deep(.el-sub-menu__title:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  transform: translateX(2px);
}

.menu :deep(.el-sub-menu .el-menu) {
  background: transparent;
}

.menu :deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  border-radius: 12px;
  padding-left: 48px !important;
}

.menu :deep(.el-sub-menu .el-menu-item.is-active) {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.9), rgba(79, 70, 229, 0.92));
  color: #fff;
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.22);
}

.menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.9), rgba(79, 70, 229, 0.92));
  color: #fff;
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.22);
}

.menu :deep(.el-menu-item [class*='el-icon']) {
  font-size: 16px;
}

.aside-footer {
  padding: 10px 8px 0;
}

.aside-tip {
  padding: 12px 14px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.04);
  color: rgba(255, 255, 255, 0.62);
  font-size: 12px;
  line-height: 1.6;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 76px;
  margin: 18px 18px 0;
  padding: 0 20px;
  border: 1px solid rgba(255, 255, 255, 0.75);
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.06);
  backdrop-filter: blur(14px);
}

.page-title {
  margin-bottom: 4px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 168px;
  padding: 8px 10px 8px 8px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.05);
  cursor: pointer;
  color: #334155;
  outline: none;
}

.avatar-badge {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 50%;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  text-transform: uppercase;
}

.user-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.user-meta strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 700;
}

.user-meta small {
  color: #64748b;
  font-size: 11px;
}

.main {
  padding: 18px;
  background:
    radial-gradient(circle at right top, rgba(96, 165, 250, 0.15), transparent 26%),
    radial-gradient(circle at left bottom, rgba(79, 70, 229, 0.08), transparent 28%);
}

@media (max-width: 992px) {
  .aside {
    padding: 14px 10px 10px;
  }

  .header {
    margin: 12px 12px 0;
  }

  .main {
    padding: 12px;
  }
}
</style>

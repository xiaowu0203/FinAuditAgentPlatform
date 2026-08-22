<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  const valid = await formRef.value?.validate().then(() => true).catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const data = await login({ username: form.username, password: form.password })
    auth.setLogin(data.token, data.user)
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch {
    // 错误提示已由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-glow glow-left" />
    <div class="login-glow glow-right" />
    <el-card class="login-card" shadow="never">
      <div class="login-side hero-side">
        <div class="hero-badge">FinAudit</div>
        <h1 class="title">财务费用智能审核平台</h1>
        <p class="subtitle">统一处理任务审核、报销提交流程和规则配置，界面更轻、更清晰。</p>
        <div class="hero-points">
          <div class="hero-point">
            <strong>任务驱动</strong>
            <span>提交审核任务后自动跟踪执行状态和步骤结果</span>
          </div>
          <div class="hero-point">
            <strong>报销联动</strong>
            <span>报销单、附件、审核任务三者在同一界面联动查看</span>
          </div>
          <div class="hero-point">
            <strong>规则可视化</strong>
            <span>配置规则后可直接发布生效，不改变既有接口契约</span>
          </div>
        </div>
      </div>

      <div class="login-side form-side">
        <div class="form-head">
          <div class="form-kicker">欢迎回来</div>
          <h2>登录到 FinAudit</h2>
          <p>使用你的账号进入工作台</p>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="Lock" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">登 录</el-button>
          </el-form-item>
        </el-form>

        <div class="account-tip">
          <span>体验账号</span>
          <strong>admin / admin123</strong>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  background:
    radial-gradient(circle at top left, rgba(96, 165, 250, 0.3), transparent 28%),
    radial-gradient(circle at bottom right, rgba(79, 70, 229, 0.32), transparent 30%),
    linear-gradient(135deg, #0f172a 0%, #111827 46%, #172554 100%);
}

.login-glow {
  position: absolute;
  width: 420px;
  height: 420px;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.35;
}

.glow-left {
  top: -120px;
  left: -80px;
  background: #3b82f6;
}

.glow-right {
  right: -120px;
  bottom: -120px;
  background: #6366f1;
}

.login-card {
  position: relative;
  z-index: 1;
  width: min(980px, 100%);
  padding: 0;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.24);
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.12);
  box-shadow: 0 30px 80px rgba(15, 23, 42, 0.34);
  backdrop-filter: blur(20px);
}

.login-card :deep(.el-card__body) {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  padding: 0;
}

.login-side {
  padding: 42px;
}

.hero-side {
  position: relative;
  color: #fff;
  background:
    linear-gradient(160deg, rgba(59, 130, 246, 0.25), rgba(79, 70, 229, 0.08)),
    linear-gradient(180deg, rgba(15, 23, 42, 0.12), rgba(15, 23, 42, 0.04));
}

.hero-side::after {
  content: '';
  position: absolute;
  inset: 22px 22px auto auto;
  width: 180px;
  height: 180px;
  border-radius: 28px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.14), transparent);
  transform: rotate(18deg);
}

.form-side {
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: rgba(255, 255, 255, 0.94);
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  padding: 8px 14px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.title {
  position: relative;
  z-index: 1;
  max-width: 420px;
  margin: 22px 0 12px;
  font-size: 36px;
  line-height: 1.2;
  font-weight: 800;
}

.subtitle {
  position: relative;
  z-index: 1;
  max-width: 460px;
  color: rgba(255, 255, 255, 0.74);
  font-size: 15px;
  line-height: 1.8;
}

.hero-points {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 14px;
  margin-top: 30px;
}

.hero-point {
  padding: 16px 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.06);
}

.hero-point strong {
  display: block;
  margin-bottom: 6px;
  font-size: 15px;
}

.hero-point span {
  color: rgba(255, 255, 255, 0.72);
  font-size: 13px;
  line-height: 1.7;
}

.form-head {
  margin-bottom: 26px;
}

.form-kicker {
  margin-bottom: 8px;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.form-head h2 {
  margin-bottom: 10px;
  color: #0f172a;
  font-size: 30px;
  line-height: 1.15;
}

.form-head p {
  color: #64748b;
  font-size: 14px;
}

.login-btn {
  width: 100%;
  height: 46px;
  border: none;
  background: linear-gradient(135deg, #2563eb, #4f46e5);
  box-shadow: 0 14px 28px rgba(37, 99, 235, 0.24);
}

.account-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  padding: 14px 16px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 16px;
  background: #f8fbff;
  color: #475569;
  font-size: 13px;
}

.account-tip strong {
  color: #0f172a;
  font-size: 14px;
}

@media (max-width: 920px) {
  .login-card :deep(.el-card__body) {
    grid-template-columns: 1fr;
  }

  .hero-side,
  .form-side {
    padding: 30px 24px;
  }

  .title {
    font-size: 28px;
  }
}

@media (max-width: 520px) {
  .login-page {
    padding: 14px;
  }

  .account-tip {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
  }
}
</style>

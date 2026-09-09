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
  <div class="cover">
    <!-- 凭证封面：墨青左栏，账线与印章元素，无渐变无玻璃 -->
    <section class="cover-left">
      <div class="cover-brand">
        <span class="brand-seal display">审</span>
        <span class="brand-word display">FinAudit</span>
      </div>

      <div class="cover-title">
        <h1 class="display">财务费用<br />智能审核平台</h1>
        <p>提交报销单据，Agent 逐级核验，高风险单据转人工审批。</p>
      </div>

      <dl class="cover-points">
        <div class="point">
          <dt>自主核验</dt>
          <dd>OCR 票据识别、预算与规则校验、重复检测依次执行</dd>
        </div>
        <div class="point">
          <dt>终审在人</dt>
          <dd>大额、超标或存疑的单据自动生成审批工单，留痕全程可查</dd>
        </div>
        <div class="point">
          <dt>笔笔有痕</dt>
          <dd>每一步结论都可回溯到工具输出与操作记录</dd>
        </div>
      </dl>
    </section>

    <!-- 表单栏：纸面右栏 -->
    <section class="cover-right">
      <div class="form-wrap">
        <h2 class="display">登 录</h2>
        <p class="form-sub">使用你的账号进入工作台</p>

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="Lock" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" class="submit" :loading="loading" @click="handleLogin">
              进入工作台
            </el-button>
          </el-form-item>
        </el-form>

        <div class="demo">
          <span>体验账号</span>
          <code>admin / admin123</code>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.cover {
  display: grid;
  grid-template-columns: minmax(380px, 46%) 1fr;
  height: 100%;
}

/* ---- 左：凭证封面 ---- */

.cover-left {
  display: flex;
  flex-direction: column;
  padding: 48px 56px;
  background: var(--aside-bg);
  border-right: 1px solid var(--aside-line);
}

/* 账页横线纹理：极淡，替代渐变光斑 */
.cover-left::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image: repeating-linear-gradient(
    to bottom,
    transparent 0,
    transparent 35px,
    rgba(244, 246, 243, 0.045) 35px,
    rgba(244, 246, 243, 0.045) 36px
  );
}

.cover-left {
  position: relative;
}

.cover-brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-seal {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1.5px solid var(--ledger);
  border-radius: 6px;
  color: var(--ledger);
  font-size: 20px;
  transform: rotate(-4deg);
}

.brand-word {
  color: var(--aside-text-strong);
  font-size: 19px;
}

.cover-title {
  position: relative;
  margin-top: 12vh;
}

.cover-title h1 {
  color: var(--aside-text-strong);
  font-size: clamp(30px, 3.4vw, 42px);
  line-height: 1.35;
  letter-spacing: 0.04em;
}

.cover-title p {
  max-width: 380px;
  margin-top: 18px;
  color: var(--aside-text);
  font-size: 14.5px;
  line-height: 1.9;
}

.cover-points {
  position: relative;
  margin-top: auto;
  display: grid;
  gap: 0;
}

/* 分录式条目：上账线分隔 */
.point {
  padding: 14px 0;
  border-top: 1px solid var(--aside-line);
}

.point dt {
  margin-bottom: 4px;
  color: var(--aside-text-strong);
  font-size: 14px;
  font-weight: 600;
}

.point dd {
  color: var(--aside-text);
  font-size: 13px;
  line-height: 1.7;
}

/* ---- 右：表单 ---- */

.cover-right {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.form-wrap {
  width: min(360px, 100%);
}

.form-wrap h2 {
  font-size: 26px;
  letter-spacing: 0.3em;
  color: var(--ink);
}

.form-sub {
  margin: 10px 0 28px;
  color: var(--ink-2);
  font-size: 13.5px;
}

.submit {
  width: 100%;
  height: 44px;
}

.demo {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18px;
  padding: 10px 14px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-sm);
  color: var(--ink-2);
  font-size: 12.5px;
}

.demo code {
  color: var(--ink);
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
}

/* ---- 响应式 ---- */

@media (max-width: 920px) {
  .cover {
    grid-template-columns: 1fr;
  }

  .cover-left {
    display: none;
  }

  .cover-right {
    align-items: flex-start;
    padding-top: 12vh;
  }
}
</style>

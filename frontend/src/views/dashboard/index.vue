<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTaskPage, submitTask } from '@/api/task'
import { TASK_STATUS_MAP, isActive } from '@/utils/task'
import type { TaskStatus, TaskVO } from '@/types'

const router = useRouter()
const submitting = ref(false)
const recentTasks = ref<TaskVO[]>([])

const form = reactive({
  title: '',
  inputParamsText: `{
  "items": [
    { "name": "高铁票", "amount": 553.00, "amountType": "交通费", "quantity": 1, "unitPrice": 553.00, "date": "2026-08-01" },
    { "name": "住宿费", "amount": 458.00, "amountType": "住宿费", "quantity": 1, "unitPrice": 458.00, "date": "2026-08-01" }
  ],
  "claimedTotal": 1011.00
}`,
})

let timer: number | undefined

async function loadRecent() {
  try {
    const page = await getTaskPage({ pageNum: 1, pageSize: 5 })
    recentTasks.value = page.records || []
  } catch {
    // 拦截器已提示
  }
}

/** 存在进行中任务则启动轮询，全部终态后停止 */
function ensurePolling() {
  if (timer) return
  if (recentTasks.value.some((t) => isActive(t.status))) {
    timer = window.setInterval(async () => {
      await loadRecent()
      if (!recentTasks.value.some((t) => isActive(t.status))) {
        window.clearInterval(timer)
        timer = undefined
      }
    }, 2500)
  }
}

async function handleSubmit() {
  if (!form.title.trim()) {
    ElMessage.warning('请输入任务标题')
    return
  }
  let inputParams: Record<string, unknown>
  try {
    inputParams = JSON.parse(form.inputParamsText) as Record<string, unknown>
  } catch {
    ElMessage.error('任务参数不是合法 JSON，请检查格式')
    return
  }
  submitting.value = true
  try {
    const task = await submitTask({ title: form.title.trim(), inputParams })
    ElMessage.success(`任务已提交：${task.taskNo}`)
    form.title = ''
    await loadRecent()
    ensurePolling()
    router.push(`/tasks/${task.id}`)
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.title = ''
  form.inputParamsText = ''
}

function goList() {
  router.push('/tasks')
}

function goDetail(id: number) {
  router.push(`/tasks/${id}`)
}

onMounted(() => {
  loadRecent().then(ensurePolling)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page-shell dashboard">
    <div class="page-header-bar">
      <div>
        <div class="page-title">任务工作台</div>
        <div class="page-subtitle">快速提交审核任务，并实时查看最近任务的执行进度</div>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :md="14">
        <el-card class="page-card">
          <template #header>
            <div class="page-header">
              <div>
                <div class="page-title card-title">提交审核任务</div>
                <div class="page-subtitle">支持直接输入 JSON 参数，提交后自动跳转到任务详情</div>
              </div>
            </div>
          </template>
          <el-form :model="form" label-width="90px">
            <el-form-item label="任务标题" required>
              <el-input v-model="form.title" placeholder="如：差旅费报销审核" maxlength="128" clearable />
            </el-form-item>
            <el-form-item label="任务参数" required>
              <el-input
                v-model="form.inputParamsText"
                type="textarea"
                :rows="10"
                spellcheck="false"
                placeholder='JSON 对象，如 {"items":[{"name":"高铁票","amount":553,"amountType":"交通费","quantity":1,"unitPrice":553,"date":"2026-08-01"}],"claimedTotal":1011}'
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="handleSubmit">提交任务</el-button>
              <el-button @click="resetForm">重置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="10">
        <el-card class="page-card">
          <template #header>
            <div class="recent-header">
              <div>
                <div class="page-title card-title">最近任务</div>
                <div class="page-subtitle">存在进行中任务时会自动刷新状态</div>
              </div>
              <el-link type="primary" @click="goList">全部任务 ›</el-link>
            </div>
          </template>
          <el-table :data="recentTasks" size="small" empty-text="暂无任务">
            <el-table-column prop="taskNo" label="任务编号" min-width="150" show-overflow-tooltip />
            <el-table-column prop="title" label="标题" min-width="100" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="TASK_STATUS_MAP[row.status as TaskStatus].tag" size="small">
                  {{ TASK_STATUS_MAP[row.status as TaskStatus].label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="goDetail(row.id)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard {
  gap: 18px;
}

.recent-header {
  width: 100%;
}

.card-title {
  font-size: 16px;
}
</style>

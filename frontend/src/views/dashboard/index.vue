<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getTaskPage, submitTask } from '@/api/task'
import { getAuditTicketPage } from '@/api/audit'
import { TASK_STATUS_MAP, isActive } from '@/utils/task'
import StatusStamp from '@/components/StatusStamp.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { TaskStatus, TaskVO } from '@/types'

const router = useRouter()
const submitting = ref(false)
const recentTasks = ref<TaskVO[]>([])
const dialogVisible = ref(false)

/** 待办计数：进行中任务 / 待处理任务 / 待审批工单（后端按可见性过滤） */
const stats = reactive({ running: 0, pending: 0, tickets: 0 })

const form = reactive({
  title: '',
  inputParamsText: `{
  "items": [
    { "name": "高铁票", "amount": 553.00, "amountType": "交通费", "quantity": 1, "unitPrice": 553.00, "date": "2026-08-01" }
  ],
  "claimedTotal": 553.00
}`,
})

let timer: number | undefined

async function loadStats() {
  try {
    const [running, pending, tickets] = await Promise.all([
      getTaskPage({ pageNum: 1, pageSize: 1, status: 'RUNNING' }),
      getTaskPage({ pageNum: 1, pageSize: 1, status: 'PENDING' }),
      getAuditTicketPage({ pageNum: 1, pageSize: 1, status: 'PENDING' }),
    ])
    stats.running = running.total || 0
    stats.pending = pending.total || 0
    stats.tickets = tickets.total || 0
  } catch {
    // 拦截器已提示
  }
}

async function loadRecent() {
  try {
    const page = await getTaskPage({ pageNum: 1, pageSize: 8 })
    recentTasks.value = page.records || []
  } catch {
    // 拦截器已提示
  }
}

function refreshAll() {
  return Promise.all([loadStats(), loadRecent()])
}

/** 存在进行中任务则启动轮询，全部终态后停止 */
function ensurePolling() {
  if (timer) return
  if (recentTasks.value.some((t) => isActive(t.status))) {
    timer = window.setInterval(async () => {
      await refreshAll()
      if (!recentTasks.value.some((t) => isActive(t.status))) {
        window.clearInterval(timer)
        timer = undefined
      }
    }, 2500)
  }
}

function openTaskDialog() {
  dialogVisible.value = true
}

/** 通用任务（GENERIC）：直接给 JSON 参数，走任务规划器拆解 */
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
    dialogVisible.value = false
    form.title = ''
    await refreshAll()
    ensurePolling()
    router.push(`/tasks/${task.id}`)
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function goDetail(id: number) {
  router.push(`/tasks/${id}`)
}

onMounted(() => {
  refreshAll().then(ensurePolling)
})
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-head-title">工作台</div>
        <div class="page-head-sub">报销单据走完整表单提交；通用分析任务可直接给参数</div>
      </div>
      <div class="page-head-actions">
        <el-button @click="openTaskDialog">提交通用任务</el-button>
        <el-button type="primary" @click="router.push('/reimbursements/create')">提交报销单</el-button>
      </div>
    </div>

    <!-- 待办三格：账簿分栏，数字衬线放大 -->
    <div class="stats">
      <button class="stat" type="button" @click="router.push('/tasks')">
        <span class="stat-label">进行中任务</span>
        <strong class="stat-num">{{ stats.running }}</strong>
      </button>
      <button class="stat" type="button" @click="router.push('/tasks')">
        <span class="stat-label">待处理任务</span>
        <strong class="stat-num">{{ stats.pending }}</strong>
      </button>
      <button class="stat" type="button" @click="router.push({ path: '/audits', query: { status: 'PENDING' } })">
        <span class="stat-label">待审批工单</span>
        <strong class="stat-num">{{ stats.tickets }}</strong>
      </button>
    </div>

    <!-- 最近流水：账页 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">最近任务</span>
        <el-link type="primary" :underline="false" @click="router.push('/tasks')">全部任务</el-link>
      </div>
      <div class="panel-body panel-body--flush">
        <el-table
          v-loading="false"
          :data="recentTasks"
          class="ledger-table ledger-table--bare"
        >
          <template #empty>
            <EmptyState
              title="还没有审核任务"
              description="提交第一张报销单后，Agent 流水线会在这里留下第一笔记录"
            >
              <el-button type="primary" @click="router.push('/reimbursements/create')">提交报销单</el-button>
            </EmptyState>
          </template>
          <el-table-column prop="taskNo" label="任务编号" min-width="170" show-overflow-tooltip />
          <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusStamp
                :label="TASK_STATUS_MAP[row.status as TaskStatus].label"
                :tone="TASK_STATUS_MAP[row.status as TaskStatus].tone"
              />
            </template>
          </el-table-column>
          <el-table-column label="进度" width="110" align="center">
            <template #default="{ row }">{{ row.finishedSteps }} / {{ row.totalSteps }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="175" />
          <el-table-column label="" width="70" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="goDetail(row.id)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 通用任务提交：JSON 参数为高级入口，收进对话框 -->
    <el-dialog v-model="dialogVisible" title="提交通用任务" width="600" :close-on-click-modal="false">
      <el-form :model="form" label-width="84px">
        <el-form-item label="任务标题" required>
          <el-input v-model="form.title" placeholder="如：会议费合规性分析" maxlength="128" clearable />
        </el-form-item>
        <el-form-item label="任务参数" required>
          <el-input
            v-model="form.inputParamsText"
            type="textarea"
            :rows="10"
            spellcheck="false"
            class="json-input"
          />
        </el-form-item>
      </el-form>
      <div class="dialog-hint">JSON 对象：items 明细数组 + claimedTotal 申报总额，由任务规划器自行拆解步骤</div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">提交任务</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}

.stat {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s ease;
}

.stat:hover {
  border-color: var(--ledger);
}

.stat-label {
  color: var(--ink-2);
  font-size: 13px;
}

.stat-num {
  font-family: var(--font-display);
  font-variant-numeric: tabular-nums;
  font-size: 34px;
  font-weight: 600;
  line-height: 1;
  color: var(--ink);
}

.json-input :deep(textarea) {
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12.5px;
}

.dialog-hint {
  margin: -6px 0 0 84px;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 768px) {
  .stats {
    grid-template-columns: 1fr;
  }

  .dialog-hint {
    margin-left: 0;
  }
}
</style>

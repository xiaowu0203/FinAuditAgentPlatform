<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, Refresh } from '@element-plus/icons-vue'
import { getTaskDetail, getTaskSteps, resumeTask } from '@/api/task'
import { TASK_STATUS_MAP, canResume, isActive, reimbIdOf } from '@/utils/task'
import { useAuthStore } from '@/stores/auth'
import StatusStamp from '@/components/StatusStamp.vue'
import PipelineTimeline from '@/components/PipelineTimeline.vue'
import type { TaskStepVO, TaskVO } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const taskId = Number(route.params.id)

const loading = ref(false)
const stepsLoading = ref(false)
const task = ref<TaskVO | null>(null)
const steps = ref<TaskStepVO[]>([])

let timer: number | undefined

/** 对象 → 格式化 JSON 文本（非 JSON 时原样展示） */
function pretty(obj: unknown): string {
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

async function load(opts?: { silent?: boolean }) {
  // 轮询为后台静默刷新：不触发 loading 遮罩，避免详情页周期性闪动
  if (!opts?.silent) loading.value = true
  try {
    task.value = await getTaskDetail(taskId)
    if (!opts?.silent) stepsLoading.value = true
    try {
      steps.value = (await getTaskSteps(taskId)) || []
    } finally {
      stepsLoading.value = false
    }
    syncPolling()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 任务处于进行中则轮询刷新，终态后停止 */
function syncPolling() {
  const active = task.value ? isActive(task.value.status) : false
  if (active && !timer) {
    timer = window.setInterval(() => {
      // 页面不可见时跳过本轮，回前台后下一轮自然恢复
      if (document.hidden) return
      load({ silent: true })
    }, 5000)
  } else if (!active && timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function handleResume() {
  try {
    await resumeTask(taskId)
    ElMessage.success('已触发续跑')
    load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(() => load())
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div>
    <!-- 页头：任务号即凭证号 -->
    <div class="page-head">
      <div>
        <div class="page-head-title">{{ task?.taskNo || `任务 #${taskId}` }}</div>
        <div class="page-head-sub detail-sub">
          <span v-if="task">{{ task.title }}</span>
          <StatusStamp
            v-if="task"
            :label="TASK_STATUS_MAP[task.status].label"
            :tone="TASK_STATUS_MAP[task.status].tone"
          />
          <span v-if="task" class="muted">进度 {{ task.finishedSteps }} / {{ task.totalSteps }}</span>
        </div>
      </div>
      <div class="page-head-actions">
        <el-button
          v-if="task && reimbIdOf(task) != null"
          @click="router.push(`/reimbursements/${reimbIdOf(task)}`)"
        >
          查看报销单
        </el-button>
        <!-- 任意用户可见（申请人本人工单只读查看）；audit:viewAll 持有者跳转后执行审批 -->
        <el-button
          v-if="task?.status === 'APPROVAL_PENDING'"
          :type="auth.hasPerm('audit:viewAll') ? 'danger' : 'primary'"
          @click="router.push(`/audits?taskId=${task!.id}`)"
        >
          查看审批工单
        </el-button>
        <el-button
          v-if="task && canResume(task.status)"
          v-perm="'task:viewAll'"
          type="warning"
          :icon="Refresh"
          @click="handleResume"
        >
          续跑
        </el-button>
        <el-button :icon="Back" @click="router.back()">返回</el-button>
      </div>
    </div>

    <!-- 流水线：分录式步骤时间线 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">执行流水（{{ steps.length }} 步）</span>
        <span class="faint">进行中任务每 2.5 秒自动刷新</span>
      </div>
      <div v-loading="stepsLoading" class="panel-body">
        <PipelineTimeline v-if="steps.length" :steps="steps" />
        <p v-else class="muted">暂无步骤记录</p>
      </div>
    </div>

    <!-- 单据信息：入参 / 结果 / 错误 -->
    <template v-if="task">
      <div class="panel info-panel">
        <div class="panel-head">
          <span class="panel-title">单据信息</span>
        </div>
        <div class="panel-body">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="创建时间">{{ task.createdAt }}</el-descriptions-item>
            <el-descriptions-item label="业务类型">{{ task.taskType }}</el-descriptions-item>
            <el-descriptions-item label="错误信息" :span="2">
              <span v-if="task.errorMsg" class="detail-error">{{ task.errorMsg }}</span>
              <span v-else class="faint">无</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="io-block">
            <div class="io-label">任务入参</div>
            <pre class="output-block">{{ pretty(task.inputParams) }}</pre>
          </div>

          <div v-if="task.result" class="io-block">
            <div class="io-label">审核结果</div>
            <pre class="output-block">{{ pretty(task.result) }}</pre>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.detail-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.detail-error {
  color: var(--seal);
}

.info-panel {
  margin-top: 18px;
}

.io-block {
  margin-top: 16px;
}

.io-label {
  margin-bottom: 6px;
  color: var(--ink-2);
  font-size: 12.5px;
  font-weight: 600;
}
</style>

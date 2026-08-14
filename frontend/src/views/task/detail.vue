<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, Refresh } from '@element-plus/icons-vue'
import { getTaskDetail, getTaskSteps, resumeTask } from '@/api/task'
import { TASK_STATUS_MAP, isActive } from '@/utils/task'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { TaskStatus, TaskStepVO, TaskVO } from '@/types'

const route = useRoute()
const router = useRouter()
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

/** LLM 步骤输出形如 { content: "Markdown 文本" }，判断为需渲染的 Markdown */
function isMarkdownText(o: unknown): o is { content: string } {
  return (
    typeof o === 'object' &&
    o !== null &&
    'content' in o &&
    typeof (o as { content: unknown }).content === 'string'
  )
}

/** Markdown → 安全 HTML（DOMPurify 清洗防 XSS） */
function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(marked.parse(text) as string)
}

async function load() {
  loading.value = true
  try {
    task.value = await getTaskDetail(taskId)
    stepsLoading.value = true
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
    timer = window.setInterval(() => load(), 2500)
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
  <div class="detail">
    <el-card v-loading="loading" class="mb">
      <template #header>
        <div class="detail-header">
          <span>任务详情：{{ task?.taskNo }}</span>
          <div>
            <el-button
              v-if="task && isActive(task.status)"
              type="warning"
              :icon="Refresh"
              @click="handleResume"
            >
              续跑
            </el-button>
            <el-button :icon="Back" @click="router.back()">返回</el-button>
          </div>
        </div>
      </template>

      <template v-if="task">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="标题">{{ task.title }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="TASK_STATUS_MAP[task.status].tag">{{ TASK_STATUS_MAP[task.status].label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ task.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="进度">{{ task.finishedSteps }} / {{ task.totalSteps }}</el-descriptions-item>
          <el-descriptions-item label="入参" :span="2">
            <pre class="json-block">{{ pretty(task.inputParams) }}</pre>
          </el-descriptions-item>
          <el-descriptions-item v-if="task.errorMsg" label="错误信息" :span="2">
            <span class="error-text">{{ task.errorMsg }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="task.result" label="结果" :span="2">
            <pre class="json-block">{{ pretty(task.result) }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <el-card v-loading="stepsLoading">
      <template #header><span>步骤明细（{{ steps.length }}）</span></template>
      <el-table :data="steps" empty-text="暂无步骤">
        <el-table-column prop="stepNo" label="步骤" width="70" />
        <el-table-column prop="stepName" label="名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="stepType" label="类型" width="90" />
        <el-table-column prop="toolName" label="工具" width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="TASK_STATUS_MAP[row.status as TaskStatus].tag">{{ TASK_STATUS_MAP[row.status as TaskStatus].label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="70" />
        <el-table-column label="输出" min-width="280">
          <template #default="{ row }">
            <div v-if="row.output">
              <div
                v-if="isMarkdownText(row.output)"
                class="markdown-body"
                v-html="renderMarkdown(row.output.content)"
              />
              <pre v-else class="json-block compact">{{ pretty(row.output) }}</pre>
            </div>
            <span v-else-if="row.errorMsg" class="error-text">{{ row.errorMsg }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>

<!-- LLM 输出 Markdown 渲染样式：v-html 内容无 scope 属性，须用非 scoped 样式（.markdown-body 前缀隔离） -->
<style>
.markdown-body {
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-primary);
}
.markdown-body p {
  margin: 4px 0;
}
.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4 {
  margin: 8px 0 4px;
  font-weight: 600;
}
.markdown-body table {
  border-collapse: collapse;
  margin: 6px 0;
  width: 100%;
}
.markdown-body th,
.markdown-body td {
  border: 1px solid var(--el-border-color-lighter);
  padding: 4px 8px;
  text-align: left;
}
.markdown-body th {
  background: var(--el-fill-color-light);
}
.markdown-body ul,
.markdown-body ol {
  margin: 4px 0;
  padding-left: 20px;
}
.markdown-body li {
  margin: 2px 0;
}
.markdown-body code {
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}
.markdown-body pre {
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
}
.markdown-body pre code {
  background: none;
  padding: 0;
}
.markdown-body blockquote {
  border-left: 3px solid var(--el-border-color);
  margin: 6px 0;
  padding-left: 8px;
  color: var(--el-text-color-secondary);
}
.markdown-body hr {
  border: none;
  border-top: 1px solid var(--el-border-color-lighter);
  margin: 8px 0;
}
</style>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { getTaskPage, resumeTask } from '@/api/task'
import { TASK_STATUS_MAP, isActive, reimbIdOf } from '@/utils/task'
import type { TaskStatus, TaskVO } from '@/types'

const router = useRouter()
const loading = ref(false)
const records = ref<TaskVO[]>([])
const total = ref(0)
const query = reactive<{ pageNum: number; pageSize: number; status: TaskStatus | '' }>({
  pageNum: 1,
  pageSize: 10,
  status: '',
})

let timer: number | undefined

async function load(page?: number) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const result = await getTaskPage({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      ...(query.status ? { status: query.status } : {}),
    })
    records.value = result.records || []
    total.value = result.total || 0
    syncPolling()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 当前页存在进行中任务则轮询刷新，全部终态后停止 */
function syncPolling() {
  const active = records.value.some((r) => isActive(r.status))
  if (active && !timer) {
    timer = window.setInterval(() => load(), 2500)
  } else if (!active && timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function handleResume(row: TaskVO) {
  try {
    await resumeTask(row.id)
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
  <el-card>
    <template #header>
      <div class="list-header">
        <span>任务列表</span>
        <div class="filters">
          <el-select
            v-model="query.status"
            placeholder="状态"
            clearable
            style="width: 140px"
            @change="load(1)"
          >
            <el-option v-for="(v, k) in TASK_STATUS_MAP" :key="k" :label="v.label" :value="k" />
          </el-select>
          <el-button type="primary" :icon="Plus" @click="router.push('/dashboard')">提交任务</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="records" empty-text="暂无任务">
      <el-table-column prop="taskNo" label="任务编号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="TASK_STATUS_MAP[row.status as TaskStatus].tag">{{ TASK_STATUS_MAP[row.status as TaskStatus].label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="100">
        <template #default="{ row }">{{ row.finishedSteps }} / {{ row.totalSteps }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="router.push(`/tasks/${row.id}`)">详情</el-button>
          <el-button
            v-if="reimbIdOf(row) != null"
            link
            type="primary"
            size="small"
            @click="router.push(`/reimbursements/${reimbIdOf(row)}`)"
          >
            报销单
          </el-button>
          <el-button v-if="isActive(row.status)" link type="warning" size="small" @click="handleResume(row)">
            续跑
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="load(1)"
      />
    </div>
  </el-card>
</template>

<style scoped>
.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.filters {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>

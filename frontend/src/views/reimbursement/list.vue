<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { getReimbursementPage } from '@/api/reimbursement'
import { TASK_STATUS_MAP } from '@/utils/task'
import type { ReimbStatus, ReimbursementVO } from '@/types'

const router = useRouter()
const loading = ref(false)
const records = ref<ReimbursementVO[]>([])
const total = ref(0)
const query = reactive<{ pageNum: number; pageSize: number; status: ReimbStatus | '' }>({
  pageNum: 1,
  pageSize: 10,
  status: '',
})

async function load(page?: number) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const result = await getReimbursementPage({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      ...(query.status ? { status: query.status } : {}),
    })
    records.value = result.records || []
    total.value = result.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function goDetail(id: number) {
  router.push(`/reimbursements/${id}`)
}

onMounted(() => load())
</script>

<template>
  <el-card class="page-card">
    <template #header>
      <div class="list-header">
        <div>
          <div class="page-title card-title">我的报销单</div>
          <div class="page-subtitle">查看个人报销记录、审核状态及对应任务</div>
        </div>
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
          <el-button type="primary" :icon="Plus" @click="router.push('/reimbursements/create')">
            提交报销
          </el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="records" empty-text="暂无报销单">
      <el-table-column prop="reimbNo" label="报销单号" min-width="170" show-overflow-tooltip />
      <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
      <el-table-column label="费用类型" width="100">
        <template #default="{ row }">{{ row.expenseType }}</template>
      </el-table-column>
      <el-table-column label="申报金额" width="120">
        <template #default="{ row }">￥{{ Number(row.totalAmount).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="TASK_STATUS_MAP[row.status as ReimbStatus].tag">
            {{ TASK_STATUS_MAP[row.status as ReimbStatus].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="claimDate" label="报销日期" width="120" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="goDetail(row.id)">详情</el-button>
          <el-button
            v-if="row.taskId"
            link
            type="primary"
            size="small"
            @click="router.push(`/tasks/${row.taskId}`)"
          >
            任务
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
.card-title {
  font-size: 16px;
}

.pager {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
}
</style>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getReimbursementPage } from '@/api/reimbursement'
import { TASK_STATUS_MAP } from '@/utils/task'
import StatusStamp from '@/components/StatusStamp.vue'
import EmptyState from '@/components/EmptyState.vue'
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

function money(v: unknown): string {
  return `¥${Number(v ?? 0).toFixed(2)}`
}

onMounted(() => load())
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-head-title">报销单</div>
        <div class="page-head-sub">每一笔申报都有对应的审核任务与留痕记录</div>
      </div>
      <div class="page-head-actions">
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          style="width: 132px"
          @change="load(1)"
        >
          <el-option v-for="(v, k) in TASK_STATUS_MAP" :key="k" :label="v.label" :value="k" />
        </el-select>
        <el-button type="primary" @click="router.push('/reimbursements/create')">提交报销单</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="records" class="ledger-table">
      <template #empty>
        <EmptyState title="还没有报销单" description="填一张报销单，Agent 会逐项核验票据、预算与规则">
          <el-button type="primary" @click="router.push('/reimbursements/create')">提交第一张报销单</el-button>
        </EmptyState>
      </template>
      <el-table-column prop="reimbNo" label="单号" min-width="175" show-overflow-tooltip />
      <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip />
      <el-table-column prop="expenseType" label="费用类型" width="100" />
      <el-table-column label="申报金额" width="130" align="right">
        <template #default="{ row }">
          <span class="money">{{ money(row.totalAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusStamp
            :label="TASK_STATUS_MAP[row.status as ReimbStatus].label"
            :tone="TASK_STATUS_MAP[row.status as ReimbStatus].tone"
          />
        </template>
      </el-table-column>
      <el-table-column prop="claimDate" label="报销日期" width="115" />
      <el-table-column label="" width="120" align="right" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="router.push(`/reimbursements/${row.id}`)">详情</el-button>
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
  </div>
</template>

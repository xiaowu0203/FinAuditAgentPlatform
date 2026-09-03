<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAuditTicketPage } from '@/api/audit'
import { AUDIT_STATUS_MAP, AUDIT_TRIGGER_MAP } from '@/utils/task'
import StatusStamp from '@/components/StatusStamp.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { AuditTicketStatus, AuditTicketVO, AuditTriggerType } from '@/types'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const records = ref<AuditTicketVO[]>([])
const total = ref(0)
const query = reactive<{
  pageNum: number
  pageSize: number
  status: AuditTicketStatus | ''
  taskId: number | null
}>({
  pageNum: 1,
  pageSize: 10,
  // 支持 /audits?status=PENDING（工作台待办入口）与 /audits?taskId= 预过滤
  status: (route.query.status as AuditTicketStatus) || '',
  taskId: route.query.taskId ? Number(route.query.taskId) : null,
})

let timer: number | undefined

async function load(page?: number) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const result = await getAuditTicketPage({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      ...(query.status ? { status: query.status } : {}),
      ...(query.taskId ? { taskId: query.taskId } : {}),
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

/** 存在待审批 / 重跑中 / 撤销待审的工单则轮询刷新（提交人改后重跑自动闭合 / 等财务处理撤销），全部终态后停止 */
function syncPolling() {
  const pending = records.value.some(
    (r) => r.status === 'PENDING' || r.status === 'AMENDED' || r.status === 'WITHDRAW_PENDING',
  )
  if (pending && !timer) {
    timer = window.setInterval(() => load(), 2500)
  } else if (!pending && timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function money(v: unknown): string {
  return `¥${Number(v ?? 0).toFixed(2)}`
}

onMounted(() => load())
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-head-title">审批工单</div>
        <div class="page-head-sub">
          流水线命中复核后生成；申请人查看本人工单，财务执行审批
          <template v-if="query.taskId"> · 按任务 {{ query.taskId }} 过滤</template>
        </div>
      </div>
      <div class="page-head-actions">
        <el-select
          v-model="query.status"
          placeholder="全部状态"
          clearable
          style="width: 132px"
          @change="load(1)"
        >
          <el-option v-for="(v, k) in AUDIT_STATUS_MAP" :key="k" :label="v.label" :value="k" />
        </el-select>
        <el-button v-if="query.taskId" @click="query.taskId = null; load(1)">清除任务过滤</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="records" class="ledger-table">
      <template #empty>
        <EmptyState
          title="暂无审批工单"
          description="工单在流水线命中大额、超标或风控存疑时自动生成，无需手工创建"
        />
      </template>
      <el-table-column prop="ticketNo" label="工单号" min-width="175" show-overflow-tooltip />
      <el-table-column prop="title" label="任务标题" min-width="150" show-overflow-tooltip />
      <el-table-column label="触发类型" width="130">
        <template #default="{ row }">
          <StatusStamp
            :label="AUDIT_TRIGGER_MAP[row.triggerType as AuditTriggerType].label"
            :tone="AUDIT_TRIGGER_MAP[row.triggerType as AuditTriggerType].tone"
          />
        </template>
      </el-table-column>
      <el-table-column prop="riskDesc" label="复核原因" min-width="200" show-overflow-tooltip />
      <el-table-column label="申报总额" width="115" align="right">
        <template #default="{ row }">
          <span class="money">{{ money(row.originAmount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="修改后" width="115" align="right">
        <template #default="{ row }">
          <span v-if="row.adjustedAmount != null" class="money">{{ money(row.adjustedAmount) }}</span>
          <span v-else class="faint">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <StatusStamp
            :label="AUDIT_STATUS_MAP[row.status as AuditTicketStatus].label"
            :tone="AUDIT_STATUS_MAP[row.status as AuditTicketStatus].tone"
          />
        </template>
      </el-table-column>
      <el-table-column label="" width="80" align="right" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="router.push(`/audits/${row.id}`)">详情</el-button>
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

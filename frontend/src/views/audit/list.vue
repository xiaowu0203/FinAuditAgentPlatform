<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAuditTicketPage } from '@/api/audit'
import { AUDIT_STATUS_MAP, AUDIT_TRIGGER_MAP } from '@/utils/task'
import type { AuditTicketStatus, AuditTicketVO, AuditTriggerType } from '@/types'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const records = ref<AuditTicketVO[]>([])
const total = ref(0)
const query = reactive<{ pageNum: number; pageSize: number; status: AuditTicketStatus | ''; taskId: number | null }>({
  pageNum: 1,
  pageSize: 10,
  status: '',
  // 支持 /audits?taskId= 预过滤（任务/报销单详情「查看审批工单」入口）
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

onMounted(() => load())
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <el-card class="page-card">
    <template #header>
      <div class="list-header">
        <div>
          <div class="page-title card-title">审批工单</div>
          <div class="page-subtitle">
            {{ query.taskId ? `按任务 ${query.taskId} 过滤 · ` : '' }}流水线命中复核后生成；申请人查看本人工单，财务执行通过/驳回/终止/撤销审批
          </div>
        </div>
        <div class="filters">
          <el-select
            v-model="query.status"
            placeholder="状态"
            clearable
            style="width: 140px"
            @change="load(1)"
          >
            <el-option v-for="(v, k) in AUDIT_STATUS_MAP" :key="k" :label="v.label" :value="k" />
          </el-select>
          <el-button v-if="query.taskId" @click="query.taskId = null; load(1)">清除任务过滤</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="records" empty-text="暂无审批工单">
      <el-table-column prop="ticketNo" label="工单号" min-width="170" show-overflow-tooltip />
      <el-table-column prop="title" label="任务标题" min-width="140" show-overflow-tooltip />
      <el-table-column label="触发类型" width="120">
        <template #default="{ row }">
          <el-tag :type="AUDIT_TRIGGER_MAP[row.triggerType as AuditTriggerType].tag">{{ AUDIT_TRIGGER_MAP[row.triggerType as AuditTriggerType].label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="riskDesc" label="复核原因" min-width="220" show-overflow-tooltip />
      <el-table-column label="申报总额" width="110">
        <template #default="{ row }">￥{{ Number(row.originAmount).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="修改后" width="110">
        <template #default="{ row }">{{ row.adjustedAmount != null ? `￥${Number(row.adjustedAmount).toFixed(2)}` : '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="AUDIT_STATUS_MAP[row.status as AuditTicketStatus].tag">
            {{ AUDIT_STATUS_MAP[row.status as AuditTicketStatus].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rerunCount" label="重跑" width="70" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="90" fixed="right">
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
  </el-card>
</template>

<style scoped>
.card-title {
  font-size: 16px;
}
</style>

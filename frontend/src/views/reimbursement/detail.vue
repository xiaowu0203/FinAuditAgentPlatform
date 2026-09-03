<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Download, View } from '@element-plus/icons-vue'
import { getAuditTicketPage } from '@/api/audit'
import { getFileDownloadUrl } from '@/api/file'
import { getReimbursementDetail, withdrawReimbursement, withdrawRequestReimbursement } from '@/api/reimbursement'
import { TASK_STATUS_MAP } from '@/utils/task'
import { useAuthStore } from '@/stores/auth'
import type { AuditTicketStatus, AuditTicketVO, ReimbursementDetailVO } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const reimbId = Number(route.params.id)

const loading = ref(false)
const detail = ref<ReimbursementDetailVO | null>(null)
/** 关联工单（经 /audit/tickets?taskId= 解析，uk_task 1:1）：状态驱动界面/轮询，复核原因供提交人了解驳回点 */
const ticket = ref<AuditTicketVO | null>(null)
const ticketStatus = computed<AuditTicketStatus | null>(() => (ticket.value?.status as AuditTicketStatus) ?? null)

let timer: number | undefined

const reimb = computed(() => detail.value?.reimbursement)
/** 是否提交人本人（P3.5b 取代「非财务角色」判定）：修改重跑/撤回仅对单据归属人可见，后端按 ownership 兜底 */
const isOwner = computed(() => !!auth.user?.id && auth.user.id === reimb.value?.applicantId)
const totalAmount = computed(() =>
  (detail.value?.items || []).reduce((sum, it) => sum + (Number(it.amount) || 0), 0),
)

/** 解析关联工单（reimb.task_id → 工单 1:1，uk_task；存整单供提交人读复核原因） */
async function loadTicketStatus() {
  if (!reimb.value?.taskId) {
    ticket.value = null
    return
  }
  try {
    const page = await getAuditTicketPage({ pageNum: 1, pageSize: 1, taskId: reimb.value.taskId })
    ticket.value = page.records?.[0] ?? null
  } catch {
    // 拦截器已提示；工单解析失败不阻塞详情渲染
  }
}

async function load() {
  loading.value = true
  try {
    detail.value = await getReimbursementDetail(reimbId)
    await loadTicketStatus()
    syncPolling()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 执行中 / 撤销待审则轮询刷新（修改重跑后自动闭合、等财务处理撤销），终态后停止 */
function syncPolling() {
  const active = reimb.value?.status === 'RUNNING' || ticketStatus.value === 'WITHDRAW_PENDING'
  if (active && !timer) {
    timer = window.setInterval(() => load(), 2500)
  } else if (!active && timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

// ---------- 提交人动作（P3b） ----------
/** 撤回（仅待审批）：工单 WITHDRAWN，任务/报销单作废，附件解绑 */
async function handleWithdraw() {
  try {
    await ElMessageBox.confirm(
      '确认撤回该报销单？撤回后工单作废、报销单/任务 CANCELLED，附件可复用，且无法恢复。',
      '撤回确认',
      { type: 'warning', confirmButtonText: '撤回', confirmButtonClass: 'el-button--danger' },
    )
  } catch {
    return
  }
  try {
    await withdrawReimbursement(reimbId)
    ElMessage.success('已撤回')
    load()
  } catch {
    // 拦截器已提示
  }
}

/** 发起撤销申请（仅已通过）：工单 WITHDRAW_PENDING，等财务同意/拒绝 */
async function handleWithdrawRequest() {
  try {
    await ElMessageBox.confirm(
      '确认发起撤销申请？财务同意后报销单/任务作废；拒绝后单据保持已通过。',
      '发起撤销',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await withdrawRequestReimbursement(reimbId)
    ElMessage.success('已发起撤销申请，等待财务处理')
    load()
  } catch {
    // 拦截器已提示
  }
}

function preview(att: { url: string | null }) {
  if (att.url) window.open(att.url, '_blank', 'noopener')
}

async function download(fileRecordId: number) {
  try {
    const url = await getFileDownloadUrl(fileRecordId)
    window.open(url, '_blank', 'noopener')
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page-shell detail">
    <el-card v-loading="loading" class="page-card mb">
      <template #header>
        <div class="detail-header">
          <div>
            <div class="page-title card-title">报销单详情：{{ reimb?.reimbNo }}</div>
            <div class="page-subtitle">集中查看报销基础信息、明细汇总和附件资料</div>
          </div>
          <div class="header-actions">
            <template v-if="isOwner && reimb">
              <!-- 待审批 / 已驳回：提交人可修改明细重跑（标题/部门不可改） -->
              <el-button
                v-if="(reimb.status === 'MANUAL_REVIEW' || reimb.status === 'FAILED') && ticketStatus !== 'WITHDRAW_PENDING'"
                type="primary"
                @click="router.push(`/reimbursements/${reimbId}/edit`)"
              >
                修改明细重跑
              </el-button>
              <!-- 待审批：提交人可直接撤回 -->
              <el-button v-if="reimb.status === 'MANUAL_REVIEW'" type="danger" plain @click="handleWithdraw">
                撤回
              </el-button>
              <!-- 已通过：提交人可发起撤销申请（等财务同意/拒绝） -->
              <el-button
                v-if="reimb.status === 'SUCCESS' && ticketStatus === 'APPROVED'"
                type="danger"
                plain
                @click="handleWithdrawRequest"
              >
                发起撤销
              </el-button>
            </template>
            <el-button v-if="reimb?.taskId" type="primary" @click="router.push(`/tasks/${reimb!.taskId}`)">
              查看审核任务
            </el-button>
            <!-- 任意用户可见：audit:viewAll 持有者（财务）在人工复核时跳转执行审批（danger）；申请人（归属人）在工单解析成功后只读查看本人工单 -->
            <el-button
              v-if="auth.hasPerm('audit:viewAll')
                ? (reimb?.status === 'MANUAL_REVIEW' && reimb?.taskId)
                : !!ticket"
              :type="auth.hasPerm('audit:viewAll') ? 'danger' : 'primary'"
              @click="router.push(`/audits?taskId=${reimb!.taskId}`)"
            >
              查看审批工单
            </el-button>
            <el-button :icon="Back" @click="router.back()">返回</el-button>
          </div>
        </div>
      </template>

      <template v-if="reimb">
        <el-descriptions :column="3" border class="soft-descriptions">
          <el-descriptions-item label="标题">{{ reimb.title }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="TASK_STATUS_MAP[reimb.status].tag">{{ TASK_STATUS_MAP[reimb.status].label }}</el-tag>
            <el-tag v-if="ticketStatus === 'WITHDRAW_PENDING'" type="warning" effect="dark" class="status-tag">
              撤销待审
            </el-tag>
            <el-tag v-else-if="ticketStatus === 'WITHDRAWN'" type="info" effect="dark" class="status-tag">
              已撤回/撤销
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申报总金额">
            <span class="amount">￥{{ reimb.totalAmount.toFixed(2) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="费用类型">{{ reimb.expenseType }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ reimb.deptName }}</el-descriptions-item>
          <el-descriptions-item label="报销日期">{{ reimb.claimDate }}</el-descriptions-item>
          <el-descriptions-item label="关联任务">
            <el-link v-if="reimb.taskId" type="primary" @click="router.push(`/tasks/${reimb!.taskId}`)">
              {{ reimb.taskId }}
            </el-link>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ reimb.createdAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="申请人 ID">{{ reimb.applicantId }}</el-descriptions-item>
          <el-descriptions-item v-if="reimb.remark" label="备注" :span="3">{{ reimb.remark }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <!-- 提交人可见：待审批 / 已驳回时的复核原因与处理意见（审批工单页是财务工具面，普通用户经此了解驳回点） -->
    <el-card
      v-if="isOwner && ticket && (ticket.status === 'PENDING' || ticket.status === 'REJECTED')"
      class="page-card mb"
    >
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-title card-title">{{ ticket.status === 'REJECTED' ? '驳回意见' : '待审批复核原因' }}</div>
            <div class="page-subtitle">了解原因后可「修改明细重跑」修正（标题/部门不可改，重跑上限 3 次）</div>
          </div>
        </div>
      </template>
      <el-alert
        :type="ticket.status === 'REJECTED' ? 'error' : 'warning'"
        :closable="false"
        show-icon
        :title="
          ticket.status === 'REJECTED' && ticket.auditComment
            ? ticket.auditComment
            : (ticket.riskDesc || '存在复核项，请对照明细核实')
        "
      />
      <div v-if="ticket.reviewReasons && ticket.reviewReasons.length" class="reason-list">
        <el-tag v-for="(r, i) in ticket.reviewReasons" :key="i" type="warning" effect="plain">{{ r }}</el-tag>
      </div>
    </el-card>

    <el-card class="page-card mb">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-title card-title">报销明细（{{ (detail?.items || []).length }} 项）</div>
            <div class="page-subtitle">明细合计与服务端核验金额保持一致</div>
          </div>
        </div>
      </template>
      <el-table :data="detail?.items || []" empty-text="暂无明细">
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="金额" width="130">
          <template #default="{ row }">￥{{ Number(row.amount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="amountType" label="金额类型" width="110">
          <template #default="{ row }">{{ row.amountType || '—' }}</template>
        </el-table-column>
        <el-table-column label="数量" width="90">
          <template #default="{ row }">{{ row.quantity ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="单价" width="120">
          <template #default="{ row }">{{ row.unitPrice != null ? `￥${Number(row.unitPrice).toFixed(2)}` : '—' }}</template>
        </el-table-column>
        <el-table-column label="发生日期" width="120">
          <template #default="{ row }">{{ row.date || '—' }}</template>
        </el-table-column>
        <!-- 差旅明细扩展字段（非差旅项显示 —） -->
        <el-table-column label="城市" width="100">
          <template #default="{ row }">{{ row.city || '—' }}</template>
        </el-table-column>
        <el-table-column label="住宿天数" width="90">
          <template #default="{ row }">{{ row.hotelDays ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="住宿金额" width="110">
          <template #default="{ row }">{{ row.hotelAmount != null ? `￥${Number(row.hotelAmount).toFixed(2)}` : '—' }}</template>
        </el-table-column>
        <el-table-column label="交通金额" width="110">
          <template #default="{ row }">{{ row.transportAmount != null ? `￥${Number(row.transportAmount).toFixed(2)}` : '—' }}</template>
        </el-table-column>
        <el-table-column label="补贴金额" width="110">
          <template #default="{ row }">{{ row.subsidyAmount != null ? `￥${Number(row.subsidyAmount).toFixed(2)}` : '—' }}</template>
        </el-table-column>
        <template #append>
          <div class="table-total">
            <span>合计</span>
            <span class="amount">￥{{ totalAmount.toFixed(2) }}</span>
          </div>
        </template>
      </el-table>
    </el-card>

    <el-card class="page-card">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-title card-title">附件（{{ (detail?.attachments || []).length }}）</div>
            <div class="page-subtitle">支持预览和下载，方便对照审核材料</div>
          </div>
        </div>
      </template>
      <el-empty v-if="!detail?.attachments || detail.attachments.length === 0" description="暂无附件" />
      <div v-else class="attachment-grid">
        <el-card v-for="att in detail.attachments" :key="att.id" shadow="hover" class="attachment-card">
          <div class="att-name" :title="att.fileName || '未命名文件'">
            📎 {{ att.fileName || `file_${att.fileRecordId}` }}
          </div>
          <div class="att-meta">
            <el-tag size="small" type="info">{{ att.fileType }}</el-tag>
            <el-tag size="small" :type="att.ocrStatus === 'SUCCESS' ? 'success' : 'info'">
              OCR: {{ att.ocrStatus }}
            </el-tag>
          </div>
          <div class="att-actions">
            <el-button
              v-if="att.url"
              size="small"
              :icon="View"
              @click="preview(att)"
            >预览</el-button>
            <el-button size="small" :icon="Download" @click="download(att.fileRecordId)">下载</el-button>
          </div>
        </el-card>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.detail {
  gap: 16px;
}

.card-title {
  font-size: 16px;
}

.amount {
  color: #f56c6c;
  font-weight: 600;
}

.status-tag {
  margin-left: 6px;
}

.reason-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.table-total {
  display: flex;
  justify-content: flex-end;
  gap: 16px;
  padding: 8px 16px;
  font-weight: 600;
}

.attachment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.attachment-card {
  text-align: center;
}

.att-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  margin-bottom: 8px;
}

.att-meta {
  display: flex;
  justify-content: center;
  gap: 6px;
  margin-bottom: 8px;
}

.att-actions {
  display: flex;
  justify-content: center;
  gap: 8px;
}

.soft-descriptions :deep(.el-descriptions__body) {
  border-radius: 16px;
  overflow: hidden;
}
</style>

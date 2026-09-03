<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Download, View } from '@element-plus/icons-vue'
import {
  approveAuditTicket,
  getAuditRecords,
  getAuditTicketDetail,
  rejectAuditTicket,
  terminateAuditTicket,
  withdrawAgreeAuditTicket,
  withdrawRefuseAuditTicket,
} from '@/api/audit'
import { getFileDownloadUrl } from '@/api/file'
import { AUDIT_ACTION_MAP, AUDIT_STATUS_MAP, AUDIT_TRIGGER_MAP } from '@/utils/task'
import { useAuthStore } from '@/stores/auth'
import type { AuditRecordVO, AuditTicketDetailVO, AuditTicketVO } from '@/types'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ticketId = Number(route.params.id)

const loading = ref(false)
const detail = ref<AuditTicketDetailVO | null>(null)
const records = ref<AuditRecordVO[]>([])

let timer: number | undefined

const ticket = computed<AuditTicketVO | null>(() => detail.value?.ticket || null)
const reimb = computed(() => detail.value?.reimbursement)
/** 审批权限（P3.5 权限码，取代角色字符串）：审批动作按钮仅 audit:approve 可见 */
const isFinance = computed(() => auth.hasPerm('audit:approve'))

/** 对象 → 格式化 JSON 文本 */
function pretty(obj: unknown): string {
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

// ---------- 加载 ----------
async function load() {
  loading.value = true
  try {
    detail.value = await getAuditTicketDetail(ticketId)
    records.value = (await getAuditRecords(ticketId)) || []
    syncPolling()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

/** 工单待审批 / 重跑中 / 撤销待审则轮询刷新（提交人改后重跑可自动通过闭合 / 等提交人处理撤销），终态后停止 */
function syncPolling() {
  const s = ticket.value?.status
  const active = s === 'PENDING' || s === 'AMENDED' || s === 'WITHDRAW_PENDING'
  if (active && !timer) {
    timer = window.setInterval(() => load(), 2500)
  } else if (!active && timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

// ---------- 审批动作 ----------
/** 通过 / 终止：二次确认 */
async function confirmAction(action: 'approve' | 'terminate', label: string) {
  try {
    await ElMessageBox.confirm(`确认${label}该审批工单？`, '操作确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    if (action === 'approve') await approveAuditTicket(ticketId)
    else await terminateAuditTicket(ticketId)
    ElMessage.success(`已${label}`)
    load()
  } catch {
    // 拦截器已提示
  }
}

/** 驳回：必填意见 */
async function handleReject() {
  let comment = ''
  try {
    const res = await ElMessageBox.prompt('请输入驳回意见', '审批驳回', {
      inputPlaceholder: '驳回原因（必填）',
      inputValidator: (v) => (v && v.trim().length > 0) || '驳回意见不能为空',
    })
    comment = (res.value || '').trim()
  } catch {
    return
  }
  try {
    await rejectAuditTicket(ticketId, { comment })
    ElMessage.success('已驳回')
    load()
  } catch {
    // 拦截器已提示
  }
}

/** 同意 / 拒绝撤销（提交人已发起撤销申请后）：二次确认 */
async function handleWithdraw(action: 'agree' | 'refuse') {
  const label = action === 'agree' ? '同意撤销' : '拒绝撤销'
  const tip =
    action === 'agree'
      ? '工单将作废（WITHDRAWN），任务/报销单 CANCELLED，附件解绑可复用。'
      : '工单将回到已通过状态，单据与数据不变。'
  try {
    await ElMessageBox.confirm(`确认${label}该撤销申请？${tip}`, '操作确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    if (action === 'agree') await withdrawAgreeAuditTicket(ticketId)
    else await withdrawRefuseAuditTicket(ticketId)
    ElMessage.success(`已${label}`)
    load()
  } catch {
    // 拦截器已提示
  }
}

// ---------- 附件 ----------
function preview(att: { url: string | null }) {
  if (att.url) window.open(att.url, '_blank', 'noopener')
}

/** 留痕是否携带数据快照（P3b：SUBMIT 后每次动作均有 before/after，首条 before 为 null） */
function hasSnapshot(r: AuditRecordVO): boolean {
  return !!(r.beforeData || r.afterData)
}

async function download(fileRecordId: number) {
  try {
    const url = await getFileDownloadUrl(fileRecordId)
    window.open(url, '_blank', 'noopener')
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
  <div class="page-shell detail">
    <!-- 工单基础信息 -->
    <el-card v-loading="loading" class="page-card mb">
      <template #header>
        <div class="detail-header">
          <div>
            <div class="page-title card-title">{{ ticket?.ticketNo }}</div>
            <div class="page-subtitle">流水线命中复核自动生成，财务执行终审动作（每次动作均留痕）</div>
          </div>
          <div class="header-actions">
            <template v-if="isFinance && ticket?.status === 'PENDING'">
              <el-button type="success" @click="confirmAction('approve', '通过')">通过</el-button>
              <el-button type="danger" @click="handleReject">驳回</el-button>
              <el-button type="warning" @click="confirmAction('terminate', '终止')">终止</el-button>
            </template>
            <template v-if="isFinance && ticket?.status === 'WITHDRAW_PENDING'">
              <el-button type="primary" @click="handleWithdraw('agree')">同意撤销</el-button>
              <el-button @click="handleWithdraw('refuse')">拒绝撤销</el-button>
            </template>
            <el-button :icon="Back" @click="router.back()">返回</el-button>
          </div>
        </div>
      </template>

      <template v-if="ticket">
        <el-descriptions :column="3" border class="soft-descriptions">
          <el-descriptions-item label="任务标题">{{ ticket.title }}</el-descriptions-item>
          <el-descriptions-item label="触发类型">
            <el-tag :type="AUDIT_TRIGGER_MAP[ticket.triggerType].tag">{{ AUDIT_TRIGGER_MAP[ticket.triggerType].label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="工单状态">
            <el-tag :type="AUDIT_STATUS_MAP[ticket.status].tag">{{ AUDIT_STATUS_MAP[ticket.status].label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="关联任务">
            <el-link type="primary" @click="router.push(`/tasks/${ticket.taskId}`)">{{ ticket.taskId }}</el-link>
          </el-descriptions-item>
          <el-descriptions-item label="申报总额">
            <span class="amount">￥{{ Number(ticket.originAmount).toFixed(2) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="修改后金额">
            <span v-if="ticket.adjustedAmount != null" class="amount">￥{{ Number(ticket.adjustedAmount).toFixed(2) }}</span>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="重跑次数">{{ ticket.rerunCount }} / 3</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ ticket.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="最近处理人">
            <span v-if="ticket.auditorId">{{ ticket.auditorId }}</span>
            <span v-else>—</span>
          </el-descriptions-item>
          <el-descriptions-item label="复核原因列表" :span="3">
            <div class="reason-list">
              <el-tag v-for="(r, i) in ticket.reviewReasons || []" :key="i" type="warning" effect="plain" class="reason-tag">
                {{ r }}
              </el-tag>
              <span v-if="!ticket.reviewReasons || ticket.reviewReasons.length === 0" class="muted">—</span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item v-if="ticket.auditComment" label="最近处理意见" :span="3">
            {{ ticket.auditComment }}
          </el-descriptions-item>
        </el-descriptions>
      </template>
    </el-card>

    <!-- 关联报销单（GENERIC 任务无报销单） -->
    <el-card v-if="reimb" class="page-card mb">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-title card-title">关联报销单：{{ reimb.reimbursement.reimbNo }}</div>
            <div class="page-subtitle">对照单据明细与票据 OCR 抽取字段执行审批</div>
          </div>
        </div>
      </template>

      <el-descriptions :column="3" border class="soft-descriptions mb">
        <el-descriptions-item label="标题">{{ reimb.reimbursement.title }}</el-descriptions-item>
        <el-descriptions-item label="费用类型">{{ reimb.reimbursement.expenseType }}</el-descriptions-item>
        <el-descriptions-item label="申报总金额">
          <span class="amount">￥{{ Number(reimb.reimbursement.totalAmount).toFixed(2) }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-table :data="reimb.items" empty-text="暂无明细">
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="金额" width="130">
          <template #default="{ row }">￥{{ Number(row.amount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="amountType" label="金额类型" width="110">
          <template #default="{ row }">{{ row.amountType || '—' }}</template>
        </el-table-column>
        <el-table-column label="发生日期" width="120">
          <template #default="{ row }">{{ row.date || '—' }}</template>
        </el-table-column>
        <!-- 差旅明细扩展字段（审批判断差旅标准所需；非差旅项显示 —） -->
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
      </el-table>

      <div v-if="reimb.attachments && reimb.attachments.length" class="attachment-grid mt">
        <el-card v-for="att in reimb.attachments" :key="att.id" shadow="hover" class="attachment-card">
          <div class="att-name" :title="att.fileName || '未命名文件'">
            📎 {{ att.fileName || `file_${att.fileRecordId}` }}
          </div>
          <div class="att-meta">
            <el-tag size="small" type="info">{{ att.fileType }}</el-tag>
            <el-tag size="small" :type="att.ocrStatus === 'SUCCESS' ? 'success' : 'info'">OCR: {{ att.ocrStatus }}</el-tag>
          </div>
          <el-collapse v-if="att.ocrResult" class="ocr-collapse">
            <el-collapse-item title="OCR 抽取字段" name="ocr">
              <pre class="json-block compact">{{ pretty(att.ocrResult) }}</pre>
            </el-collapse-item>
          </el-collapse>
          <div class="att-actions">
            <el-button v-if="att.url" size="small" :icon="View" @click="preview(att)">预览</el-button>
            <el-button size="small" :icon="Download" @click="download(att.fileRecordId)">下载</el-button>
          </div>
        </el-card>
      </div>
    </el-card>

    <!-- 审批留痕时间线 -->
    <el-card class="page-card">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-title card-title">审批留痕（{{ records.length }}）</div>
            <div class="page-subtitle">append-only 审计溯源：操作人 / 变更前后金额 / 意见 / 时间</div>
          </div>
        </div>
      </template>
      <el-timeline v-if="records.length" class="timeline">
        <el-timeline-item
          v-for="r in records"
          :key="r.id"
          :timestamp="r.createdAt"
          placement="top"
          :type="r.action === 'REJECT' || r.action === 'TERMINATE' ? 'danger' : r.action === 'APPROVE' ? 'success' : 'primary'"
        >
          <div class="record-item">
            <div class="record-head">
              <el-tag size="small" :type="r.action === 'REJECT' || r.action === 'TERMINATE' ? 'danger' : 'info'">
                {{ AUDIT_ACTION_MAP[r.action] ?? r.action }}
              </el-tag>
              <span class="operator">{{ r.operatorName || '系统' }}<template v-if="r.operatorRoles">（{{ r.operatorRoles }}）</template></span>
            </div>
            <div v-if="r.beforeAmount != null" class="record-amount">
              <template v-if="r.afterAmount != null && r.afterAmount !== r.beforeAmount">
                ￥{{ Number(r.beforeAmount).toFixed(2) }} → <b>￥{{ Number(r.afterAmount).toFixed(2) }}</b>
              </template>
              <template v-else>￥{{ Number(r.beforeAmount).toFixed(2) }}</template>
            </div>
            <div v-if="r.comment" class="record-comment">{{ r.comment }}</div>
            <el-collapse v-if="hasSnapshot(r)" class="snap-collapse">
              <el-collapse-item title="数据快照（改前 / 改后，不含预签名 URL）" name="snap">
                <div class="snap-grid">
                  <div v-if="r.beforeData" class="snap-box">
                    <div class="snap-label">改前</div>
                    <pre class="json-block compact">{{ pretty(r.beforeData) }}</pre>
                  </div>
                  <div v-else class="snap-box snap-empty">改前：无（首条提交）</div>
                  <div v-if="r.afterData" class="snap-box">
                    <div class="snap-label">改后</div>
                    <pre class="json-block compact">{{ pretty(r.afterData) }}</pre>
                  </div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无审批留痕" />
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

.mb {
  margin-bottom: 12px;
}

.mt {
  margin-top: 12px;
}

.muted {
  color: var(--el-text-color-secondary);
}

.reason-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.reason-tag {
  max-width: 100%;
}

.timeline {
  padding: 4px 6px 0;
}

.record-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.record-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.operator {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.record-amount {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.record-comment {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.attachment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
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

.ocr-collapse {
  text-align: left;
  margin-bottom: 8px;
}

.att-actions {
  display: flex;
  justify-content: center;
  gap: 8px;
}

.snap-collapse {
  margin-top: 6px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.snap-collapse :deep(.el-collapse-item__header) {
  height: 34px;
  line-height: 34px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.snap-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.snap-box {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.snap-empty {
  display: grid;
  place-items: center;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.snap-label {
  padding: 4px 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
}

.snap-box .json-block {
  max-height: 220px;
  margin: 0;
}

@media (max-width: 768px) {
  .snap-grid {
    grid-template-columns: 1fr;
  }
}

.soft-descriptions :deep(.el-descriptions__body) {
  border-radius: 16px;
  overflow: hidden;
}
</style>

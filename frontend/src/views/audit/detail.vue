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
import StatusStamp from '@/components/StatusStamp.vue'
import SealStamp from '@/components/SealStamp.vue'
import EmptyState from '@/components/EmptyState.vue'
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

/** 终态印章：仅 APPROVED / REJECTED / TERMINATED 盖章（撤销等中间态走状态戳） */
const seal = computed<{ text: string; tone: 'success' | 'danger' } | null>(() => {
  if (!ticket.value) return null
  if (ticket.value.status === 'APPROVED') return { text: '同意', tone: 'success' }
  if (ticket.value.status === 'REJECTED') return { text: '驳回', tone: 'danger' }
  if (ticket.value.status === 'TERMINATED') return { text: '终止', tone: 'danger' }
  return null
})

function money(v: unknown): string {
  return `¥${Number(v ?? 0).toFixed(2)}`
}

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
  <div>
    <!-- 页头：工单号 + 审批动作 -->
    <div class="page-head">
      <div>
        <div class="page-head-title">{{ ticket?.ticketNo || `工单 #${ticketId}` }}</div>
        <div class="page-head-sub detail-sub">
          <span v-if="ticket">{{ ticket.title }}</span>
          <StatusStamp
            v-if="ticket"
            :label="AUDIT_STATUS_MAP[ticket.status].label"
            :tone="AUDIT_STATUS_MAP[ticket.status].tone"
          />
          <StatusStamp
            v-if="ticket"
            :label="AUDIT_TRIGGER_MAP[ticket.triggerType].label"
            :tone="AUDIT_TRIGGER_MAP[ticket.triggerType].tone"
          />
        </div>
      </div>
      <div class="page-head-actions">
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

    <!-- 工单单据：右上角盖终审印章 -->
    <div v-loading="loading" class="panel ticket-panel">
      <SealStamp v-if="seal" class="ticket-seal" :text="seal.text" :tone="seal.tone" />
      <div class="panel-head">
        <span class="panel-title">工单信息</span>
        <span class="faint">流水线命中复核自动生成，每次动作均留痕</span>
      </div>
      <div class="panel-body">
        <template v-if="ticket">
          <!-- 金额对比条：申报 vs 修改后 -->
          <div class="amount-strip">
            <div class="amt">
              <span class="amt-label">申报总额</span>
              <strong class="amt-num">{{ money(ticket.originAmount) }}</strong>
            </div>
            <template v-if="ticket.adjustedAmount != null">
              <span class="amt-arrow">→</span>
              <div class="amt">
                <span class="amt-label">修改后</span>
                <strong class="amt-num">{{ money(ticket.adjustedAmount) }}</strong>
              </div>
            </template>
            <div class="amt amt--side">
              <span class="amt-label">重跑</span>
              <strong class="amt-num amt-num--small">{{ ticket.rerunCount }} / 3</strong>
            </div>
            <div class="amt amt--side">
              <span class="amt-label">关联任务</span>
              <el-link type="primary" :underline="false" @click="router.push(`/tasks/${ticket.taskId}`)">
                {{ ticket.taskId }}
              </el-link>
            </div>
          </div>

          <el-descriptions :column="3" border class="ticket-desc">
            <el-descriptions-item label="最近处理人">
              <span v-if="ticket.auditorId">{{ ticket.auditorId }}</span>
              <span v-else class="faint">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ ticket.createdAt }}</el-descriptions-item>
            <el-descriptions-item label="最近处理意见">
              <span v-if="ticket.auditComment">{{ ticket.auditComment }}</span>
              <span v-else class="faint">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="复核原因" :span="3">
              <div class="reason-list">
                <span v-for="(r, i) in ticket.reviewReasons || []" :key="i" class="stamp stamp--pending reason-tag">
                  {{ r }}
                </span>
                <span v-if="!ticket.reviewReasons || ticket.reviewReasons.length === 0" class="faint">—</span>
              </div>
            </el-descriptions-item>
          </el-descriptions>
        </template>
      </div>
    </div>

    <!-- 关联报销单（GENERIC 任务无报销单） -->
    <div v-if="reimb" class="panel ticket-panel">
      <div class="panel-head">
        <span class="panel-title">关联报销单 · {{ reimb.reimbursement.reimbNo }}</span>
        <span class="faint">对照单据明细与票据 OCR 字段执行审批</span>
      </div>
      <div class="panel-body">
        <el-descriptions :column="3" border class="ticket-desc">
          <el-descriptions-item label="标题">{{ reimb.reimbursement.title }}</el-descriptions-item>
          <el-descriptions-item label="费用类型">{{ reimb.reimbursement.expenseType }}</el-descriptions-item>
          <el-descriptions-item label="申报总金额">
            <span class="money">{{ money(reimb.reimbursement.totalAmount) }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-table :data="reimb.items" class="ledger-table ledger-table--bare items-table" empty-text="暂无明细">
          <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
          <el-table-column label="金额" width="120" align="right">
            <template #default="{ row }">
              <span class="money">{{ money(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="amountType" label="金额类型" width="100">
            <template #default="{ row }">{{ row.amountType || '—' }}</template>
          </el-table-column>
          <el-table-column label="发生日期" width="110">
            <template #default="{ row }">{{ row.date || '—' }}</template>
          </el-table-column>
          <!-- 差旅明细扩展字段（审批判断差旅标准所需；非差旅项显示 —） -->
          <el-table-column label="城市" width="90">
            <template #default="{ row }">{{ row.city || '—' }}</template>
          </el-table-column>
          <el-table-column label="住宿天数" width="85" align="center">
            <template #default="{ row }">{{ row.hotelDays ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="住宿金额" width="105" align="right">
            <template #default="{ row }">{{ row.hotelAmount != null ? money(row.hotelAmount) : '—' }}</template>
          </el-table-column>
          <el-table-column label="交通金额" width="105" align="right">
            <template #default="{ row }">{{ row.transportAmount != null ? money(row.transportAmount) : '—' }}</template>
          </el-table-column>
          <el-table-column label="补贴金额" width="105" align="right">
            <template #default="{ row }">{{ row.subsidyAmount != null ? money(row.subsidyAmount) : '—' }}</template>
          </el-table-column>
        </el-table>

        <div v-if="reimb.attachments && reimb.attachments.length" class="attachment-grid">
          <div v-for="att in reimb.attachments" :key="att.id" class="att-card">
            <div class="att-name" :title="att.fileName || '未命名文件'">
              {{ att.fileName || `file_${att.fileRecordId}` }}
            </div>
            <div class="att-meta">
              <span class="stamp stamp--muted">{{ att.fileType }}</span>
              <span class="stamp" :class="att.ocrStatus === 'SUCCESS' ? 'stamp--success' : 'stamp--muted'">
                OCR {{ att.ocrStatus }}
              </span>
            </div>
            <el-collapse v-if="att.ocrResult" class="ocr-collapse">
              <el-collapse-item title="OCR 抽取字段" name="ocr">
                <pre class="output-block output-block--scroll">{{ pretty(att.ocrResult) }}</pre>
              </el-collapse-item>
            </el-collapse>
            <div class="att-actions">
              <el-button v-if="att.url" size="small" :icon="View" @click="preview(att)">预览</el-button>
              <el-button size="small" :icon="Download" @click="download(att.fileRecordId)">下载</el-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 审批留痕时间线 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">审批留痕（{{ records.length }}）</span>
        <span class="faint">append-only 溯源：操作人 / 变更前后金额 / 意见 / 快照</span>
      </div>
      <div class="panel-body">
        <el-timeline v-if="records.length" class="timeline">
          <el-timeline-item
            v-for="r in records"
            :key="r.id"
            :timestamp="r.createdAt"
            placement="top"
            :hollow="true"
            :type="r.action === 'REJECT' || r.action === 'TERMINATE' ? 'danger' : r.action === 'APPROVE' ? 'success' : 'primary'"
          >
            <div class="record-item">
              <div class="record-head">
                <span
                  class="stamp"
                  :class="r.action === 'REJECT' || r.action === 'TERMINATE' ? 'stamp--danger' : r.action === 'APPROVE' ? 'stamp--success' : 'stamp--muted'"
                >
                  {{ AUDIT_ACTION_MAP[r.action] ?? r.action }}
                </span>
                <span class="operator">{{ r.operatorName || '系统' }}<template v-if="r.operatorRoles">（{{ r.operatorRoles }}）</template></span>
              </div>
              <div v-if="r.beforeAmount != null" class="record-amount">
                <template v-if="r.afterAmount != null && r.afterAmount !== r.beforeAmount">
                  <span class="money">{{ money(r.beforeAmount) }}</span>
                  <span> → </span>
                  <b class="money">{{ money(r.afterAmount) }}</b>
                </template>
                <template v-else>
                  <span class="money">{{ money(r.beforeAmount) }}</span>
                </template>
              </div>
              <div v-if="r.comment" class="record-comment">{{ r.comment }}</div>
              <el-collapse v-if="hasSnapshot(r)" class="snap-collapse">
                <el-collapse-item title="数据快照（改前 / 改后，不含预签名 URL）" name="snap">
                  <div class="snap-grid">
                    <div v-if="r.beforeData" class="snap-box">
                      <div class="snap-label">改前</div>
                      <pre class="output-block output-block--scroll">{{ pretty(r.beforeData) }}</pre>
                    </div>
                    <div v-else class="snap-box snap-empty">改前：无（首条提交）</div>
                    <div v-if="r.afterData" class="snap-box">
                      <div class="snap-label">改后</div>
                      <pre class="output-block output-block--scroll">{{ pretty(r.afterData) }}</pre>
                    </div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
          </el-timeline-item>
        </el-timeline>
        <EmptyState v-else title="暂无审批留痕" description="工单生成、审批、修改重跑的每一次动作都会记录在这里" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

/* 印章悬浮在单据卡右上角 */
.ticket-panel {
  position: relative;
  margin-bottom: 18px;
}

.ticket-seal {
  position: absolute;
  top: 18px;
  right: 26px;
  z-index: 1;
  pointer-events: none;
}

/* 金额对比条 */
.amount-strip {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 26px;
  margin-bottom: 18px;
  padding: 14px 18px;
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}

.amt {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.amt--side {
  margin-left: auto;
}

.amt-label {
  color: var(--ink-2);
  font-size: 12px;
}

.amt-num {
  font-family: var(--font-display);
  font-variant-numeric: tabular-nums;
  font-size: 26px;
  font-weight: 600;
  line-height: 1.1;
  color: var(--ink);
}

.amt-num--small {
  font-size: 18px;
}

.amt-arrow {
  color: var(--ink-3);
  font-size: 18px;
  padding-bottom: 4px;
}

.reason-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.reason-tag {
  max-width: 100%;
}

.items-table {
  margin-top: 16px;
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
  color: var(--ink-2);
  font-size: 13px;
}

.record-amount {
  color: var(--ink);
  font-size: 13px;
}

.record-comment {
  color: var(--ink-2);
  font-size: 13px;
}

.attachment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.att-card {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface);
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
  gap: 6px;
  margin-bottom: 8px;
}

.ocr-collapse {
  margin-bottom: 8px;
  border: none;
}

.ocr-collapse :deep(.el-collapse-item__header) {
  height: 30px;
  line-height: 30px;
  font-size: 12px;
  color: var(--ledger);
  border-bottom: none;
}

.att-actions {
  display: flex;
  gap: 8px;
}

.snap-collapse {
  margin-top: 6px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}

.snap-collapse :deep(.el-collapse-item__header) {
  height: 32px;
  line-height: 32px;
  font-size: 12px;
  color: var(--ink-2);
}

.snap-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.snap-box {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
}

.snap-empty {
  display: grid;
  place-items: center;
  min-height: 60px;
  color: var(--ink-3);
  font-size: 12px;
}

.snap-label {
  padding: 4px 8px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--line);
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 600;
}

@media (max-width: 768px) {
  .snap-grid {
    grid-template-columns: 1fr;
  }

  .amt--side {
    margin-left: 0;
  }
}
</style>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Back, Download, View } from '@element-plus/icons-vue'
import { getFileDownloadUrl } from '@/api/file'
import { getReimbursementDetail } from '@/api/reimbursement'
import { TASK_STATUS_MAP } from '@/utils/task'
import type { ReimbursementDetailVO } from '@/types'

const route = useRoute()
const router = useRouter()
const reimbId = Number(route.params.id)

const loading = ref(false)
const detail = ref<ReimbursementDetailVO | null>(null)

const reimb = computed(() => detail.value?.reimbursement)
const totalAmount = computed(() =>
  (detail.value?.items || []).reduce((sum, it) => sum + (Number(it.amount) || 0), 0),
)

async function load() {
  loading.value = true
  try {
    detail.value = await getReimbursementDetail(reimbId)
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
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
            <el-button v-if="reimb?.taskId" type="primary" @click="router.push(`/tasks/${reimb!.taskId}`)">
              查看审核任务
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

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadProgressEvent, UploadRequestOptions, UploadUserFile } from 'element-plus'
import { Back, Delete, Plus } from '@element-plus/icons-vue'
import { uploadFile } from '@/api/file'
import { getReimbursementDetail, resubmitReimbursement } from '@/api/reimbursement'
import type { ExpenseType, ReimbursementDetailVO, ReimbursementResubmitRequest } from '@/types'

const route = useRoute()
const router = useRouter()
const reimbId = Number(route.params.id)

interface ItemRow {
  name: string
  amount: number | undefined
  amountType: string
  quantity: number | undefined
  unitPrice: number | undefined
  date: string
  /** P2c 差旅/补贴评估字段（费用类型 TRAVEL 时展示） */
  city?: string
  hotelDays?: number
  hotelAmount?: number
  transportAmount?: number
  subsidyAmount?: number
}

const loading = ref(false)
const form = reactive({
  title: '',
  expenseType: 'TRAVEL' as ExpenseType,
  deptName: '',
  claimDate: '',
  remark: '',
})
const items = ref<ItemRow[]>([])
const fileList = ref<UploadUserFile[]>([])
const uploadedIds = ref<number[]>([])
const uidToRecordId = new Map<number, number>()
const uploading = ref(false)
const submitting = ref(false)

// 附件已绑定项：以 fileRecordId 作为业务引用（el-upload uid 用负 fileRecordId 合成占位）
function seedExisting(detail: ReimbursementDetailVO) {
  for (const att of detail.attachments || []) {
    const uid = -att.fileRecordId
    uploadedIds.value.push(att.fileRecordId)
    uidToRecordId.set(uid, att.fileRecordId)
    fileList.value.push({ uid, name: att.fileName || `file_${att.fileRecordId}`, url: att.url || undefined })
  }
}

async function load() {
  loading.value = true
  try {
    const detail = await getReimbursementDetail(reimbId)
    const reimb = detail.reimbursement
    form.title = reimb.title
    form.expenseType = reimb.expenseType
    form.deptName = reimb.deptName
    form.claimDate = reimb.claimDate
    form.remark = reimb.remark || ''
    items.value = (detail.items || []).map((it) => ({
      name: it.name,
      amount: Number(it.amount),
      amountType: it.amountType || '',
      quantity: it.quantity ?? undefined,
      unitPrice: it.unitPrice ?? undefined,
      date: it.date || '',
      city: it.city || '',
      hotelDays: it.hotelDays ?? undefined,
      hotelAmount: it.hotelAmount ?? undefined,
      transportAmount: it.transportAmount ?? undefined,
      subsidyAmount: it.subsidyAmount ?? undefined,
    }))
    seedExisting(detail)
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function addItem() {
  items.value.push({
    name: '',
    amount: undefined,
    amountType: '',
    quantity: undefined,
    unitPrice: undefined,
    date: '',
  })
}

function removeItem(index: number) {
  items.value.splice(index, 1)
}

async function doUpload(options: UploadRequestOptions) {
  const raw = options.file as File
  uploading.value = true
  try {
    const vo = await uploadFile(raw, (p) => options.onProgress({ percent: p } as UploadProgressEvent))
    uploadedIds.value.push(vo.id)
    uidToRecordId.set(options.file.uid, vo.id)
    options.onSuccess(vo)
  } catch (err) {
    options.onError(err as never)
  } finally {
    uploading.value = false
  }
}

function handleRemove(file: UploadFile) {
  const id = uidToRecordId.get(file.uid)
  if (id != null) {
    uploadedIds.value = uploadedIds.value.filter((x) => x !== id)
    uidToRecordId.delete(file.uid)
  }
}

function validate(): string | null {
  if (items.value.length === 0) return '请至少保留一条报销明细'
  for (const [i, it] of items.value.entries()) {
    if (!it.name.trim()) return `第 ${i + 1} 条明细：名称不能为空`
    if (it.amount == null || it.amount <= 0) return `第 ${i + 1} 条明细：金额必须大于 0`
  }
  if (uploadedIds.value.length === 0) return '请至少保留一个附件'
  return null
}

async function handleSubmit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  const body: ReimbursementResubmitRequest = {
    expenseType: form.expenseType,
    claimDate: form.claimDate,
    remark: form.remark.trim() || undefined,
    items: items.value.map((it) => ({
      name: it.name.trim(),
      amount: it.amount!,
      ...(it.amountType.trim() ? { amountType: it.amountType.trim() } : {}),
      ...(it.quantity != null ? { quantity: it.quantity } : {}),
      ...(it.unitPrice != null ? { unitPrice: it.unitPrice } : {}),
      ...(it.date ? { date: it.date } : {}),
      ...(it.city?.trim() ? { city: it.city.trim() } : {}),
      ...(it.hotelDays != null ? { hotelDays: it.hotelDays } : {}),
      ...(it.hotelAmount != null ? { hotelAmount: it.hotelAmount } : {}),
      ...(it.transportAmount != null ? { transportAmount: it.transportAmount } : {}),
      ...(it.subsidyAmount != null ? { subsidyAmount: it.subsidyAmount } : {}),
    })),
    fileRecordIds: uploadedIds.value,
  }
  submitting.value = true
  try {
    await resubmitReimbursement(reimbId, body)
    ElMessage.success('已保存修改，流水线重跑中')
    router.push(`/reimbursements/${reimbId}`)
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading || submitting" class="page-card">
    <template #header>
      <div class="page-header">
        <div>
          <div class="page-title card-title">修改明细并重跑</div>
          <div class="page-subtitle">
            待审批 / 已驳回状态下提交人可修改；标题与部门沿用原值不可改，保存后服务端按明细重算总额并回退流水线
          </div>
        </div>
        <div class="header-actions">
          <el-button :icon="Back" @click="router.back()">返回</el-button>
        </div>
      </div>
    </template>

    <el-alert type="warning" :closable="false" class="mb">
      重跑上限 3 次；重跑后再次命中复核将重新进入审批，自动通过则直接闭合工单。
    </el-alert>

    <el-form :model="form" label-width="90px">
      <el-row :gutter="16">
        <el-col :xs="24" :md="12">
          <el-form-item label="报销标题">
            <el-input :model-value="form.title" disabled />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-form-item label="部门">
            <el-input :model-value="form.deptName" disabled />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-form-item label="费用类型" required>
            <el-select v-model="form.expenseType" style="width: 100%">
              <el-option label="差旅" value="TRAVEL" />
              <el-option label="招待" value="ENTERTAINMENT" />
              <el-option label="办公" value="OFFICE" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-form-item label="报销日期" required>
            <el-date-picker
              v-model="form.claimDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
              placeholder="选择日期"
            />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="备注">
            <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="512" clearable />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <el-divider content-position="left">报销明细（总金额由服务端按明细求和）</el-divider>

    <div v-for="(it, index) in items" :key="index" class="item-block">
      <div class="item-row">
        <el-input v-model="it.name" placeholder="名称" style="width: 160px" />
        <el-input-number v-model="it.amount" :min="0.01" :precision="2" :controls="false" placeholder="金额" style="width: 140px" />
        <el-input v-model="it.amountType" placeholder="金额类型" style="width: 120px" />
        <el-input-number v-model="it.quantity" :min="0" :precision="0" :controls="false" placeholder="数量" style="width: 110px" />
        <el-input-number v-model="it.unitPrice" :min="0" :precision="2" :controls="false" placeholder="单价" style="width: 130px" />
        <el-date-picker v-model="it.date" type="date" value-format="YYYY-MM-DD" placeholder="发生日期" style="width: 150px" />
        <el-button :icon="Delete" circle plain type="danger" @click="removeItem(index)" />
      </div>
      <div v-if="form.expenseType === 'TRAVEL'" class="item-row travel-row">
        <span class="travel-label">差旅</span>
        <el-input v-model="it.city" placeholder="城市" style="width: 110px" />
        <el-input-number v-model="it.hotelDays" :min="0" :precision="0" :controls="false" placeholder="住宿天数" style="width: 120px" />
        <el-input-number v-model="it.hotelAmount" :min="0" :precision="2" :controls="false" placeholder="住宿金额" style="width: 130px" />
        <el-input-number v-model="it.transportAmount" :min="0" :precision="2" :controls="false" placeholder="交通金额" style="width: 130px" />
        <el-input-number v-model="it.subsidyAmount" :min="0" :precision="2" :controls="false" placeholder="补贴金额" style="width: 130px" />
      </div>
    </div>
    <el-button class="mb" :icon="Plus" @click="addItem">添加明细</el-button>

    <el-divider content-position="left">附件（移除的附件将解绑可复用，新增附件需上传）</el-divider>

    <el-upload
      v-model:file-list="fileList"
      :http-request="doUpload"
      :on-remove="handleRemove"
      :limit="5"
      :multiple="true"
      accept=".pdf,.png,.jpg,.jpeg,.webp"
      drag
      style="max-width: 480px"
    >
      <div v-loading="uploading" style="min-height: 80px">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽或<em>点击上传</em>（多文件）</div>
      </div>
    </el-upload>

    <div class="footer-actions">
      <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">保存并重跑</el-button>
      <el-button size="large" @click="router.back()">取消</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.card-title {
  font-size: 16px;
}

.mb {
  margin-bottom: 12px;
}

.item-block {
  margin-bottom: 10px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.travel-row {
  padding-left: 10px;
  border-left: 3px solid #e4e7ed;
}

.travel-label {
  color: #909399;
  font-size: 12px;
  width: 30px;
}

.footer-actions {
  margin-top: 20px;
  text-align: right;
}
</style>

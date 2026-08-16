<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadProgressEvent, UploadRequestOptions, UploadUserFile } from 'element-plus'
import { Back, Delete, Plus } from '@element-plus/icons-vue'
import { uploadFile } from '@/api/file'
import { submitReimbursement } from '@/api/reimbursement'
import type { ExpenseType, ReimbursementSubmitRequest } from '@/types'

const router = useRouter()

/** 内置模板：一键填充示例明细与基础字段（对齐 amount_verify 核验用例；差旅字段演示 P2c 差旅标准评估） */
const TEMPLATE_ITEMS: Array<{
  name: string
  amount: number
  amountType: string
  quantity: number
  unitPrice: number
  city?: string
  hotelDays?: number
  hotelAmount?: number
  transportAmount?: number
  subsidyAmount?: number
}> = [
  { name: '高铁票', amount: 553, amountType: '交通费', quantity: 1, unitPrice: 553, city: '北京', transportAmount: 553 },
  { name: '住宿费', amount: 458, amountType: '住宿费', quantity: 1, unitPrice: 458, city: '北京', hotelDays: 1, hotelAmount: 458 },
  { name: '打车费', amount: 50, amountType: '交通费', quantity: 2, unitPrice: 25, city: '北京', transportAmount: 50 },
]

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

const form = reactive({
  title: '',
  expenseType: 'TRAVEL' as ExpenseType,
  deptName: '',
  claimDate: new Date().toISOString().slice(0, 10),
  remark: '',
})
const items = ref<ItemRow[]>([])
const fileList = ref<UploadUserFile[]>([])
const uploading = ref(false)
const submitting = ref(false)

// 上传：el-upload 管理文件列表与进度，uploadFile 走 axios（带 token + 网关注入租户头）
const uploadedIds = ref<number[]>([])
const uidToRecordId = new Map<number, number>()

function fillTemplate() {
  if (!form.title) form.title = '差旅费报销审核'
  if (!form.deptName) form.deptName = '技术部'
  items.value = TEMPLATE_ITEMS.map((i) => ({ ...i, date: '' }))
  ElMessage.success('已填充内置模板，可继续编辑')
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
  if (!form.title.trim()) return '请输入报销标题'
  if (!form.expenseType) return '请选择费用类型'
  if (!form.deptName.trim()) return '请输入部门'
  if (!form.claimDate) return '请选择报销日期'
  if (items.value.length === 0) return '请至少添加一条报销明细（可用内置模板填充）'
  for (const [i, it] of items.value.entries()) {
    if (!it.name.trim()) return `第 ${i + 1} 条明细：名称不能为空`
    if (it.amount == null || it.amount <= 0) return `第 ${i + 1} 条明细：金额必须大于 0`
  }
  if (uploadedIds.value.length === 0) return '请至少上传一个附件'
  return null
}

async function handleSubmit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  const body: ReimbursementSubmitRequest = {
    title: form.title.trim(),
    expenseType: form.expenseType,
    deptName: form.deptName.trim(),
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
    const vo = await submitReimbursement(body)
    ElMessage.success(`报销单已提交：${vo.reimbNo}，审核任务 ${vo.taskId ?? ''}`)
    router.push(`/reimbursements/${vo.id}`)
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-card v-loading="submitting">
    <template #header>
      <div class="page-header">
        <span>提交报销单</span>
        <div>
          <el-button :icon="Back" @click="router.back()">返回</el-button>
          <el-button type="primary" @click="fillTemplate">内置模板</el-button>
        </div>
      </div>
    </template>

    <el-form :model="form" label-width="90px">
      <el-row :gutter="16">
        <el-col :xs="24" :md="12">
          <el-form-item label="报销标题" required>
            <el-input v-model="form.title" placeholder="如：差旅费报销审核" maxlength="128" clearable />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :md="12">
          <el-form-item label="部门" required>
            <el-input v-model="form.deptName" placeholder="如：技术部" maxlength="64" clearable />
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

    <el-divider content-position="left">附件（≤20MB，建议上传发票/行程单）</el-divider>

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
      <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">提交审核</el-button>
      <el-button size="large" @click="router.back()">取消</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
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

.mb {
  margin-bottom: 12px;
}

.footer-actions {
  margin-top: 20px;
  text-align: right;
}
</style>

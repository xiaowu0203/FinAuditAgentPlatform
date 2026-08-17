<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { createRule, getRules, publishRule, toggleRule, updateRule } from '@/api/rule'
import type { RuleType, RuleVO } from '@/types'

const loading = ref(false)
const records = ref<RuleVO[]>([])
const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)

/** 规则类型元数据：列表标签 + 表单按类型渲染结构化字段 */
const RULE_TYPE_META: Record<RuleType, { label: string; tag: 'primary' | 'warning' | 'success' | 'info' | 'danger' }> = {
  AMOUNT_LIMIT: { label: '大额限额', tag: 'danger' },
  REIMBURSE_EXPIRE: { label: '报销时效', tag: 'warning' },
  TRAVEL_STANDARD: { label: '差旅标准', tag: 'success' },
  SUBSIDY_LIMIT: { label: '补贴限额', tag: 'info' },
}

interface StandardRow {
  city: string
  hotelDaily: number | null
  transportTotal: number | null
}

const form = reactive({
  ruleCode: '',
  ruleName: '',
  ruleType: 'AMOUNT_LIMIT' as RuleType,
  enabled: 1,
  threshold: null as number | null,
  maxDays: null as number | null,
  dailyAmount: null as number | null,
  standards: [] as StandardRow[],
})

async function load() {
  loading.value = true
  try {
    records.value = await getRules()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function num(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

function resetForm() {
  form.enabled = 1
  form.threshold = null
  form.maxDays = null
  form.dailyAmount = null
  form.standards = []
}

function openCreate() {
  editingId.value = null
  resetForm()
  form.ruleCode = ''
  form.ruleName = ''
  form.ruleType = 'AMOUNT_LIMIT'
  dialogVisible.value = true
}

function openEdit(row: RuleVO) {
  editingId.value = row.id
  resetForm()
  form.ruleCode = row.ruleCode
  form.ruleName = row.ruleName
  form.ruleType = row.ruleType
  form.enabled = row.enabled ?? 1
  const cfg = row.ruleConfig || {}
  form.threshold = num(cfg.threshold)
  form.maxDays = num(cfg.maxDays)
  form.dailyAmount = num(cfg.dailyAmount)
  form.standards = Array.isArray(cfg.standards) ? (cfg.standards as StandardRow[]) : []
  dialogVisible.value = true
}

function addStandard() {
  form.standards.push({ city: '', hotelDaily: null, transportTotal: null })
}

function removeStandard(i: number) {
  form.standards.splice(i, 1)
}

function buildConfig(): Record<string, unknown> {
  switch (form.ruleType) {
    case 'AMOUNT_LIMIT':
      return { threshold: form.threshold }
    case 'REIMBURSE_EXPIRE':
      return { maxDays: form.maxDays }
    case 'SUBSIDY_LIMIT':
      return { dailyAmount: form.dailyAmount }
    case 'TRAVEL_STANDARD':
      return { standards: form.standards }
    default:
      return {}
  }
}

function validate(): string | null {
  if (!form.ruleName.trim()) return '请填写规则名称'
  if (editingId.value === null && !form.ruleCode.trim()) return '请填写规则编码'
  if (form.ruleType === 'AMOUNT_LIMIT' && (form.threshold === null || form.threshold <= 0))
    return '请填写大于 0 的限额阈值'
  if (form.ruleType === 'REIMBURSE_EXPIRE' && (form.maxDays === null || form.maxDays <= 0))
    return '请填写大于 0 的时效天数'
  if (form.ruleType === 'SUBSIDY_LIMIT' && (form.dailyAmount === null || form.dailyAmount <= 0))
    return '请填写大于 0 的单日补贴上限'
  if (form.ruleType === 'TRAVEL_STANDARD') {
    if (form.standards.length === 0) return '请至少添加一条城市标准'
    if (form.standards.some((s) => !s.city.trim() || s.hotelDaily === null || s.transportTotal === null))
      return '城市标准需填写完整（城市 / 住宿标准 / 交通标准）'
  }
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  submitting.value = true
  try {
    const payload = {
      ruleCode: form.ruleCode.trim(),
      ruleName: form.ruleName.trim(),
      ruleType: form.ruleType,
      ruleConfig: buildConfig(),
      enabled: form.enabled,
    }
    if (editingId.value === null) {
      await createRule(payload)
      ElMessage.success('已新增（草稿），发布后生效')
    } else {
      await updateRule(editingId.value, payload)
      ElMessage.success('已保存（草稿），重新发布后生效')
    }
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function onToggle(row: RuleVO) {
  try {
    await toggleRule(row.id)
    ElMessage.success(`已${row.enabled === 1 ? '停用' : '启用'}（草稿），发布后生效`)
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function onPublish(row: RuleVO) {
  try {
    await ElMessageBox.confirm(
      `发布后「${row.ruleName}」及同租户其他已发布规则将立即生效（不重启服务），是否继续？`,
      '发布规则',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' },
    )
    await publishRule(row.id)
    ElMessage.success('已发布，生效集已同步 Nacos')
    await load()
  } catch {
    // 取消或失败：拦截器已提示
  }
}

onMounted(() => load())
</script>

<template>
  <el-card class="page-card">
    <template #header>
      <div class="list-header">
        <div>
          <div class="page-title card-title">财务规则配置</div>
          <div class="page-subtitle">结构化维护审核规则，草稿保存后可按需发布生效</div>
        </div>
        <div class="filters">
          <el-tooltip content="发布后立即生效，无需重启服务" placement="top">
            <span class="tip">⚡ 改规则不重启</span>
          </el-tooltip>
          <el-button type="primary" :icon="Plus" @click="openCreate">新增规则</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="records" empty-text="暂无规则">
      <el-table-column prop="ruleCode" label="规则编码" min-width="140" show-overflow-tooltip />
      <el-table-column prop="ruleName" label="规则名称" min-width="120" show-overflow-tooltip />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="RULE_TYPE_META[row.ruleType as RuleType].tag">
            {{ RULE_TYPE_META[row.ruleType as RuleType].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生效状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.published === 1 ? 'success' : 'info'" effect="plain">
            {{ row.published === 1 ? '已发布生效' : '草稿待发布' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启停" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'danger'">
            {{ row.enabled === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="80" />
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="row.published !== 1"
            link
            type="warning"
            size="small"
            @click="onPublish(row)"
          >
            发布
          </el-button>
          <el-button v-else link type="success" size="small" @click="onPublish(row)">重新发布</el-button>
          <el-button
            link
            :type="row.enabled === 1 ? 'danger' : 'success'"
            size="small"
            @click="onToggle(row)"
          >
            {{ row.enabled === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog
    v-model="dialogVisible"
    :title="editingId === null ? '新增规则' : '编辑规则'"
    width="560px"
    :close-on-click-modal="false"
  >
    <el-form label-width="100px">
      <el-form-item label="规则编码">
        <el-input v-model="form.ruleCode" placeholder="如 amount_limit" :disabled="editingId !== null" />
      </el-form-item>
      <el-form-item label="规则名称">
        <el-input v-model="form.ruleName" placeholder="如 大额报销限额" />
      </el-form-item>
      <el-form-item label="规则类型">
        <el-select v-model="form.ruleType" style="width: 100%" :disabled="editingId !== null">
          <el-option
            v-for="(meta, key) in RULE_TYPE_META"
            :key="key"
            :label="meta.label"
            :value="key"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="启停">
        <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" active-text="启用" inactive-text="停用" />
      </el-form-item>

      <!-- 大额限额：threshold -->
      <el-form-item v-if="form.ruleType === 'AMOUNT_LIMIT'" label="限额阈值">
        <el-input-number v-model="form.threshold" :min="0" :precision="2" :step="500" style="width: 100%" placeholder="申报总额超过即命中" />
        <div class="field-tip">申报总额超过该值即判定大额超标</div>
      </el-form-item>

      <!-- 报销时效：maxDays -->
      <el-form-item v-if="form.ruleType === 'REIMBURSE_EXPIRE'" label="时效天数">
        <el-input-number v-model="form.maxDays" :min="1" :precision="0" style="width: 100%" placeholder="报销日往前 N 天" />
        <div class="field-tip">明细发生日期早于「报销日 - N 天」即判定超时效</div>
      </el-form-item>

      <!-- 补贴限额：dailyAmount -->
      <el-form-item v-if="form.ruleType === 'SUBSIDY_LIMIT'" label="单日上限">
        <el-input-number v-model="form.dailyAmount" :min="0" :precision="2" :step="50" style="width: 100%" placeholder="补贴单日上限" />
        <div class="field-tip">单日补贴（subsidyAmount / hotelDays）超过该值即命中</div>
      </el-form-item>

      <!-- 差旅标准：城市标准动态行 -->
      <el-form-item v-if="form.ruleType === 'TRAVEL_STANDARD'" label="城市标准">
        <div class="standard-list">
          <div v-for="(s, i) in form.standards" :key="i" class="standard-row">
            <el-input v-model="s.city" placeholder="城市" style="width: 120px" />
            <el-input-number v-model="s.hotelDaily" :min="0" :precision="2" :step="50" placeholder="住宿标准/晚" style="width: 160px" />
            <el-input-number v-model="s.transportTotal" :min="0" :precision="2" :step="200" placeholder="交通标准" style="width: 140px" />
            <el-button link type="danger" @click="removeStandard(i)">删除</el-button>
          </div>
          <el-button size="small" @click="addStandard">+ 添加城市标准</el-button>
          <div class="field-tip">住宿：明细住宿金额 / 天数 &gt; 标准即命中；交通：明细交通金额 &gt; 标准即命中</div>
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.card-title {
  font-size: 16px;
}

.tip {
  color: #67c23a;
  font-size: 13px;
}

.field-tip {
  width: 100%;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}

.standard-list {
  width: 100%;
}

.standard-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
</style>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue'
import { createDept, deleteDept, getDeptTree, updateDept } from '@/api/system'
import type { DeptVO } from '@/types'

const loading = ref(false)
const treeData = ref<DeptVO[]>([])
const selected = ref<DeptVO | null>(null)
const keyword = ref('')
/** 高亮的 node-key（el-tree current-node-key） */
const currentKey = ref<number | null>(null)

const total = computed(() => countNodes(treeData.value))
const filteredTree = computed<DeptVO[]>(() => {
  const kw = keyword.value.trim()
  if (!kw) return treeData.value
  return treeData.value.map(fitNode).filter(Boolean) as DeptVO[]
})
function countNodes(nodes: DeptVO[]): number {
  return nodes.reduce((sum, n) => sum + 1 + countNodes(n.children || []), 0)
}
function fitNode(n: DeptVO): DeptVO | null {
  const children = (n.children || []).map(fitNode).filter(Boolean) as DeptVO[]
  if (n.deptName.toLowerCase().includes(keyword.value.trim().toLowerCase()) || children.length > 0) {
    return { ...n, children }
  }
  return null
}

async function load() {
  loading.value = true
  try {
    treeData.value = await getDeptTree()
    // 选中节点失效后回落
    if (selected.value) {
      const fresh = findById(treeData.value, selected.value.id)
      selected.value = fresh ?? null
      currentKey.value = fresh?.id ?? null
    }
  } finally {
    loading.value = false
  }
}
function findById(nodes: DeptVO[], id: number): DeptVO | null {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = findById(n.children || [], id)
    if (hit) return hit
  }
  return null
}
onMounted(load)

function handleNodeClick(node: DeptVO) {
  selected.value = node
  currentKey.value = node.id
}

const childCount = computed(() => selected.value?.children?.length ?? 0)
const parentLabel = computed(() => {
  if (!selected.value) return ''
  if (!selected.value.parentId) return '根部门'
  const p = findById(treeData.value, selected.value.parentId)
  return p ? p.deptName : `#${selected.value.parentId}`
})
/** 可选父部门：全部节点，但排除「自身 + 自身以下整棵子树」（不可把部门挂到自己/子孙下；后端防环兜底）
 *  以扁平缩进列表呈现（相比 el-tree-select：标量 id→label 回显绝对可靠） */
const parentOptions = computed<{ value: number; label: string }[]>(() => {
  const options: { value: number; label: string }[] = [{ value: 0, label: '根部门' }]
  const selfId = editingId.value
  const collect = (nodes: DeptVO[], depth: number) => {
    for (const n of nodes) {
      if (selfId != null && isSelfOrDescendant(n, selfId)) continue
      options.push({ value: n.id, label: `${'　'.repeat(depth)}${n.deptName}` })
      collect(n.children || [], depth + 1)
    }
  }
  collect(treeData.value, 1)
  return options
})
function isSelfOrDescendant(n: DeptVO, selfId: number): boolean {
  return n.id === selfId || (n.children || []).some((c) => isSelfOrDescendant(c, selfId))
}

const dialog = ref(false)
const editingId = ref<number | null>(null)
const creatingParent = ref(0 as number)
const saving = ref(false)
const form = ref({ deptName: '', parentId: 0 as number | null, status: 1 })

function openCreateRoot() {
  editingId.value = null
  creatingParent.value = 0
  Object.assign(form.value, { deptName: '', parentId: 0, status: 1 })
  dialog.value = true
}
function openCreateChild() {
  if (!selected.value) {
    ElMessage.warning('请先在左侧选择父部门')
    return
  }
  editingId.value = null
  creatingParent.value = selected.value.id
  Object.assign(form.value, { deptName: '', parentId: selected.value.id, status: 1 })
  dialog.value = true
}
function openEdit() {
  if (!selected.value) {
    ElMessage.warning('请先选择要编辑的部门')
    return
  }
  const node = selected.value
  editingId.value = node.id
  Object.assign(form.value, { deptName: node.deptName, parentId: node.parentId, status: node.status })
  dialog.value = true
}

async function handleSave() {
  const name = form.value.deptName.trim()
  if (!name) {
    ElMessage.warning('部门名称不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createDept({ deptName: name, parentId: form.value.parentId ?? 0 })
      ElMessage.success('部门已创建')
    } else {
      await updateDept(editingId.value, { deptName: name, parentId: form.value.parentId ?? 0, status: form.value.status })
      ElMessage.success('部门已更新')
    }
    dialog.value = false
    await load()
  } catch {
    /* 拦截器已提示（防环/重名/父不存在由后端拦截） */
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  if (!selected.value) {
    ElMessage.warning('请先选择要删除的部门')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除部门「${selected.value.deptName}」？有子部门或绑定用户的部门将被拒绝删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await deleteDept(selected.value.id)
  ElMessage.success('已删除')
  selected.value = null
  currentKey.value = null
  await load()
}
</script>

<template>
  <div class="page-shell">
    <el-card class="page-card">
      <template #header>
        <div class="page-header">
          <div>
            <div class="page-title card-title">部门管理</div>
            <div class="page-subtitle">部门树为报销单选择器与用户管理公用数据；租户内名称唯一</div>
          </div>
          <div class="filters">
            <el-input v-model="keyword" class="tree-search" placeholder="搜索部门" :prefix-icon="Search" clearable />
            <el-button type="primary" plain :icon="Plus" v-perm="'dept:create'" @click="openCreateRoot">
              新增根部门
            </el-button>
            <el-button type="primary" :icon="Plus" v-perm="'dept:create'" :disabled="!selected" @click="openCreateChild">
              新增子部门
            </el-button>
          </div>
        </div>
      </template>

      <div class="dept-layout">
        <!-- 左：部门树 -->
        <section class="soft-panel tree-panel">
          <header class="panel-head">
            <span class="panel-title">部门树</span>
            <el-tag size="small" effect="plain" round>{{ total }} 个</el-tag>
          </header>
          <div v-loading="loading" class="tree-body">
            <el-tree
              v-if="filteredTree.length"
              :data="filteredTree"
              :props="{ label: 'deptName', children: 'children' }"
              node-key="id"
              :current-node-key="currentKey"
              highlight-current
              default-expand-all
              :expand-on-click-node="false"
              class="dept-tree"
              @node-click="handleNodeClick"
            >
              <template #default="{ data }">
                <span class="tree-node">
                  <span class="tree-node-name">{{ data.deptName }}</span>
                  <span v-if="(data.children || []).length" class="child-badge">{{ data.children.length }}</span>
                  <el-tag v-if="data.status !== 1" size="small" type="info" effect="plain" class="off-tag">停用</el-tag>
                </span>
              </template>
            </el-tree>
            <div v-else class="tree-empty">
              <el-empty :description="keyword ? '无匹配部门' : '暂无部门'" :image-size="72" />
            </div>
          </div>
        </section>

        <!-- 右：选中部门详情 -->
        <section class="detail-panel">
          <template v-if="selected">
            <div class="dept-hero">
              <div class="dept-hero-icon">🏛️</div>
              <div class="dept-hero-info">
                <div class="dept-hero-name">
                  {{ selected.deptName }}
                  <el-tag :type="selected.status === 1 ? 'success' : 'info'" size="small" effect="light" round>
                    {{ selected.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </div>
                <div class="dept-hero-sub">ID {{ selected.id }} · {{ parentLabel }}</div>
              </div>
            </div>

            <div class="metric-row">
              <div class="metric">
                <div class="metric-value">{{ childCount }}</div>
                <div class="metric-label">直接子部门</div>
              </div>
              <div class="metric">
                <div class="metric-value">{{ parentLabel }}</div>
                <div class="metric-label">上级部门</div>
              </div>
              <div class="metric">
                <div class="metric-value">{{ selected.status === 1 ? '启用' : '停用' }}</div>
                <div class="metric-label">状态</div>
              </div>
            </div>

            <div class="detail-actions">
              <el-button type="primary" :icon="Edit" v-perm="'dept:update'" @click="openEdit">编辑部门</el-button>
              <el-button
                type="primary"
                plain
                :icon="Plus"
                v-perm="'dept:create'"
                @click="openCreateChild"
              >
                添加子部门
              </el-button>
              <el-button type="danger" plain :icon="Delete" v-perm="'dept:delete'" @click="handleDelete">删除</el-button>
            </div>
            <div class="detail-tip">删除受引用约束：有子部门或绑定用户的部门会被后端拒绝。</div>
          </template>

          <div v-else class="detail-empty">
            <el-empty description="点击左侧部门查看详情" :image-size="88" />
          </div>
        </section>
      </div>
    </el-card>
  </div>

  <el-dialog v-model="dialog" :title="editingId == null ? `新增部门（${creatingParent ? '子部门' : '根部门'}）` : '编辑部门'" width="460px" :close-on-click-modal="false" align-center>
    <el-form label-width="92px" class="dept-form">
      <el-form-item label="部门名称" required>
        <el-input v-model="form.deptName" maxlength="64" placeholder="租户内唯一" />
      </el-form-item>
      <el-form-item label="上级部门">
        <el-select v-model="form.parentId" filterable clearable style="width: 100%" placeholder="移到根，或选择新的上级">
          <el-option v-for="o in parentOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
        <div class="field-tip">上级不能是自己的子部门（后端防环拦截）</div>
      </el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="form.status">
          <el-radio-button :value="1">启用</el-radio-button>
          <el-radio-button :value="0">停用</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.card-title {
  font-size: 16px;
}
.tree-search {
  width: 200px;
}

/* 双栏布局 */
.dept-layout {
  display: grid;
  grid-template-columns: minmax(280px, 340px) 1fr;
  gap: 18px;
}
.tree-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--border-soft);
}
.panel-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-2);
  letter-spacing: 0.04em;
}
.tree-body {
  height: 520px;
  overflow: auto;
  padding: 10px 8px;
}
.tree-empty {
  padding-top: 40px;
}

/* 树节点 */
.dept-tree {
  --el-tree-node-hover-bg-color: rgba(37, 99, 235, 0.05);
}
.dept-tree :deep(.el-tree-node__content) {
  height: 38px;
  border-radius: 12px;
  margin-bottom: 2px;
}
.dept-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.12), rgba(79, 70, 229, 0.1));
  box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.18);
}
.tree-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.tree-node-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.child-badge {
  min-width: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(100, 116, 139, 0.14);
  color: var(--text-2);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.off-tag {
  transform: scale(0.9);
}

/* 右栏 */
.detail-panel {
  min-height: 520px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-soft);
  border-radius: 18px;
  background:
    radial-gradient(circle at right top, rgba(96, 165, 250, 0.1), transparent 34%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.96) 0%, #f8fbff 100%);
  padding: 22px 24px 18px;
}
.dept-hero {
  display: flex;
  align-items: center;
  gap: 14px;
}
.dept-hero-icon {
  display: grid;
  width: 52px;
  height: 52px;
  place-items: center;
  border-radius: 16px;
  background: linear-gradient(135deg, #eff6ff, #eef2ff);
  box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.14);
  font-size: 24px;
}
.dept-hero-name {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 20px;
  font-weight: 800;
}
.dept-hero-sub {
  margin-top: 3px;
  color: var(--text-2);
  font-size: 13px;
}
.metric-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 20px;
}
.metric {
  padding: 14px 16px;
  border: 1px solid var(--border-soft);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.8);
}
.metric-value {
  font-size: 18px;
  font-weight: 800;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.metric-label {
  margin-top: 3px;
  color: var(--text-2);
  font-size: 12px;
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: auto;
  padding-top: 20px;
}
.detail-tip {
  margin-top: 12px;
  color: #94a3b8;
  font-size: 12px;
}
.detail-empty {
  flex: 1;
  display: grid;
  place-items: center;
}

.field-tip {
  width: 100%;
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
}

@media (max-width: 960px) {
  .dept-layout {
    grid-template-columns: 1fr;
  }
  .tree-body {
    height: 320px;
  }
}
</style>
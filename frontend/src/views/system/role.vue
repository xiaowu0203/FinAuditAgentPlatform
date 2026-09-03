<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Key, Plus } from '@element-plus/icons-vue'
import { assignRolePerms, createRole, deleteRole, getPermissions, getRolePermIds, getRoles, updateRole } from '@/api/system'
import type { PermissionVO, RoleVO } from '@/types'

const loading = ref(false)
const rows = ref<RoleVO[]>([])
const perms = ref<PermissionVO[]>([])

const roleDialog = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const roleForm = reactive({ roleCode: '', roleName: '' })

const permDialog = ref(false)
const permRoleId = ref<number | null>(null)
const permRoleName = ref('')
const checkedPermIds = ref<number[]>([])
const permSaving = ref(false)

/** 内置系统角色（种子角色不可删，仅展示标记） */
const SYSTEM_ROLES = new Set(['admin', 'auditor'])

const permGroups = computed(() => {
  const map = new Map<string, PermissionVO[]>()
  for (const p of perms.value) {
    const list = map.get(p.groupName) ?? []
    list.push(p)
    map.set(p.groupName, list)
  }
  return [...map.entries()]
})

const groupMeta: Record<string, { color: string; emoji: string }> = {
  系统管理: { color: 'linear-gradient(135deg,#2563eb,#7c3aed)', emoji: '🛠️' },
  财务业务: { color: 'linear-gradient(135deg,#0ea5e9,#2563eb)', emoji: '💰' },
  预留: { color: 'linear-gradient(135deg,#94a3b8,#64748b)', emoji: '🔮' },
}

async function load() {
  loading.value = true
  try {
    rows.value = await getRoles()
  } finally {
    loading.value = false
  }
}

async function init() {
  await Promise.all([load(), getPermissions().then((r) => (perms.value = r))])
}

onMounted(init)

function openCreate() {
  editingId.value = null
  Object.assign(roleForm, { roleCode: '', roleName: '' })
  roleDialog.value = true
}

function openEdit(row: RoleVO) {
  editingId.value = row.id
  Object.assign(roleForm, { roleCode: row.roleCode, roleName: row.roleName })
  roleDialog.value = true
}

async function handleSaveRole() {
  if (!roleForm.roleCode.trim() || !roleForm.roleName.trim()) {
    ElMessage.warning('角色编码与名称必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createRole({ roleCode: roleForm.roleCode.trim(), roleName: roleForm.roleName.trim() })
      ElMessage.success('角色已创建')
    } else {
      await updateRole(editingId.value, { roleName: roleForm.roleName.trim() })
      ElMessage.success('角色已更新')
    }
    roleDialog.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function openPerm(row: RoleVO) {
  permRoleId.value = row.id
  permRoleName.value = row.roleName
  checkedPermIds.value = await getRolePermIds(row.id)
  permDialog.value = true
}

async function handleSavePerm() {
  if (permRoleId.value == null) return
  permSaving.value = true
  try {
    await assignRolePerms(permRoleId.value, { permIds: checkedPermIds.value })
    ElMessage.success('权限已更新，该角色在线用户下一请求即生效（无需重新登录）')
    permDialog.value = false
  } catch {
    /* 拦截器已提示 */
  } finally {
    permSaving.value = false
  }
}

function toggleGroup(group: string) {
  const ids = permGroups.value.find(([g]) => g === group)?.[1].map((p) => p.id) ?? []
  const allChecked = ids.length > 0 && ids.every((id) => checkedPermIds.value.includes(id))
  if (allChecked) {
    checkedPermIds.value = checkedPermIds.value.filter((id) => !ids.includes(id))
  } else {
    checkedPermIds.value = [...new Set([...checkedPermIds.value, ...ids])]
  }
}

function groupAllChecked(group: string): boolean {
  const ids = permGroups.value.find(([g]) => g === group)?.[1].map((p) => p.id) ?? []
  return ids.length > 0 && ids.every((id) => checkedPermIds.value.includes(id))
}

async function handleDelete(row: RoleVO) {
  if (SYSTEM_ROLES.has(row.roleCode)) {
    ElMessage.warning('内置系统角色不可删除')
    return
  }
  await ElMessageBox.confirm(
    `确认删除角色「${row.roleName}」？内含该角色的用户将被移出该角色。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await deleteRole(row.id)
  ElMessage.success('已删除')
  await load()
}
</script>

<template>
  <el-card v-loading="loading" class="page-card">
    <template #header>
      <div class="page-header">
        <div>
          <div class="page-title card-title">角色管理</div>
          <div class="page-subtitle">角色是权限的分配单位；权限变更后在线用户下一请求即生效</div>
        </div>
        <div class="filters">
          <el-button type="primary" :icon="Plus" v-perm="'role:create'" @click="openCreate">新增角色</el-button>
        </div>
      </div>
    </template>

    <el-table :data="rows" empty-text="暂无角色">
      <el-table-column label="角色" min-width="220">
        <template #default="{ row }">
          <div class="role-cell">
            <span class="role-badge">{{ (row.roleName || 'R').slice(0, 1) }}</span>
            <div class="role-meta">
              <span class="role-name">
                {{ row.roleName }}
                <el-tag v-if="SYSTEM_ROLES.has(row.roleCode)" size="small" type="info" effect="plain" round>系统</el-tag>
              </span>
              <span class="role-code">{{ row.roleCode }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="170">
        <template #default="{ row }">{{ row.createdAt || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" v-perm="'role:assign-perm'" :icon="Key" @click="openPerm(row)">
            权限分配
          </el-button>
          <el-button link type="primary" size="small" v-perm="'role:update'" :icon="Edit" @click="openEdit(row)">
            编辑
          </el-button>
          <el-button link type="danger" size="small" v-perm="'role:delete'" :icon="Delete" @click="handleDelete(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog
    v-model="roleDialog"
    :title="editingId == null ? '新增角色' : '编辑角色'"
    width="440px"
    :close-on-click-modal="false"
    align-center
  >
    <el-form label-width="90px">
      <el-form-item label="编码" required>
        <el-input v-model="roleForm.roleCode" :disabled="editingId != null" maxlength="32" placeholder="如 auditor，小写唯一" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="roleForm.roleName" maxlength="64" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="roleDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSaveRole">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="permDialog"
    :title="`权限分配 · ${permRoleName}`"
    width="620px"
    :close-on-click-modal="false"
    align-center
    class="perm-dialog"
  >
    <div class="perm-scroll">
      <section v-for="[group, items] in permGroups" :key="group" class="perm-tile">
        <header class="perm-tile-head">
          <span class="perm-emoji">{{ groupMeta[group]?.emoji ?? '📦' }}</span>
          <span class="perm-tile-title">{{ group }}</span>
          <el-checkbox
            :model-value="groupAllChecked(group)"
            class="perm-select-all"
            @change="toggleGroup(group)"
          >
            全选
          </el-checkbox>
        </header>
        <div class="perm-checks">
          <el-checkbox v-for="p in items" :key="p.id" v-model="checkedPermIds" :value="p.id" border class="perm-check">
            {{ p.permName }}
            <span class="perm-code">{{ p.permCode }}</span>
          </el-checkbox>
        </div>
      </section>
    </div>
    <template #footer>
      <div class="perm-footer">
        <span class="perm-footer-tip">替换式保存：以勾选列表为准，权限变更对在线用户实时生效</span>
        <div>
          <el-button @click="permDialog = false">取消</el-button>
          <el-button type="primary" :loading="permSaving" @click="handleSavePerm">保存</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.card-title {
  font-size: 16px;
}

.role-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}
.role-badge {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 12px;
  background: linear-gradient(135deg, #0ea5e9, #2563eb);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 6px 14px rgba(14, 165, 233, 0.22);
}
.role-meta {
  display: flex;
  flex-direction: column;
}
.role-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: var(--text-1);
}
.role-code {
  color: var(--text-2);
  font-size: 12px;
}

/* 权限分配 */
.perm-dialog :deep(.el-dialog__body) {
  max-height: 66vh;
  overflow-y: auto;
}
.perm-scroll {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 4px;
}
.perm-tile {
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96), #f8fbff);
}
.perm-tile-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-soft);
}
.perm-emoji {
  font-size: 16px;
}
.perm-tile-title {
  font-size: 14px;
  font-weight: 700;
}
.perm-select-all {
  margin-left: auto;
}
.perm-checks {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
  padding: 14px 16px;
}
.perm-check {
  width: 100%;
  margin: 0;
  border-radius: 12px;
}
.perm-code {
  color: var(--text-2);
  font-size: 11px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
.perm-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.perm-footer-tip {
  color: var(--text-2);
  font-size: 12px;
}

@media (max-width: 768px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
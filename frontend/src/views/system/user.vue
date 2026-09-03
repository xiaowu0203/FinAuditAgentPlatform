<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue'
import { assignUserRoles, createUser, deleteUser, getDeptTree, getRoles, getUsers, updateUser } from '@/api/system'
import type { DeptVO, RoleVO, SystemUserVO } from '@/types'

const loading = ref(false)
const rows = ref<SystemUserVO[]>([])
const total = ref(0)
const keyword = ref('')
const roles = ref<RoleVO[]>([])
const deptTree = ref<DeptVO[]>([])

const pageNum = ref(1)
const pageSize = ref(10)

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({
  username: '',
  password: '',
  realName: '',
  phone: '',
  deptId: null as number | null,
  status: 1,
  roleIds: [] as number[],
})

/** 部门树 → 扁平缩进选项（el-select 标量 id→label 回显可靠；取代 el-tree-select） */
const deptOptions = computed<{ value: number; label: string }[]>(() => {
  const options: { value: number; label: string }[] = []
  const walk = (nodes: DeptVO[], depth: number) => {
    for (const n of nodes) {
      options.push({ value: n.id, label: `${'　'.repeat(depth)}${n.deptName}` })
      walk(n.children || [], depth + 1)
    }
  }
  walk(deptTree.value, 0)
  return options
})
function avatarOf(u: SystemUserVO): string {
  return (u.realName || u.username || 'U').slice(0, 1).toUpperCase()
}

async function load() {
  loading.value = true
  try {
    const page = await getUsers({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value || undefined })
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function init() {
  await Promise.all([load(), getRoles().then((r) => (roles.value = r)), getDeptTree().then((t) => (deptTree.value = t))])
}

onMounted(init)

function openCreate() {
  editingId.value = null
  Object.assign(form, { username: '', password: '', realName: '', phone: '', deptId: null, status: 1, roleIds: [] })
  dialogVisible.value = true
}

function openEdit(row: SystemUserVO) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    password: '',
    realName: row.realName ?? '',
    phone: row.phone ?? '',
    deptId: row.deptId,
    status: row.status,
    roleIds: [],
  })
  dialogVisible.value = true
}

async function handleSave() {
  if (editingId.value == null && (!form.username.trim() || !form.password)) {
    ElMessage.warning('用户名与初始密码必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createUser({
        username: form.username.trim(),
        password: form.password,
        realName: form.realName.trim() || undefined,
        phone: form.phone.trim() || undefined,
        deptId: form.deptId,
        status: form.status,
        roleIds: form.roleIds,
      })
      ElMessage.success('用户已创建')
    } else {
      const id = editingId.value
      await updateUser(id, {
        realName: form.realName.trim() || undefined,
        phone: form.phone.trim() || undefined,
        deptId: form.deptId ?? 0,
        status: form.status,
        password: form.password || undefined,
      })
      await assignUserRoles(id, { roleIds: form.roleIds })
      ElMessage.success('用户已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: SystemUserVO) {
  await ElMessageBox.confirm(
    `确认删除用户「${row.username}」？删除后其全部会话与权限即时失效。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await deleteUser(row.id)
  ElMessage.success('已删除')
  await load()
}

function handleSearch() {
  pageNum.value = 1
  load()
}
</script>

<template>
  <div v-loading="loading">
    <div class="page-head">
      <div>
        <div class="page-head-title">用户管理</div>
        <div class="page-head-sub">新增/编辑用户（部门绑定 + 角色分配），变更即时生效、无需重新登录</div>
      </div>
      <div class="page-head-actions">
        <el-input
          v-model="keyword"
          class="search-input"
          placeholder="用户名 / 姓名 / 手机号"
          :prefix-icon="Search"
          clearable
          @keyup.enter="handleSearch"
        />
        <el-button @click="handleSearch">查询</el-button>
        <el-button type="primary" :icon="Plus" v-perm="'user:create'" @click="openCreate">新增用户</el-button>
      </div>
    </div>

    <el-table :data="rows" class="ledger-table">
      <el-table-column label="用户" min-width="200">
        <template #default="{ row }">
          <div class="user-cell">
            <span class="avatar">{{ avatarOf(row as SystemUserVO) }}</span>
            <div class="user-meta2">
              <span class="user-name">{{ row.realName || row.username }}</span>
              <span class="user-login">@{{ row.username }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="部门" min-width="130">
        <template #default="{ row }">
          <span v-if="row.deptName" class="stamp stamp--success">{{ row.deptName }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号" min-width="130">
        <template #default="{ row }">{{ row.phone || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <span class="stamp" :class="row.status === 1 ? 'stamp--success' : 'stamp--danger'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" v-perm="'user:update'" :icon="Edit" @click="openEdit(row as SystemUserVO)">
            编辑
          </el-button>
          <el-button link type="danger" size="small" v-perm="'user:delete'" :icon="Delete" @click="handleDelete(row as SystemUserVO)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="load"
      />
    </div>
  </div>

  <el-dialog
    v-model="dialogVisible"
    :title="editingId == null ? '新增用户' : '编辑用户'"
    width="560px"
    :close-on-click-modal="false"
    align-center
  >
    <el-form label-width="92px" class="user-form">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="登录名" required>
            <el-input v-model="form.username" :disabled="editingId != null" maxlength="64" placeholder="唯一登录名" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item :label="editingId == null ? '初始密码' : '重置密码'" :required="editingId == null">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              maxlength="32"
              :placeholder="editingId == null ? '6-32 位' : '留空则不修改'"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="姓名">
            <el-input v-model="form.realName" maxlength="64" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="手机号">
            <el-input v-model="form.phone" maxlength="20" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="部门">
        <el-select v-model="form.deptId" filterable clearable style="width: 100%" placeholder="选择部门（可清空解绑）">
          <el-option v-for="o in deptOptions" :key="o.value" :value="o.value" :label="o.label" />
        </el-select>
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.roleIds" multiple clearable style="width: 100%" placeholder="可多选">
          <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-radio-group v-model="form.status">
          <el-radio-button :value="1">启用</el-radio-button>
          <el-radio-button :value="0">禁用</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
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
.search-input {
  width: 240px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}
.avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--ledger);
  border-radius: var(--radius-sm);
  color: var(--ledger);
  font-size: 14px;
  font-weight: 600;
  transform: rotate(-4deg);
}
.user-meta2 {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.user-name {
  font-weight: 600;
  color: var(--ink);
}
.user-login {
  color: var(--ink-2);
  font-size: 12px;
}

@media (max-width: 768px) {
  .search-input {
    width: 100%;
  }
}
</style>
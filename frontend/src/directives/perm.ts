import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

function check(el: HTMLElement, value: string | string[]) {
  const auth = useAuthStore()
  const codes = Array.isArray(value) ? value : [value]
  if (!auth.hasAnyPerm(codes)) {
    el.parentNode?.removeChild(el)
  }
}

/**
 * v-perm 指令：无权限时从 DOM 移除元素（按钮级动态渲染）。
 * 用法：`<el-button v-perm="'user:delete'">删除</el-button>`；支持数组任一命中 `<el-button v-perm="['audit:viewAll','audit:approve']">`。
 * <p>仅展示层收敛；后端 @RequirePerm 为最终裁决。权限缺失时审批菜单隐藏、直连路由被守卫拦回。</p>
 * <p>updated 钩子（P3.5d）：403 触发 refreshPerms 权限实时收敛后，已挂载元素在父级重渲染
 * （列表刷新/弹窗重开）时按新权限重新判定；已被移除的元素无法凭空恢复，属预期行为。</p>
 */
export const perm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    check(el, binding.value)
  },
  updated(el, binding) {
    check(el, binding.value)
  },
}

export default perm

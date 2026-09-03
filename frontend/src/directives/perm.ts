import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * v-perm 指令：无权限时从 DOM 移除元素（按钮级动态渲染）。
 * 用法：`<el-button v-perm="'user:delete'">删除</el-button>`；支持数组任一命中 `<el-button v-perm="['audit:viewAll','audit:approve']">`。
 * <p>仅展示层收敛；后端 @RequirePerm 为最终裁决。权限缺失时审批菜单隐藏、直连路由被守卫拦回。</p>
 */
export const perm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    const codes = Array.isArray(binding.value) ? binding.value : [binding.value]
    if (!auth.hasAnyPerm(codes)) {
      el.parentNode?.removeChild(el)
    }
  },
}

export default perm
/**
 * 权限标识符判定工具（P3.5）：前端按 perms 数组动态渲染（菜单/路由/按钮）。
 * 后端 @RequirePerm 为最终裁决（任一命中即可）；前端仅做展示层收敛，避免泄露无权限入口。
 */
export { hasPerm, hasAnyPerm }

/** 是否拥有指定权限标识符。 */
function hasPerm(perms: string[] | undefined | null, code: string): boolean {
  return !!perms && perms.includes(code)
}

/** 任一权限命中即为 true（@RequirePerm 的 any-of 语义）。 */
function hasAnyPerm(perms: string[] | undefined | null, codes: string[]): boolean {
  return !!codes && codes.some((c) => hasPerm(perms, c))
}
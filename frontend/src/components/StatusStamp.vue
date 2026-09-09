<script setup lang="ts">
/**
 * 状态戳：任务/报销单/工单的统一状态标记。
 * 语义色取自 tokens：running 墨青蓝（进行中）、success 账簿绿（通过）、
 * danger 朱砂（驳回/失败）、pending 赭金（待办/暂挂）、muted（中性/终态失效）。
 * 映射关系集中在 utils/task.ts 的 STATUS_MAP 内。
 */
withDefaults(
  defineProps<{
    label: string
    tone?: 'running' | 'success' | 'danger' | 'pending' | 'muted'
  }>(),
  { tone: 'muted' },
)
</script>

<template>
  <span class="stamp" :class="`stamp--${tone}`">{{ label }}</span>
</template>

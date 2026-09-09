<script setup lang="ts">
/**
 * 空态：解释当前为什么是空的，并给出下一步动作（默认插槽）。
 * 空屏是行动邀请，不是 mood 板。
 */
withDefaults(defineProps<{ title?: string; description?: string }>(), {
  title: '暂无数据',
  description: '',
})
</script>

<template>
  <div class="empty">
    <p class="empty-title">{{ title }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty {
  padding: 44px 20px;
  text-align: center;
}

.empty-title {
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
}

.empty-desc {
  max-width: 360px;
  margin: 6px auto 0;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.7;
}

.empty-action {
  margin-top: 14px;
}
</style>

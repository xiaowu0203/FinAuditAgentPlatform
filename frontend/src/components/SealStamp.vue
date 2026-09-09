<script setup lang="ts">
/**
 * 审批印章：财务终审结论的视觉签名（全站唯一的大胆元素）。
 * 只在工单终态出现——通过（账簿绿）/ 驳回·终止（朱砂）；落章动效尊重 reduced-motion。
 * 用法：容器 position:relative，印章绝对定位到单据卡片右上角。
 */
withDefaults(
  defineProps<{
    text: string
    tone?: 'success' | 'danger' | 'pending'
  }>(),
  { tone: 'success' },
)
</script>

<template>
  <div class="seal" :class="`seal--${tone}`" role="img" :aria-label="`印章：${text}`">
    <span class="seal-text display">{{ text }}</span>
  </div>
</template>

<style scoped>
.seal {
  display: grid;
  place-items: center;
  width: 96px;
  height: 96px;
  border: 2.5px solid currentColor;
  border-radius: 50%;
  /* 内圈细环：双环公章形制 */
  box-shadow:
    inset 0 0 0 3px var(--surface),
    inset 0 0 0 4.5px currentColor;
  color: var(--ledger);
  transform: rotate(-12deg);
  opacity: 0.92;
  user-select: none;
}

.seal--success {
  color: var(--ledger);
}

.seal--danger {
  color: var(--seal);
}

.seal--pending {
  color: var(--ochre);
}

.seal-text {
  color: inherit;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 5px;
  text-indent: 5px; /* 抵消末字间距，保持视觉居中 */
  line-height: 1;
}

/* 落章动效：一次性的盖印瞬间 */
@media (prefers-reduced-motion: no-preference) {
  .seal {
    animation: stamp-in 0.4s cubic-bezier(0.2, 1.4, 0.4, 1) both;
  }

  @keyframes stamp-in {
    from {
      transform: rotate(-12deg) scale(1.7);
      opacity: 0;
    }
    to {
      transform: rotate(-12deg) scale(1);
      opacity: 0.92;
    }
  }
}
</style>

<script setup lang="ts">
import { ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { AGENT_ROLE_MAP, TASK_STATUS_MAP } from '@/utils/task'
import StatusStamp from '@/components/StatusStamp.vue'
import type { TaskStatus, TaskStepVO } from '@/types'

/**
 * 凭证分录式流水线时间线：任务步骤的可视化主载体。
 * 左侧账线串起步骤编号（分录号），右侧一条「分录」：名称 + 类型/工具/角色徽标 + 状态戳，
 * 输出默认折叠（LLM 输出按 Markdown 渲染，其余按 JSON 展示），失败单据显示错误行。
 */
defineProps<{ steps: TaskStepVO[] }>()

const expanded = ref<Set<number>>(new Set())

function toggle(stepNo: number) {
  const next = new Set(expanded.value)
  if (next.has(stepNo)) {
    next.delete(stepNo)
  } else {
    next.add(stepNo)
  }
  expanded.value = next
}

/** LLM 步骤输出形如 { content: "Markdown 文本" }，判断为需渲染的 Markdown */
function isMarkdownText(o: unknown): o is { content: string } {
  return (
    typeof o === 'object' &&
    o !== null &&
    'content' in o &&
    typeof (o as { content: unknown }).content === 'string'
  )
}

/** Markdown → 安全 HTML（DOMPurify 清洗防 XSS） */
function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(marked.parse(text) as string)
}

function pretty(obj: unknown): string {
  try {
    return JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}

function roleLabel(role?: string | null): string | null {
  if (!role) return null
  return AGENT_ROLE_MAP[role] ?? role
}

function isExpanded(stepNo: number): boolean {
  return expanded.value.has(stepNo)
}
</script>

<template>
  <div class="pipe">
    <div
      v-for="s in steps"
      :key="s.id"
      class="pipe-row"
      :class="{ 'pipe-row--running': s.status === 'RUNNING', 'pipe-row--failed': s.status === 'FAILED' }"
    >
      <!-- 左：分录号 + 账线 -->
      <div class="pipe-rail">
        <span class="pipe-no display">{{ s.stepNo }}</span>
        <span class="pipe-line" />
      </div>

      <!-- 右：分录体 -->
      <div class="pipe-body">
        <div class="pipe-head">
          <span class="pipe-name">{{ s.stepName }}</span>
          <span v-if="s.stepType" class="chip" :class="s.stepType === 'LLM' ? 'chip--llm' : 'chip--tool'">
            {{ s.stepType === 'LLM' ? '推理' : '工具' }}
          </span>
          <span v-if="s.toolName" class="chip chip--plain">{{ s.toolName }}</span>
          <span v-if="roleLabel(s.agentRole)" class="chip chip--plain">{{ roleLabel(s.agentRole) }}</span>
          <span v-if="(s.retryCount ?? 0) > 0" class="chip chip--retry">重试 {{ s.retryCount }}</span>

          <StatusStamp
            class="pipe-status"
            :label="TASK_STATUS_MAP[s.status as TaskStatus].label"
            :tone="TASK_STATUS_MAP[s.status as TaskStatus].tone"
          />
        </div>

        <div v-if="s.errorMsg" class="pipe-error">{{ s.errorMsg }}</div>

        <div v-if="s.output" class="pipe-output">
          <button class="pipe-toggle" type="button" @click="toggle(s.stepNo)">
            {{ isExpanded(s.stepNo) ? '收起输出' : '展开输出' }}
          </button>
          <div v-show="isExpanded(s.stepNo)" class="pipe-output-body">
            <div v-if="isMarkdownText(s.output)" class="md-body" v-html="renderMarkdown(s.output.content)" />
            <pre v-else class="output-block">{{ pretty(s.output) }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pipe {
  padding: 4px 0;
}

.pipe-row {
  display: flex;
  gap: 14px;
}

/* 账线：连接相邻分录号 */
.pipe-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 30px;
  flex-shrink: 0;
}

.pipe-no {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-2);
  font-size: 13px;
}

.pipe-line {
  flex: 1;
  width: 1px;
  min-height: 14px;
  margin: 4px 0;
  background: var(--line);
}

.pipe-row:last-child .pipe-line {
  background: transparent;
}

.pipe-row--running .pipe-no {
  border-color: var(--flow);
  color: var(--flow);
}

.pipe-row--failed .pipe-no {
  border-color: var(--seal);
  color: var(--seal);
}

.pipe-body {
  flex: 1;
  min-width: 0;
  padding: 3px 0 18px;
}

.pipe-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.pipe-name {
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
}

/* 徽标：工具/推理/角色，小方章 */
.chip {
  padding: 0 7px;
  border: 1px solid var(--line-strong);
  border-radius: 3px;
  color: var(--ink-2);
  font-size: 11.5px;
  line-height: 19px;
  white-space: nowrap;
}

.chip--llm {
  border-color: var(--flow);
  color: var(--flow);
}

.chip--tool {
  border-color: var(--ledger);
  color: var(--ledger);
}

.chip--retry {
  border-color: var(--ochre);
  color: var(--ochre);
}

.chip--plain {
  border-style: dashed;
}

.pipe-status {
  margin-left: auto;
}

.pipe-error {
  margin-top: 8px;
  padding: 7px 11px;
  border: 1px solid var(--seal);
  border-radius: var(--radius-sm);
  background: var(--seal-weak);
  color: var(--seal);
  font-size: 12.5px;
  line-height: 1.6;
}

.pipe-output {
  margin-top: 8px;
}

.pipe-toggle {
  padding: 0;
  border: none;
  background: none;
  color: var(--ledger);
  font-size: 12.5px;
  cursor: pointer;
}

.pipe-toggle:hover {
  text-decoration: underline;
}

.pipe-output-body {
  margin-top: 8px;
}
</style>

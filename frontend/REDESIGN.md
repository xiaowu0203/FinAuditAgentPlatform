# 前端 UI 重构方案与进度锚点（REDESIGN.md）

> 本文件是「账簿·印章·留痕」UI 重构的执行锚点：记录已拍板的决策、设计 token、阶段任务与进度。
> 背景：评审通过的方案为**原地重构 frontend/**（不动 api/stores/types 结构），继续使用 Element Plus，
> 新增**深色模式**。分支 `feat/frontend-ui-redesign`。

## 已拍板决策

1. 原地重构 `frontend/`，api 层 / stores / types / router / 权限指令**保留结构**
2. 组件库继续 Element Plus，走 CSS 变量深度主题化 + 按需引入（还 1.2MB chunk 债）
3. 设计方向「账簿 · 印章 · 留痕」，从财务审核物料取材，反 AI 模板默认审美
4. 深色模式必做（token 层一次到位，`html.dark` 切换）
5. 全流程 P0–P4 一次执行完毕，验收问题就地修复

## 设计 Token（定稿基线）

| Token | Light | Dark（html.dark） | 用途 |
|---|---|---|---|
| `--paper` | `#F4F6F3` | `#131815` | 页面底色（冷调账簿纸，禁用暖米黄） |
| `--surface` | `#FFFFFF` | `#1B231F` | 卡片/面板 |
| `--surface-2` | `#FAFBF9` | `#212B26` | 次级面（表头、代码块） |
| `--ink` | `#1F2D2A` | `#E6EBE6` | 主文字 |
| `--ink-2` | `#5F6D67` | `#96A69E` | 次级文字 |
| `--ledger` | `#2F6B4F` | `#7DB79A` | 主操作色/链接/通过 |
| `--seal` | `#B5432E` | `#D4694F` | 审批否定语义（驳回/终止），禁止装饰性使用 |
| `--ochre` | `#9A6B15` | `#CBA24E` | 待审/暂挂（赭金） |
| `--line` | `#DCE2D9` | `#2C3731` | 账线（边框/分隔，横线为主） |
| 圆角 | 控件 4px / 卡片 8px | 同 | 告别 12–22px 泡泡圆角 |

字体：正文系统无衬线栈；标题与金额 `Noto Serif SC`（@fontsource 自托管子集，国内可用）+ 本地宋体回退；金额 `font-variant-numeric: tabular-nums`。
动效仅两处非装饰性：印章落章、时间线推进高亮；全局尊重 `prefers-reduced-motion`。
记忆点：审批工单「印章」+ 任务详情「凭证分录式流水线时间线」。

## Element Plus 主题映射要点

`--el-color-primary: var(--ledger)`、`--el-border-radius-base: 4px`、文字/边框/填充全套映射到 token；
深色模式引入 `element-plus/theme-chalk/dark/css-vars.css` + 自定义覆盖。ElMessage 等函数式组件样式在 main.ts 手动补齐。

## 阶段任务与进度（执行中更新）

### P0 锚点 ✅
- [x] 本文档 + 环境检查（node 24 / npm 11 可用；**后端网关 DOWN** → 验收策略见 P4）

### P1 基座
- [ ] npm 依赖：unplugin-auto-import / unplugin-vue-components / @fontsource/noto-serif-sc
- [ ] vite.config：ElementPlusResolver 按需引入 + proxy 走 env
- [ ] .env.development / .env.production（VITE_GATEWAY_ORIGIN 等）
- [ ] main.ts：去全量引入，dark css-vars，函数式组件样式补齐，图标改为各文件显式导入
- [ ] styles/tokens.css（light+dark）+ styles/base.css（替代 style.css，账页表格基类 .ledger-table / .money 等）
- [ ] DefaultLayout 重构（墨青窄栏 + 轻顶栏 + 面包屑 + 主题切换开关）
- [ ] 登录页（凭证封面左栏 + 表单右栏，去玻璃拟态）
- [ ] v-perm 指令补 updated 钩子（行为修复）
- [ ] vue-tsc + vite build 通过

### P2 账页族（列表 + 工作台）
- [ ] 共享组件：StatusStamp.vue（状态戳）/ EmptyState.vue（空态=行动邀请）/ PageHeader.vue
- [ ] dashboard：待办卡 + 最近流水账页 + 快捷入口（提交入口收进路由页，不再 JSON 裸文本域优先）
- [ ] task/list、reimbursement/list、audit/list、rule/list 统一账页范式
- [ ] 凭号列、金额右对齐、状态戳、筛选条

### P3 详情与表单
- [ ] PipelineTimeline.vue：任务详情流水线时间线（TOOL/LLM 徽标、耗时、重试、输出折叠）
- [ ] SealStamp.vue：审批详情印章 + 留痕时间线 + 金额对比
- [ ] reimbursement/create、edit：分组表单 + 明细子表 + 金额汇总条
- [ ] system/user、role、dept：账页范式 + 树选择器
- [ ] reimbursement/detail、audit/detail、task/detail、rule/list 逐页收口

### P4 打磨与验收
- [ ] vue-tsc + vite build 零错误；对比构建产物体积（目标：主 chunk 显著小于 1.2MB）
- [ ] 空态/错误态文案（行动邀请式）、响应式 768/992、对比度抽查、reduced-motion
- [ ] 视觉验收：起 dev server + browser-use 截图；若后端可用则以 admin 实登录走查，否则
      localStorage 预置假登录态看布局（API 报错属预期，仅验视觉）；截图交 judge 评审并修复
- [ ] 分阶段提交（不推送），最终汇报含对比截图说明

## 回归清单（每阶段后手动核对）

- 登录 → 401 跳转、权限码显隐（v-perm / 菜单 / 路由守卫）
- 任务列表分页 / 任务详情轮询 / 报销提交 / 审批动作 403 提示
- 深色切换无白底闪断、无低对比文字

# frontend（Vue3 前端）

FinAudit 财务费用智能审核平台 · 管理前端。UI 设计语言「账簿·印章·留痕」，设计与进度详见 [`REDESIGN.md`](./REDESIGN.md)。

## 技术栈

Vue3 + Vite 6 + TypeScript(strict) + Element Plus（按需引入）+ Pinia + Axios。
ECharts 暂未引入（P4 监控大盘再装）。

> Node 18 环境限制：Vite 固定 6.x，vue-router 固定 v4（v5 需要 Vite 7/8）。升级 Node 后可放开。

## 快速开始

```bash
npm install
npm run dev        # http://localhost:5173，/api 代理目标取 .env.development 的 VITE_GATEWAY_ORIGIN（默认 http://localhost:9080）
npm run build      # vue-tsc 类型检查 + 生产构建
npm run preview
```

前置：本机中间件（Nacos/RabbitMQ/MySQL/Redis/MinIO）+ 后端服务（gateway 9080 / agent-core 9201 / tool-service 9202 / tenant-service 9203 / rag-service 9204 / file-service 9205）已启动。

种子账号：`admin` / `admin123`（租户 `default`）。

## UI 设计要点（账簿·印章·留痕）

- **设计令牌**：`src/styles/tokens.css`（light + `html.dark` 双套），Element Plus 变量全套映射；`src/styles/base.css` 提供账页表格 `.ledger-table`、状态戳 `.stamp` 等基元
- **深色模式**：顶栏开关切换，首次跟随系统、此后记住用户选择
- **按需引入**：unplugin-auto-import / unplugin-vue-components + ElementPlusResolver，主 chunk 1.2MB → 190KB；函数式组件（ElMessage 等）样式在 `main.ts` 手动补齐
- **状态语义色**：账簿绿=通过、朱砂=驳回/终止（仅审批否定语义，禁止装饰性使用）、赭金=待审、墨青蓝=进行中
- **业务组件**：`StatusStamp` 状态戳 / `EmptyState` 空态（行动邀请式）/ `SealStamp` 审批印章（终态盖章）/ `PipelineTimeline` 任务流水线时间线
- **字体**：标题与金额 Noto Serif SC（@fontsource 自托管，国内可用），金额 `tabular-nums` 右对齐
- **动效纪律**：仅印章落章与时间线推进两处，尊重 `prefers-reduced-motion`

## 目录结构

```
src/
├── api/            # axios 封装（request.ts 统一拦截）+ 各域接口
├── components/     # StatusStamp / EmptyState / SealStamp / PipelineTimeline
├── directives/     # v-perm 按钮级权限（无权限移除 DOM，updated 响应权限刷新）
├── layouts/        # DefaultLayout：墨青侧栏 + 面包屑顶栏 + 深色开关 + 移动端抽屉
├── router/         # 路由 + 登录/权限守卫（meta.perm / meta.parent）
├── stores/         # Pinia（auth：token + user + 权限码，localStorage 持久化）
├── styles/         # tokens.css 设计令牌 + base.css 全局基元
├── types/          # 与后端接口契约对应的类型定义
├── utils/          # 任务/工单状态字典、轮询判定等工具
└── views/          # login / dashboard / task / reimbursement / audit / rule / system
```

## 与后端的约定

- 请求经 Vite dev 代理 → 网关（网关不剥前缀，下游映射 `/api/v1/**`）
- 统一响应 `{ code, message, data }`：业务错误 HTTP 2xx + `code!=0`；鉴权失败网关返回 HTTP 401
- axios 拦截器：自动注入 `Authorization: Bearer <token>`；401 → 清登录态跳登录页；403 → 拉 `/auth/me` 刷新权限码（按钮/菜单实时收敛）
- 轮询策略：任务/工单非终态时每 5s **后台静默刷新**（不触发 loading 遮罩，页面不可见时暂停），终态自动停止

接口明细见 `docs/api/gateway.md` / `docs/api/agent-core.md` / `docs/api/tenant-service.md`。

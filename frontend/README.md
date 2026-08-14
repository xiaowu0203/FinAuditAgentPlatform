# frontend（Vue3 前端）

FinAudit 财务费用智能审核平台 · 最小前端（P1.5）。

## 技术栈

Vue3 + Vite 6 + TypeScript + Element Plus + Pinia + Axios。
ECharts 暂未引入（P4 监控大盘再装）。

> Node 18 环境限制：Vite 固定 6.x，vue-router 固定 v4（v5 需要 Vite 7/8）。升级 Node 后可放开。

## 快速开始

```bash
npm install
npm run dev        # http://localhost:5173，/api 已代理到网关 http://localhost:9080
npm run build      # vue-tsc 类型检查 + 生产构建
npm run preview
```

前置：本机中间件四件套 + 后端四服务（gateway 9080 / agent-core 9201 / tool-service 9202 / tenant-service 9203）已启动。

种子账号：`admin` / `admin123`（租户 `default`，角色 `admin`）。

## 目录结构

```
src/
├── api/            # axios 封装（request.ts 统一拦截）+ auth/task 接口
├── layouts/        # 主布局（侧边菜单 + 顶栏用户/登出）
├── router/         # 路由 + 登录守卫
├── stores/         # Pinia（auth：token + user，localStorage 持久化）
├── types/          # 与后端接口契约对应的类型定义
├── utils/          # 任务状态字典/轮询判定等工具
└── views/          # login / dashboard（工作台）/ task（列表、详情）
```

## 与后端的约定

- 请求经 Vite dev 代理 → 网关 9080（网关不剥前缀，下游映射 `/api/v1/**`）
- 统一响应 `{ code, message, data }`：业务错误 HTTP 2xx + `code!=0`；鉴权失败网关返回 HTTP 401
- axios 拦截器：自动注入 `Authorization: Bearer <token>`；401 → 清登录态跳登录页
- 任务状态 `PENDING/RUNNING` 轮询刷新（终态自动停止）；续跑仅对 PENDING/RUNNING 显示

接口明细见 `docs/api/gateway.md` / `docs/api/agent-core.md` / `docs/api/tenant-service.md`。

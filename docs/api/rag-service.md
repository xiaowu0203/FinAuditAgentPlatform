# rag-service RAG API

> 端口 9204。**RAG 专用基础设施**：企业文档 → 向量 → 语义查询（Milvus），P4 填充。
> 不碰文件上传（file-service）、不做报销审核（agent-core）、不参与单据 OCR。
> 当前为空骨架，仅保留服务注册与端口；对外接口待 P4 补齐（语义检索、文档管理两类 RPC）。

## ⚠️ 经网关不可达（本服务无公开接口）

**`agent-gateway` 的路由表里没有 `rag-service`**（`agent-gateway/src/main/resources/application.yml:29-90`
的手写路由只覆盖 `tenant-service` / `agent-core-service` / `tool-service` / `file-service`，
且 `spring.cloud.gateway.server.webflux.discovery.locator.enabled: false` 已关闭「按服务发现自动生成路由」）。

后果与要求：

- P4 之前，**任何 `http://<网关>:9080/...` 路径都不会转发到 9204**；服务即使注册进 Nacos 也无法从外部访问；
- 该服务**尚无任何端点**（空骨架），故也不存在「端口直连可用」的临时方案；
- **P4 新增对外接口时必须同时补两件事**：① 在本服务实现端点（按项目约定 `/api/v1/**`）；② 在网关路由表**显式新增**一条 `lb://rag-service` 路由，否则接口写完也访问不到。
- 「网关只路由 `/api/v1/**`，`/internal/**` 故意不配路由」这一安全边界见 [`docs/api/gateway.md`](./gateway.md) 与网关 yml 注释。

## 服务边界（P2a-重构后）

| 能力 | 归属服务 | 经网关可达 |
|---|---|---|
| 文件上传 / 下载 / 预览 | `file-service`（9205） | ✅ `/api/v1/files/**` |
| 报销单 CRUD / 提交 / 任务生成 / 审核流程 | `agent-core-service`（9201） | ✅ `/api/v1/reimbursements/**`、`/api/v1/tasks/**`、`/api/v1/audit/tickets/**`、`/api/v1/rules/**` |
| 工具目录 / 注册 / 调试直调 | `tool-service`（9202） | ✅ `/api/v1/tools/**` |
| 租户 / 用户 / 角色 / 部门 / 权限 / 认证 | `tenant-service`（9203） | ✅ `/api/v1/auth/**`、`/api/v1/users/**`、`/api/v1/tenants/**`、`/api/v1/roles/**`、`/api/v1/permissions/**`、`/api/v1/depts/**` |
| RAG 语义检索 / 文档管理（P4） | 本服务（rag-service，9204） | ❌ **网关未配路由** |

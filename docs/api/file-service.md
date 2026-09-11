# file-service 文件 API

> 端口 9205。**纯二进制资源服务**：上传 / 详情 / 预览 / 下载，唯一持有 `common-oss-starter`（MinIO 默认）。
> 不含任何财务业务表、不建 Agent 任务、不感知审核流程；业务服务读附件一律经 `FileServiceFeign` 远程调用，**禁止直连 OSS**。
> 上传仅前端对接本服务；文件元数据落 `file_record`，业务附件经 `expense_attachment.file_record_id` 引用。
> 租户经 `X-Tenant-Id` 请求头传递，**缺失即拒绝**（不再回退默认租户 1）。

## ⚠️ P3.8 变更：对外端点与内部契约已分离

原先业务服务复用对外端点读取附件，但 `FeignHeaderPropagator` 会在业务服务的 HTTP 请求线程上透传调用者身份头，
导致 file-service 的用户可见性校验把「内部代读他人附件」判为越权拒绝 → agent-core 组附件快照失败 →
**前端附件区静默空白且无错误提示**。根因是一份契约承担了两种语义，现拆为两个前缀：

| 前缀 | 面向 | 守卫 | 网关 |
|---|---|---|---|
| `/api/v1/files/**` | 前端用户 | 登录 + **用户可见性**（上传人本人，或持有 `reimb:viewAll`/`audit:viewAll`） | 路由（对外可达） |
| `/internal/files/**` | 业务服务（Feign） | 登录上下文无关；**仅租户隔离**（多租户拦截器） | **不路由**（外部不可达） |

> 安全边界：内部前缀**故意不配置网关路由**（网关只路由 `/api/v1/**`），属结构性隔离，不依赖可伪造的请求头声明。
> 业务级越权由调用方负责——agent-core 提交时校验附件归属租户，tool-service 由 `ToolAccessGuard` 校验单据归属。

## POST /api/v1/files/upload — 上传

`multipart/form-data`：`file`（≤20MB，`spring.servlet.multipart.max-file-size`）。对象 key 为
`{tenantId}/{yyyyMM}/{uuid}{ext}`（租户前缀防跨租户碰撞），元数据落 `file_record`：

```json
{ "code": 0, "message": "ok", "data": {
  "id": 1, "tenantId": 1,
  "fileName": "invoice.png", "objectName": "1/202608/53edda00-....png",
  "contentType": "image/png", "size": 2048,
  "url": "http://localhost:9000/finaudit-file/1/202608/53edda00-....png?X-Amz-..."
} }
```

> `X-Tenant-Id` 为空 → body `code=400`「缺少租户标识 X-Tenant-Id，请通过网关访问」（P3.8 起不再默认租户 1）。
> `created_by` 由 `X-User-Id` 落库（`null` 则除 `viewAll` 持有者外无人可读）。

## GET /api/v1/files/{id} — 详情

返回 `FileVO`（含实时预览预签名 URL）。
**归属校验**：须为上传人本人，或持有 `reimb:viewAll`/`audit:viewAll`；否则 body `code=400`「无权访问该文件: {id}」。
不存在 → body `code=400`「文件不存在: {id}」。无登录上下文 → `code=400`「缺少登录上下文，请通过网关访问」。

## GET /api/v1/files/{id}/preview — 预览预签名 URL

按对象 content-type 浏览器内联渲染。`data` 为预签名 URL（默认 15 分钟有效）。归属校验同上。

## GET /api/v1/files/{id}/download — 下载预签名 URL

预签名 URL 上携带 `response-content-disposition=attachment; filename="..."`（由 MinIO 在最终 GET 时回该响应头；
**file-service 自身响应只是 JSON 字符串**，不是文件流）。归属校验同上。

## 内部契约（`/internal/files/**`，服务间专用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/internal/files/{id}` | 单条元数据（仅租户隔离） |
| GET | `/internal/files?ids=1,2` | 批量元数据（仅租户隔离，不做用户可见性校验） |
| GET | `/internal/files/{id}/preview` | 预览预签名 URL（仅租户隔离） |
| GET | `/internal/files/{id}/download` | 下载预签名 URL（仅租户隔离；供 tool-service 取票据图做 OCR） |

均要求 `X-Tenant-Id` 非空（缺失 body `code=400`）。网关不暴露，**外部无法访问**。

## 消费契约

- 业务服务（agent-core / tool-service）读文件：`common-code` `FileServiceFeign`，指向内部前缀
  - `GET /internal/files/{id}` → `R<FileRecordVO>`
  - `GET /internal/files?ids=` → `R<List<FileRecordVO>>`
  - `GET /internal/files/{id}/preview|download` → `R<String>`（预签名 URL）
- 存储抽象：`common-oss-starter`（`finaudit.oss.enabled=true`）；`presignGetUrl(bucket,key,responseContentDisposition)` 重载支持下载/预览区分

## 关联

- 网关路由：`/api/v1/files/**` → `lb://file-service`（`/internal/**` **不配路由**）
- 数据库：`file_record`（见 `docs/database/tables.md` §11）
- 成对文件：`InternalFileController` ↔ `common-code` `FileServiceFeign`（**改一处必须同步另一处**）

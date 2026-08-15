# file-service 文件 API

> 端口 9205。**纯二进制资源服务**：上传 / 详情 / 预览 / 下载，唯一持有 `common-oss-starter`（MinIO 默认）。
> 不含任何财务业务表、不建 Agent 任务、不感知审核流程；业务服务读附件一律经 `FileServiceFeign` 远程调用，**禁止直连 OSS**。
> 上传仅前端对接本服务；文件元数据落 `file_record`，业务附件经 `expense_attachment.file_record_id` 引用。
> 租户经 `X-Tenant-Id` 请求头传递。

## POST /api/v1/files/upload — 上传

`multipart/form-data`：`file`（≤20MB）。对象 key 为 `{tenantId}/{yyyyMM}/{uuid}{ext}`（租户前缀防跨租户碰撞），元数据落 `file_record`：

```json
{ "code": 0, "message": "ok", "data": {
  "id": 1, "tenantId": 1,
  "fileName": "invoice.png", "objectName": "1/202608/53edda00-....png",
  "contentType": "image/png", "size": 2048,
  "url": "http://localhost:9000/finaudit-file/1/202608/53edda00-....png?X-Amz-..."
} }
```

## GET /api/v1/files/{id} — 详情

返回 `FileVO`（含实时预览预签名 URL）；不存在返回 400「文件不存在」。

## GET /api/v1/files?ids=1,2 — 批量详情（供业务服务组快照）

`ids` 逗号分隔。**按当前租户自动过滤**：含不存在或不属于本租户的 id 时，返回条数 < 请求数（消费方据此校验）。返回 `R<List<FileVO>>`。

## GET /api/v1/files/{id}/preview — 预览预签名 URL

按对象 content-type 浏览器内联渲染。`data` 为预签名 URL（默认 15 分钟有效）。

## GET /api/v1/files/{id}/download — 下载预签名 URL

响应头带 `Content-Disposition: attachment; filename="..."`（SDK 对 header 值 URL 编码，中文/空格安全）。`data` 为预签名 URL。

## 消费契约

- 业务服务（agent-core 等）读文件：`common-code` `FileServiceFeign`
  - `GET /api/v1/files/{id}` → `R<FileRecordVO>`
  - `GET /api/v1/files?ids=` → `R<List<FileRecordVO>>`
  - `GET /api/v1/files/{id}/preview|download` → `R<String>`（预签名 URL）
- 存储抽象：`common-oss-starter`（`finaudit.oss.enabled=true`）；`presignGetUrl(bucket,key,responseContentDisposition)` 重载支持下载/预览区分

## 关联

- 网关路由：`/api/v1/files/**` → `lb://file-service`
- 数据库：`file_record`（见 `docs/database/tables.md` §11）

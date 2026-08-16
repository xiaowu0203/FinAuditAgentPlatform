# rag-service RAG API

> 端口 9204。**RAG 专用基础设施**：企业文档 → 向量 → 语义查询（Milvus），P4 填充。
> 不碰文件上传（file-service）、不做报销审核（agent-core）、不参与单据 OCR。
> 当前为空骨架，仅保留服务注册与端口；对外接口待 P4 补齐（语义检索、文档管理两类 RPC）。

## 服务边界（P2a-重构后）

| 能力 | 归属服务 |
|---|---|
| 文件上传 / 下载 / 预览 | `file-service`（9205） |
| 报销单 CRUD / 提交 / 任务生成 / 审核流程 | `agent-core-service`（9201） |
| RAG 语义检索 / 文档管理（P4） | 本服务（rag-service，9204） |

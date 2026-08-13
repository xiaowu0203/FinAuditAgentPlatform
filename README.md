# FinAuditAgentPlatform · 财务费用智能审核 Agent 平台

> **当前进度：P0 基建 ✅ 完成 ｜ P1 核心闭环 🚧 规划中** · 详细文档见 `docs/`

## 项目简介

基于 Spring Cloud 分布式微服务 + Spring AI 的**财务费用智能审核 Agent 平台**：
将报销单/费用单据的审核从「人工逐单审核」升级为「Agent 自主拆解 → 分步执行 → 工具联动 → 自主纠错 → 高风险人工审批」的智能化闭环，支持多租户、可量化评估、全链路可观测。

**核心差异化**：不是问答机器人，而是具备任务断点续跑、Multi-Agent 异步协作、分层上下文治理、人工审批网关与审计留痕的工程化 Agent 平台。

## 功能概览

- 费用单据智能审核（票据提取 → 规则初审 → 金额核验 → 独立复核 → 人工终审）
- 多租户隔离 + RBAC 细粒度权限
- Agent 任务持久化 / 断点续跑 / MQ 异步编排
- 审批规则引擎（T0~T3 分级）+ 全流程审计留痕
- RAG 企业知识库（Milvus）
- 量化评估平台 + 监控大盘

## 技术栈

JDK 21 · Spring Boot 3.5.0 · Spring Cloud 2025.0.0 · Spring Cloud Alibaba 2025.0.0.0 · Spring AI 1.1.2 · MyBatis-Plus 3.5.12 · MySQL 5.7 · Redis · Nacos 3.2.2 · RabbitMQ · MinIO · Milvus（P2）· Vue3

## 当前状态

| 阶段 | 状态 | 内容 |
|---|---|---|
| P0 基建 | ✅ 完成 | 多模块骨架、common × 5、网关注册 Nacos、双 remote 同步、Nacos 初始化 |
| P1 核心闭环 | 🚧 规划中 | 单 Agent + 工具集 + 任务持久化 + MQ + DeepSeek + 最小前端 |

## 快速启动

```bash
# 1. 配置环境变量（密钥不入库）
cp .env.example .env
# 2. 初始化 Nacos（dev/test 命名空间 + 占位配置）
bash docs/deploy/nacos-init.sh
# 3. 启动服务（backend/agent-gateway 等）
```

> 详细环境说明见 `docs/deploy/`。

## 目录结构

见 `CLAUDE.md` 第 4 节。

## 开源协议

Apache License 2.0

# FinAuditAgentPlatform · Financial Expense Audit Agent Platform

> **Progress: P0 Foundation ✅ ｜ P1 Core Loop ✅ (5-service integration acceptance passed) ｜ P2 Reimbursement and audit tools ✅ ｜ P3a Multi-agent roles and rule pipeline ✅ ｜ P3b/P3c planned** · Details in `docs/`

## Overview

A **financial expense audit agent platform** built on Spring Cloud microservices + Spring AI.
It upgrades expense/fee review from manual line-by-line auditing to an intelligent closed loop:
**agent autonomous decomposition → step-by-step execution → tool orchestration → self-correction → human approval on high-risk actions**,
with multi-tenant support, quantifiable evaluation, and full-chain observability.

**Key differentiators**: not a Q&A bot — an engineered agent platform with task breakpoint-resume,
async multi-agent collaboration, layered context governance, approval gateway and audit trail.

## Features

### Delivered (P1)

- **Agent task loop**: task persistence + state machine (`PENDING → RUNNING → SUCCESS / FAILED`) + breakpoint-resume + RabbitMQ async orchestration
- **Model integration**: DeepSeek (Spring AI), dynamic tool-catalog injection into the planning prompt
- **Multi-tenant + auth**: logical tenant data isolation + RBAC + JWT session revocation (Redis blacklist)
- **Tool governance**: tool registry + input Schema + dynamic enable/disable, first tool `amount_verify` (Decimal-only money)
- **Service contract**: Feign contracts centralized in common-code + common-feign-starter header/token propagation
- **Minimal frontend**: Vue3 + TypeScript, four pages (login / dashboard / task list / task detail)

### Delivered (P3a: multi-agent roles and rule pipeline)

- Five financial agent roles: document parsing, budget calculation, rule validation, risk auditing, and scheduling
- `REIMBURSEMENT` tasks use a deterministic rule-based pipeline and return `AUTO_PASS` or `NEED_REVIEW` with review reasons
- `APPROVAL_PENDING` currently marks manual review only; approval tickets, approval actions, and audit records belong to P3b

### Roadmap (P3b/P3c+, see `docs/planning/future-roadmap.md`)

- **P3b** Approval ticket workflow (tickets, approval actions, amend-and-rerun, audit trail)
- **P3c** Security controls (prompt-injection blocking, output masking, tool authorization)
- **P4** RAG enterprise knowledge base (Milvus), monitoring dashboard, and quantitative evaluation

## Tech Stack

JDK 21 · Spring Boot 3.5.0 · Spring Cloud 2025.0.0 · Spring Cloud Alibaba 2025.0.0.0 · Spring AI 1.1.2 · MyBatis-Plus 3.5.12 · MySQL 5.7 · Redis · Nacos 3.2.2 · RabbitMQ · MinIO · Milvus (P2) · Vue3

## Quick Start

```bash
# 1. Configure environment variables (secrets never committed)
cp .env.example .env
#    set FINAUDIT_MODEL_API_KEY (DeepSeek) and FINAUDIT_JWT_SECRET in .env

# 2. Init Nacos (dev/test namespaces + placeholder configs)
bash docs/deploy/nacos-init.sh

# 3. Init database (finaudit schema + seed data)
mysql -uroot -p < docs/database/finaudit-schema.sql

# 4. Start backend services (requires local middleware: Nacos / RabbitMQ / MySQL / Redis)
cd backend
mvn spring-boot:run -pl agent-gateway       # Gateway 9080
mvn spring-boot:run -pl agent-core-service  # Agent orchestration 9201
mvn spring-boot:run -pl tool-service        # Tool execution 9202
mvn spring-boot:run -pl tenant-service      # Tenant & auth 9203

# 5. Start frontend (dev proxy /api → gateway 9080)
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Seed account: `admin / admin123` (tenant `default`, role `admin`).

> Environment details in `docs/deploy/`, API docs in `docs/api/`, architecture in `docs/architecture/`, phase plans in `docs/planning/`.

## Directory Structure

See `CLAUDE.md` section 4.

## License

Apache License 2.0

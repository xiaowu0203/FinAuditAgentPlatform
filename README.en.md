# FinAuditAgentPlatform · Financial Expense Audit Agent Platform

> Documentation in progress (P0 foundation stage) · Details in `docs/`

## Overview

A **financial expense audit agent platform** built on Spring Cloud microservices + Spring AI.
It upgrades expense/fee review from manual line-by-line auditing to an intelligent closed loop:
**agent autonomous decomposition → step-by-step execution → tool orchestration → self-correction → human approval on high-risk actions**,
with multi-tenant support, quantifiable evaluation, and full-chain observability.

**Key differentiators**: not a Q&A bot — an engineered agent platform with task breakpoint-resume,
async multi-agent collaboration, layered context governance, approval gateway and audit trail.

## Features

- Intelligent expense review (receipt extraction → rule screening → amount verification → independent re-check → human final review)
- Multi-tenant isolation + RBAC fine-grained permissions
- Agent task persistence / breakpoint-resume / MQ async orchestration
- Approval rule engine (T0–T3 tiers) + full audit trail
- RAG enterprise knowledge base (Milvus)
- Quantitative evaluation platform + monitoring dashboard

## Tech Stack

JDK 21 · Spring Boot 3.5.0 · Spring Cloud 2025.0.0 · Spring Cloud Alibaba 2025.0.0.0 · Spring AI 1.1.2 · MyBatis-Plus · MySQL 5.7 · Redis · Nacos · RabbitMQ · MinIO · Milvus (P2) · Vue3

## Quick Start

> See `docs/deploy/`

## Directory Structure

See `CLAUDE.md` section 4.

## License

Apache License 2.0

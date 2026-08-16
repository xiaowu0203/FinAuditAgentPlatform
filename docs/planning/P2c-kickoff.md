# P2c 财务规则配置 · 开工交接

> 创建: 2026-08-16 ｜ 状态: 待开工 ｜ 源文档: `P2-execution-plan.md`（§4 P2c / §6 接口 / §7 风险 / §8 验收）
> 适用会话: 新开会话直接照此开工，相关 memory 见 `nacos3-console-architecture` / `intellij-maven-jdk-env`。

## 1. 背景与目标

P2a/P2b 已落地并推送（分支 `feat/p2-expense-loop`）。工具层（`rule_check`/`budget_query`/`ocr_extract`/`duplicate_check`）与数据层（`finance_rule`/`budget` 表 + 种子）全部就绪，**唯独配置面是空的**——现在改限额/时效只能改 SQL。
P2c 目标：**规则可视化配置 + 发布 Nacos 动态刷新，改规则不发布服务**，兑现 P2 验收清单第 4 项。

## 2. 已完成基础（复用，不重做）

| 资产 | 位置 | 现状 |
|---|---|---|
| `finance_rule` 表 + 种子 | `migration-P2b.sql` / `finaudit-schema.sql` | 2 条种子：`amount_limit`（threshold=5000）、`reimburse_expire`（maxDays=30），**`published=0`** |
| `FinanceRule` 实体 + `FinanceRuleService` | agent-core | 仅 `listEnabled()` 直查 DB + `check()` 评估，**无 CRUD**；类注释已预留「P2c 落地 Nacos 动态刷新 + 本地缓存 TTL 后迁缓存」 |
| `Budget` 实体 + `BudgetService` | agent-core | 只读（budget_query 用） |
| `RuleCheckTool` → Feign → `AuditDataController` | tool-service / common-code / agent-core | 链路已通（Feign 契约在 common-code，租户经请求头） |
| 前端 | `frontend/` | 已有报销/任务页可参照；`/rules` 页待建 |

## 3. 待建清单

### ① 后端规则 CRUD（agent-core 新增 `RuleController`）
- `GET /api/v1/rules`（列表，含启停/发布状态）
- `POST /api/v1/rules`（新增）
- `PUT /api/v1/rules/{id}`（修改）
- `POST /{id}/toggle`（启停）
- `POST /{id}/publish`（发布）
- `FinanceRuleService` 补全 `listAll`/`save`/`update`/`toggle`/`publish`；实体转换走 `from/apply`（CLAUDE.md §5.6），金额/配置 Decimal

### ② Nacos 动态刷新链路（核心难点）——两个触达点勿混
- **管理端发布**：`publish` → 租户规则集序列化 JSON，用 **Nacos 3 控制台 API** 写 `127.0.0.1:8080 /v3/console/cs/config`（参数 `groupName`+`namespaceId`，头 `accessToken`）。⚠️ 传统 `/nacos/v1/cs/configs` 本机 **404 不存在**；复用 `docs/deploy/nacos-init.sh` 的「8848 登录 → 8080 v3」模式
- **应用端订阅**：agent-core 用 spring-cloud-alibaba nacos-config（`@RefreshScope`）或 `NacosConfigService` 监听；规则量小，**本地缓存 TTL 兜底**（规划 §7），改规则即时生效不重启
- data-id 方案：租户维度（如 `finaudit-rules-{tenantId}`），发布时 `published=1` + `version` 自增
- 兜底：种子 `published=0`，Nacos 无配置期间 `listEnabled` 回退 DB 直查，避免空窗

### ③ 前端规则配置页
规则列表 + 按 `rule_type` 结构化表单（AMOUNT_LIMIT→threshold、REIMBURSE_EXPIRE→maxDays…）+ 发布/启停 + 生效状态展示；路由 `/rules`

### ④ 差旅/补贴规则（建议首版后置）
`TRAVEL_STANDARD`/`SUBSIDY_LIMIT` 现因缺城市/住宿天数字段在 `check()` 内直接跳过。若配置页要支持，需先定入参扩展（明细加 city/hotelDays）。建议首版只做 AMOUNT_LIMIT + REIMBURSE_EXPIRE，另两类可配置但评估后置。

## 4. 新会话先定的 4 个决策

1. **应用端刷新机制**：`@RefreshScope`+nacos-config（标准，需加依赖） vs `NacosConfigService` 手动监听 + TTL 缓存（可控，贴合现有直查 DB）
2. **管理 API 鉴权**：规划 §6 要求走网关 Bearer + 租户隔离；P2 无 RBAC，先定管理员身份方案（X-User-Id 白名单或标注 P5 补角色校验，参照 `AuditDataController` 注记）
3. **data-id 与 namespace**：租户维度 data-id 放哪个 namespace
4. **差旅/补贴**首版做不做真评估（建议不做，见 §3④）

## 5. 约束与坑（环境硬性）

- 编译：Bash `export JAVA_HOME="/c/Program Files/Java/jdk-21"`；Maven `-o`（离线）+ `-pl <模块> -am`；IDEA 2022.2 source 级别低——**避免 instanceof 绑定模式**（`instanceof Map<?,?> x` 编译失败），用类型测试+转型；switch 表达式本工程可用
- MySQL 5.7：`rule_config` JSON 只存不参与 WHERE（规划 §7）
- 配置类默认值字段用 `@Getter @Setter` 勿用 `@Data`（默认值静默丢失，CLAUDE.md §5.7）
- 内联测试请求直连服务端口（9201/9202/9205）带 `X-Tenant-Id/X-User-Id`；网关要 JWT
- 跑闭环前确认 agent-core 为最新代码（P2b 竞态修复 `afterCommit` 需重启生效）

## 6. 验收

规划 §8 第 4 项：改 `amount_limit.threshold` → 发布 → **不重启** → 下次 `rule_check` 立即用新阈值。建议照 P2b 方式：清数据 → 跑闭环验证改前/改后阈值是否生效。

## 7. 参考资料

- 规划与验收：`docs/planning/P2-execution-plan.md`
- Nacos 运维模式：`docs/deploy/nacos-init.sh` + memory `nacos3-console-architecture`
- 规则评估逻辑：`backend/agent-core-service/.../service/FinanceRuleService.java`（`check`/`evalAmountLimit`/`evalReimburseExpire`）
- 工具-facing 端点范式：`AuditDataController.java`
- Feign 契约位置：`backend/common/common-code/.../web/feign/`

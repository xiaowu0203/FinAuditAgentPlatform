# P2 单据闭环与审核工具 · 执行点文档

> 版本: v0.3 ｜ 状态: **P2a 后端已落地、前端归 P2a 待推进；P2b 审核工具、P2c 财务规则配置待开工** ｜ 前置依赖: P1 完成、MinIO 本机启动
> 目标: 把审核业务从「纯 JSON 文本任务」升级为「真实报销单闭环」——图片上传、四类审核工具、财务规则可视化配置，为 P3 多 Agent 流水线铺垫数据与工具底座。

---

## 1. 范围

| 阶段 | 内容 | 说明 |
|---|---|---|
| **P2a 单据闭环** | MinIO 文件能力 + 报销单/附件实体 + 前端上传/提交页 + 前端任务跳转 | 任务入参从纯 JSON 文本 → 真实报销单数据（文件 ID 引用）；**后端已落地，前端归 P2a 待推进** |
| **P2b 审核工具做厚** | OCR / 预算查询 / 规则校验 / 重复报销检测 | amount_verify 从唯一工具 → 五类之一 |
| **P2c 财务规则配置** | 规则表 + 配置页 + Nacos 动态刷新 | 差旅标准/限额/时效可视化配置，改规则不发布服务 |

**编排说明**：P2 仍是单 Agent（沿用 P1 TaskPlanner），多 Agent 角色化流水线由 P3 承接。P2 重点是数据层 + 工具层做真。

## 2. 已确认决策（2026-08-15）

| # | 决策 | 结论 |
|---|---|---|
| D1 | OCR 引入时机 | **P2b**（非占位、非后置），需第三方 key + 多厂商兜底/熔断降级 |
| D2 | 单据提交形态 | **图片上传**（依赖 MinIO，P2a 前启动 9000/9001） |
| D3 | 财务规则载体 | **后台可视化配置 + Nacos 动态刷新**（规则存表，保存后发布到 Nacos，服务 @RefreshScope 生效） |
| D4 | 文件能力载体 | **新增 file-service 承载纯文件资源**（上传/下载/预览收敛 file-service，唯一持有 `common-oss-starter`；报销域迁 agent-core 服务内直调建任务，读附件一律 `FileServiceFeign`；rag-service 回归 RAG 专用空骨架，P4 填 Milvus）。**P2a 重构已落地** |
| D5 | 任务业务分派 | **`agent_task` 增 `task_type`（REIMBURSEMENT/GENERIC）**：报销单提交显式标记 REIMBURSEMENT，通用任务缺省 GENERIC；`TaskPlanner` 按业务注入提示词指令段 + 按业务收敛工具集（财务工具仅 REIMBURSEMENT 注入），避免报销与通用任务共用同一份写死「财务审核」提示词导致规划漂移，并为 P3 多 Agent 角色化铺垫分派字段。**P2a 已落地** |

## 3. 待确认决策（P2 开工前定）

| # | 决策 | 备选 | 结论 |
|---|---|---|---|
| D6 | OCR 厂商选型 | 百度 / 阿里 / 本地轻量；统一封装进 OCR 工具 Starter | 待定：需账号 key（环境变量 `FINAUDIT_OCR_API_KEY`），优先选本机可用/免费额度方案 |
| D7 | 部门归属 | 先 `dept_name` 字符串，独立部门表后置 | 字符串（预算按 dept_name 分组即可） |

## 4. 执行点进度（待进行）

### P2a 单据闭环
- [x] **MinIO 文件能力**：按 D4 落地，**P2a-重构后拆出 file-service（9205）**：`POST /api/v1/files/upload`（multipart → MinIO，元数据落 `file_record`，业务附件仅存 `file_record` 引用）
- [x] **报销单/附件实体**：`expense_reimbursement` / `expense_attachment`（见 §5，归属 agent-core；`expense_attachment` 去 fileName/objectName 加 file_record_id），实体 `from/apply` 转换，数据访问收敛到各自 Service（CLAUDE.md §5.8）
- [x] **报销单提交接口**：`POST /api/v1/reimbursements`（明细 + `fileRecordIds`）→ **agent-core 服务内直调 `AgentTaskService.createTask`**（同事务，消灭跨服务 Feign 孤儿窗口；`input_params` 携带报销单快照 + 附件 file 引用，`task_id` 反写回报销单）
- [x] **前端上传/提交页**（归 P2a）：图片多文件上传（进度条）+ 报销明细表单 + 内置模板（`npm run build` 通过）
- [x] **前端任务跳转**（归 P2a）：前端任务列表可跳报销单（后端 `TaskVO.inputParams.reimbId` 已暴露并 E2E 验证）

### P2b 审核工具做厚（tool-service）
- [ ] **`ocr_extract` 票据识别**：OCR 抽取金额/日期/商户/税号，识别失败自动重试 ≤3 → 仍失败推送人工录入（`ocr_status=FAILED`）；多厂商兜底（D6 定）
- [ ] **`budget_query` 预算核算**：查 `budget` 部门当月剩余预算，返回占用/剩余额度
- [ ] **`rule_check` 财务规则校验**：加载 `finance_rule`（本地缓存 + Nacos 刷新），校验差旅标准/补贴限额/报销时效/大额阈值，返回命中规则 + 是否超标
- [ ] **`duplicate_check` 重复报销检测**：按（申请人 + 商户 + 金额 + 日期区间）查历史报销单，返回疑似重复
- [ ] **工具注册**：四类工具注册进 `tool_registry`（入参 JSON Schema 强校验），TaskPlanner 动态注入已兼容（P1 收尾已验证）
- [ ] **金额一律 Decimal**：工具输出金额字段全 DECIMAL，杜绝 float/double

### P2c 财务规则配置
- [ ] **`finance_rule` 表 + CRUD**：规则类型 TRAVEL_STANDARD / SUBSIDY_LIMIT / REIMBURSE_EXPIRE / AMOUNT_LIMIT，`rule_config` JSON 结构化存储
- [ ] **发布到 Nacos**：`POST /api/v1/rules/{id}/publish` → 规则写入 Nacos（租户维度 data-id），`published` 标记；`rule_check` 订阅/轮询刷新，改规则不发布服务
- [ ] **前端规则配置页**：规则列表 + 编辑（结构化表单）+ 发布/启停 + 生效状态展示

## 5. 新增表（对齐现有 snake_case + 三件套风格）

**`expense_reimbursement` 报销单**
| 字段 | 类型 | 说明 |
|---|---|---|
| reimb_no | VARCHAR(40) UNIQUE | 报销单号，如 `R2026081512345678` |
| title / expense_type | VARCHAR | 标题 / 费用类型（TRAVEL/ENTERTAINMENT/OFFICE） |
| applicant_id / dept_name | BIGINT / VARCHAR(64) | 申请人用户ID / 部门字符串（D7） |
| total_amount | DECIMAL(12,2) | 申报总金额（**Decimal 强制**） |
| task_id | BIGINT | 关联 `agent_task.id`（提交后反写） |
| status | VARCHAR(20) | 审核状态（对齐任务状态机） |
| claim_date / remark | DATE / VARCHAR(512) | 报销日期 / 备注 |

**`file_record` 文件元数据（file-service，纯二进制资源）**
| 字段 | 类型 | 说明 |
|---|---|---|
| file_name / object_name | VARCHAR(255) | 原始文件名 / 对象存储 key（含租户前缀） |
| content_type / size | VARCHAR(128) / BIGINT | MIME 类型 / 字节大小 |

**`expense_attachment` 报销业务附件（agent-core，仅存引用 + 业务字段）**
| 字段 | 类型 | 说明 |
|---|---|---|
| reimb_id | BIGINT | 报销单 ID |
| file_record_id | BIGINT | file_record.id 引用（索引 idx_file_record） |
| file_type | VARCHAR(32) | INVOICE / ITINERARY / CONTRACT / OTHER（P2a 默认 OTHER，分类归 P2b OCR） |
| ocr_status / ocr_result | VARCHAR(16) / JSON | PENDING/SUCCESS/FAILED + OCR 抽取结果 |

**`budget` 部门预算**
| 字段 | 类型 | 说明 |
|---|---|---|
| dept_name / period | VARCHAR / VARCHAR(7) | 部门 + 预算周期 `YYYY-MM`，UNIQUE(tenant_id, dept_name, period) |
| total_budget / used_amount | DECIMAL(14,2) | 预算总额 / 已用，`used_amount` 审核通过后累加 |

**`finance_rule` 财务规则**
| 字段 | 类型 | 说明 |
|---|---|---|
| rule_code / rule_name | VARCHAR | 规则编码 / 名称 |
| rule_type | VARCHAR(32) | TRAVEL_STANDARD / SUBSIDY_LIMIT / REIMBURSE_EXPIRE / AMOUNT_LIMIT |
| rule_config | JSON | 结构化规则（如城市星级房价上限） |
| enabled / published / version | TINYINT / TINYINT / VARCHAR | 启停 / 是否已发布 Nacos / 版本 |

## 6. 新增接口（全部走网关 9080，Bearer + 租户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/files/upload` | 附件上传（multipart → MinIO） |
| POST | `/api/v1/reimbursements` | 提交报销单（明细 + 附件 ID → 生成任务） |
| GET | `/api/v1/reimbursements` | 报销单分页（员工视角仅本人） |
| GET | `/api/v1/reimbursements/{id}` | 报销单详情（含附件 + OCR 结果） |
| GET | `/api/v1/rules` | 规则列表（配置页） |
| POST / PUT | `/api/v1/rules` / `/api/v1/rules/{id}` | 新增 / 修改规则 |
| POST | `/api/v1/rules/{id}/publish` | 发布规则到 Nacos |
| POST | `/api/v1/rules/{id}/toggle` | 启停规则 |

## 7. 依赖与风险

| 风险 | 预案 |
|---|---|
| MinIO 本机未启动（9000/9001） | P2a 开工前启动（本机已有） |
| OCR 第三方故障 | 多厂商兜底 + 重试 ≤3 + 降级人工录入（`ocr_status=FAILED` 进工单） |
| MySQL 5.7 对 JSON 列的检索限制 | 规则检索条件尽量走独立列；`rule_config` 仅做存储不参与 WHERE 过滤 |
| 报销单 → 任务联动复杂度 | `reimb_id` 单向关联，任务 `input_params` 存报销单快照，状态独立推进 |
| 规则 Nacos 刷新一致性 | 发布后 `rule_check` 本地缓存 TTL 兜底；变更记 `audit_log`（P3 补全留痕） |

## 8. 验收清单（P2 完成定义）

1. 图片上传 MinIO 成功，附件与报销单关联可查
2. 提交报销单 → 生成 agent_task，任务入参为真实报销单数据（含附件引用）
3. 四类工具真实执行：OCR 抽取 / 预算核算 / 规则校验 / 重复检测，结果落库（金额全 Decimal）
4. 规则配置页改差旅标准 → 发布 → **无需重启服务**，下次审核立即生效
5. OCR 失败路径：自动重试 → 失败标记人工录入，不阻塞主流程
6. 跨租户隔离：租户 2 不可见租户 1 的报销单/预算/规则

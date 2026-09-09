# 前端界面截图集 · Frontend UI Screenshots

> 本项目「账簿 · 印章 · 留痕」设计系统的 Web 前端真实页面截图（源自前端 UI 重构 `feat/frontend-ui-redesign` 真实链路走查），供评审、文档与开源展示使用。

## 说明

共 **12 张**截图，覆盖 **登录 → 工作台 → 任务 → 报销单 → 审批工单 → 规则配置 → 系统管理** 的完整业务链路，均来自本地真实运行页面（种子账号 `admin / admin123`）。

## 截图列表

| 编号 | 文件 | 页面 | 说明 |
|---|---|---|---|
| 01 | `01-login.png` | 登录页 | 账簿纸风格登录页，租户/账号/验证码入口 |
| 02 | `02-workbench.png` | 工作台 | 待办统计 + 快捷入口账页化，移动端抽屉导航切换点 |
| 03 | `03-review-task.png` | 审核任务 · 列表 | 任务列表统一「账页」范式 + 状态戳（RUNNING/SUCCESS/FAILED/APPROVAL_PENDING） |
| 04 | `04-task-detail.png` | 任务详情 · 流水线时间线 | Agent 多步骤执行过程：工具/推理徽标 + 输出折叠 |
| 05 | `05-reimbursements.png` | 报销单 · 列表 | 报销单账页列表，金额衬线等宽右对齐、状态戳 |
| 06 | `06-reimbursement-detail.png` | 报销单 · 详情 | 单据详情 + OCR 提取结果展示 |
| 07 | `07-audit-ticket.png` | 审批工单 · 列表 | 审批工单账页（PENDING/APPROVED/REJECTED…），触发原因与复核入口 |
| 08 | `08-audit-ticket-detail.png` | 审批工单 · 详情 | **终审结论盖章可视化（审批印章）** + `audit_record` 审计留痕 |
| 09 | `09-rules.png` | 规则配置 | 财务规则账页（规则列表/启用停用，Nacos 动态下发） |
| 10 | `10-system-user.png` | 系统管理 · 用户 | 用户账页，权限码驱动渲染，部门/角色归属 |
| 11 | `11-system-role.png` | 系统管理 · 角色 | 角色权限树勾选（`sys_permission` 权限码） |
| 12 | `12-system-dept.png` | 系统管理 · 部门 | 部门树（`sys_dept`），防环 + 删除引用守卫 |

## 复现

启动完整环境（后端六服务 + 前端 dev server）的步骤见根目录 `README.md`「快速启动」，本地访问 `http://localhost:5173`。

## 维护约定

- 命名：`<两位序号>-<页面小写短横线>.png`，序号即展示顺序
- 新增截图请在「截图列表」表格末尾追加对应行
- 截图体积控制在几百 KB 内（总数建议 ≤ 2MB），不做整页超长图
- 需在根 `README.md` / `README.en.md` 展示的精选图，在对应界面预览小节按需引用本目录相对路径
- 此目录存放**页面成果截图**；架构图/状态机图（如审批工单状态机）仍在 `docs/images/`，两者不混放
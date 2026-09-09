package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.AuditAction;
import com.finaudit.agentcore.enums.AuditTicketStatus;
import com.finaudit.agentcore.enums.ReimbursementStatus;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.mapper.AuditRecordMapper;
import com.finaudit.agentcore.mapper.AuditTicketMapper;
import com.finaudit.agentcore.pojo.dto.AuditActionRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitResult;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AuditRecord;
import com.finaudit.agentcore.pojo.entity.AuditTicket;
import com.finaudit.agentcore.pojo.vo.AuditRecordVO;
import com.finaudit.agentcore.pojo.vo.AuditTicketDetailVO;
import com.finaudit.agentcore.pojo.vo.AuditTicketVO;
import com.finaudit.agentcore.pojo.vo.ReimbursementDetailVO;
import com.finaudit.starter.redis.lock.DistributedLockTemplate;
import com.finaudit.starter.web.auth.UserContextHolder;
import com.finaudit.starter.web.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 审批工单服务
 * <p>管理报销多Agent审核产生的审批工单全生命周期，AgentTask与AuditTicket为1:1绑定关系。</p>
 * <ul>
 * <li>编排回调：人工复核建单、重跑复位、自动通过闭合工单</li>
 * <li>业务操作：提交人重跑修改、撤回；财务审批通过/驳回/终止</li>
 * <li>状态机：待审批、待修改、已通过、已驳回、已终止、撤回申请、已撤回</li>
 * <li>保障：分布式锁防丢失更新；全部状态变更生成审计快照留痕；重跑次数上限控制防止死循环</li>
 * <li>多租户：由MyBatis‑Plus租户拦截器自动隔离数据</li>
 * </ul>
 */
@Service
public class AuditTicketService {

    private static final Logger log = LoggerFactory.getLogger(AuditTicketService.class);

    /** 修改重跑次数上限 */
    public static final int MAX_RERUN = 3;
    /** 重跑校验通过自动通过工单，系统内置留痕意见 */
    public static final String AUTO_PASS_COMMENT = "改金额重跑后自动通过";

    private final AuditTicketMapper ticketMapper;
    private final AuditRecordMapper recordMapper;
    private final AgentTaskService taskService;
    private final AgentTaskStepService stepService;
    private final ReimbursementService reimbursementService;
    private final DistributedLockTemplate lockTemplate;
    private final RuleBasedFlowEngine flowEngine;

    public AuditTicketService(AuditTicketMapper ticketMapper, AuditRecordMapper recordMapper,
                              AgentTaskService taskService, AgentTaskStepService stepService,
                              ReimbursementService reimbursementService, DistributedLockTemplate lockTemplate,
                              RuleBasedFlowEngine flowEngine) {
        this.ticketMapper = ticketMapper;
        this.recordMapper = recordMapper;
        this.taskService = taskService;
        this.stepService = stepService;
        this.reimbursementService = reimbursementService;
        this.lockTemplate = lockTemplate;
        this.flowEngine = flowEngine;
    }

    // ===================== 编排器回调入口（由AgentOrchestrator调用） =====================

    /**
     * 流水线判定 NEED_REVIEW 时进入审批态。
     * <p>
     * 业务流程：
     * 1. 更新任务状态为待审批、报销单更新为人工复核
     * 2. 幂等处理工单：
     * <ul>
     * <li>无工单：新建审批工单 + SUBMIT留痕快照</li>
     * <li>已有AMENDED：重跑后再次命中风险，复位PENDING，刷新风险原因，追加RERUN留痕</li>
     * <li>已有PENDING：直接返回，防止重复建单</li>
     * <li>终态(APPROVED/REJECTED/TERMINATED/WITHDRAW_*)：防御跳过，正常流程不会进入</li>
     * </ul>
     * </p>
     * @param task 当前Agent任务
     * @param result 任务执行结果map
     * @param finishedSteps 已经完成的步骤计数
     * @param reasons 触发人工复核的风险原因列表
     */
    @Transactional
    public void enterApproval(AgentTask task, Map<String, Object> result, int finishedSteps, List<String> reasons) {
        // 1. CAS 任务置为待审批：竞争失败说明任务已被并发迁移（如已作废），放弃进入审批态
        if (!taskService.markApprovalPending(task, result, finishedSteps)) {
            log.warn("任务 {} 进入审批态竞争失败（状态已被并发迁移），跳过建单", task.getId());
            return;
        }
        // 若为【报销单】，则将状态置为【人工复核】
        if (TaskType.REIMBURSEMENT.name().equals(task.getTaskType())) {
            reimbursementService.updateStatusByTaskId(task.getId(), ReimbursementStatus.MANUAL_REVIEW);
        }

        // 2. 根据任务ID查询审批工单（一个taskId最多对应一条工单，tenant+taskId业务唯一）
        AuditTicket existing = ticketMapper.selectOne(new LambdaQueryWrapper<AuditTicket>()
                .eq(AuditTicket::getTaskId, task.getId())
                .last("limit 1"));

        // 若不存在工单（第一次命中复核）
        if (existing == null) {
            // 3. 首次命中复核：创建工单 + SUBMIT审计快照

            // 根据【触发人工复核的风险原因列表】解析除命中的规则类型（取优先级最高）
            String triggerType = TriggerTypeResolver.resolve(reasons);
            // 数据转换
            AuditTicket ticket = AuditTicket.from(task.getTenantId(), task.getId(),
                    "AT-" + task.getTaskNo(), task.getTitle(), triggerType,
                    TriggerTypeResolver.buildRiskDesc(reasons), claimedTotal(task), reasons, task.getCreatedBy());
            // 创建工单
            ticketMapper.insert(ticket);
            // 创建审计留痕（快照）
            recordMapper.insert(AuditRecord.ofSnapshot(task.getTenantId(), ticket.getId(), AuditAction.SUBMIT,
                    ticket.getOriginAmount(), ticket.getOriginAmount(), null, task.getCreatedBy(), null, null,
                    null, snapshotOf(task)));
            log.info("任务 {} 命中复核，创建审批工单 {}（trigger={}）", task.getTaskNo(), ticket.getTicketNo(), triggerType);
            return;
        }

        // 存在工单、进行修改重跑操作
        if (AuditTicketStatus.AMENDED.name().equals(existing.getStatus())) {
            // 4. 修改重跑后，再次命中风险规则：工单复位PENDING，刷新风险描述，追加RERUN留痕

            // 根据【触发人工复核的风险原因列表】解析除命中的规则类型（取优先级最高）
            String triggerType = TriggerTypeResolver.resolve(reasons);
            // 复位PENDING，刷新风险描述
            existing.applyRerunResetWith(reasons, triggerType, TriggerTypeResolver.buildRiskDesc(reasons));
            // 更新工单
            ticketMapper.updateById(existing);
            // 获取当前金额
            BigDecimal amt = currentAmount(existing);
            // 解析数据，创建审计留痕（快照）
            Map<String, Object> snap = snapshotOf(task);
            recordMapper.insert(AuditRecord.ofSnapshot(task.getTenantId(), existing.getId(), AuditAction.RERUN,
                    amt, amt, null, null, null, null, snap, snap));
            log.info("任务 {} 重跑再次命中复核，工单 {} 复位 PENDING（trigger={}）", task.getTaskNo(), existing.getTicketNo(), triggerType);
            return;
        }
        // 5. PENDING 已存在 / 终态防御（含 WITHDRAWN/WITHDRAW_PENDING/TERMINATED）：不重复建单
        log.warn("任务 {} 已有工单 {}（状态 {}），跳过重复创建", task.getTaskNo(), existing.getTicketNo(), existing.getStatus());
    }

    /**
     * 流水线AUTO_PASS自动通过分支回调：闭合AMENDED状态工单。
     * <p>
     * 使用场景：提交人修改重跑，重跑后规则全部通过，任务SUCCESS，把AMENDED工单流转为APPROVED。
     * 约束：仅处理AMENDED工单；PENDING不会走到这里；非报销任务直接返回。
     * </p>
     * @param task 已自动通过的agent任务
     */
    @Transactional
    public void closeOnAutoPass(AgentTask task) {
        // 若不是【报销单】类型，直接结束
        if (!TaskType.REIMBURSEMENT.name().equals(task.getTaskType())) {
            return;
        }
        // 根据任务ID查询工单
        AuditTicket existing = ticketMapper.selectOne(new LambdaQueryWrapper<AuditTicket>()
                .eq(AuditTicket::getTaskId, task.getId())
                .last("limit 1"));

        // 若不存在或状态不是【AMENDED（修改后重跑）】，直接结束
        if (existing == null || !AuditTicketStatus.AMENDED.name().equals(existing.getStatus())) {
            return;
        }
        // 将工单状态设置为【已通过】，并将【最近处理意见】置为【改金额重跑后自动通过】
        existing.applyAutoPass(AUTO_PASS_COMMENT);
        // 更新工单信息
        ticketMapper.updateById(existing);

        // 获取当前金额
        BigDecimal amt = currentAmount(existing);
        // 解析数据，创建审计留痕（快照）
        Map<String, Object> snap = snapshotOf(task);
        recordMapper.insert(AuditRecord.ofSnapshot(task.getTenantId(), existing.getId(), AuditAction.APPROVE,
                amt, amt, AUTO_PASS_COMMENT, null, null, null, snap, snap));
        log.info("任务 {} 重跑自动通过，工单 {} 闭合 APPROVED", task.getTaskNo(), existing.getTicketNo());
    }

    // ===================== 查询接口（读，区分财务角色/普通提交人权限） =====================

    /**
     * 审批工单分页查询
     * <p>权限规则：财务角色可查租户全部工单；普通申请人只能查询自己创建的工单</p>
     * @param pageNum 页码
     * @param pageSize 页大小
     * @param status 工单状态过滤，可为null
     * @param taskId 关联任务id过滤，可为null
     * @param userId 当前登录用户id
     * @param viewAll 是否全量可见（audit:viewAll 权限码，网关快照权威）
     * @return 分页VO
     */
    public Page<AuditTicketVO> page(int pageNum, int pageSize, String status, Long taskId, Long userId, boolean viewAll) {
        LambdaQueryWrapper<AuditTicket> wrapper = new LambdaQueryWrapper<AuditTicket>()
                .orderByDesc(AuditTicket::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(AuditTicket::getStatus, status);
        }
        if (taskId != null) {
            wrapper.eq(AuditTicket::getTaskId, taskId);
        }
        // 无 audit:viewAll 权限，只能查看自己提交的工单
        if (!viewAll) {
            wrapper.eq(AuditTicket::getCreatedBy, userId);
        }
        Page<AuditTicket> page = ticketMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<AuditTicketVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(AuditTicketVO::from).toList());
        return voPage;
    }

    /**
     * 获取工单详情：工单本体 + 报销单详情 + 全部操作留痕
     * @param id 工单id
     * @param userId 当前登录用户id
     * @param viewAll 是否全量可见（audit:viewAll 权限码）
     * @return 完整详情VO
     */
    public AuditTicketDetailVO detail(Long id, Long userId, boolean viewAll) {
        AuditTicketVO ticket = requireReadable(id, userId, viewAll);
        ReimbursementDetailVO reimbursement = reimbursementService.detailByTaskId(ticket.getTaskId());
        return AuditTicketDetailVO.from(ticket, reimbursement, records(id, userId, viewAll));
    }

    /**
     * 查询工单全部操作留痕记录，时间升序
     * @param id 工单id
     * @param userId 当前登录用户id
     * @param viewAll 是否全量可见（audit:viewAll 权限码）
     * @return 留痕VO列表
     */
    public List<AuditRecordVO> records(Long id, Long userId, boolean viewAll) {
        requireReadable(id, userId, viewAll);
        return recordMapper.selectList(new LambdaQueryWrapper<AuditRecord>()
                .eq(AuditRecord::getTicketId, id)
                .orderByAsc(AuditRecord::getId)).stream().map(AuditRecordVO::from).toList();
    }

    /**
     * 读权限校验：工单存在性校验 + 租户隔离 + 数据权限
     * <p>租户隔离由mybatis‑plus多租户拦截器自动注入条件；非财务只能读取自己创建工单</p>
     * @param id 工单id
     * @param userId 当前登录用户id
     * @param viewAll 是否全量可见（audit:viewAll 权限码）
     * @return 工单VO
     * @throws BizException 工单不存在 / 无权访问
     */
    private AuditTicketVO requireReadable(Long id, Long userId, boolean viewAll) {
        AuditTicket ticket = getRequired(id);
        // 无 audit:viewAll 权限仅本人可读；无身份上下文一律拒绝
        if (!viewAll && (userId == null || !userId.equals(ticket.getCreatedBy()))) {
            throw new BizException("无权查看他人审批工单");
        }
        return AuditTicketVO.from(ticket);
    }

    // ===================== 提交人侧动作：resubmit修改重跑 / withdraw撤回 / requestWithdraw申请撤销 =====================

    /**
     * 提交人修改报销明细并触发重跑。
     * <p>
     * 锁保护：使用audit:ticket:{id}分布式锁，锁内重读实体，防止丢失更新。
     * 业务约束：仅提交人本人；工单状态PENDING/REJECTED；未达到重跑上限；任务不在RUNNING。
     * 执行：更新报销明细、重建任务plan步骤、工单置AMENDED、rerun_count+1；Controller事务提交后调用continueTask触发重跑。
     * </p>
     * @param reimbId 报销单id
     * @param request 提交人修改请求
     * @param tenantId 租户id
     * @param userId 当前提交人id
     * @param username 当前提交人名称
     * @return taskId，controller拿到后异步触发任务继续执行
     */
    @Transactional
    public Long resubmit(Long reimbId, ReimbursementResubmitRequest request, Long tenantId, Long userId, String username) {
        // 根据报销单Id查询任务Id
        Long taskId = reimbursementService.getTaskIdByReimbId(reimbId);
        if (taskId == null) {
            throw new BizException("报销单不存在或未关联审核任务: " + reimbId);
        }
        // 根据任务Id查询工单信息
        AuditTicket holder = getByTask(taskId);
        if (holder == null) {
            throw new BizException("未找到关联审批工单，无法修改重跑: " + reimbId);
        }

        // 锁内重读数据库实体，禁止使用锁外查询出来的holder做业务判断，防止丢失更新
        return lockTemplate.executeInTx("audit:ticket:" + holder.getId(), () -> {
            // 根据工单ID查询工单信息
            AuditTicket ticket = getRequired(holder.getId());
            // 校验是否本人提交重跑
            if (!userId.equals(ticket.getCreatedBy())) {
                throw new BizException("仅提交人可修改重跑");
            }
            // 获取工单状态
            String status = ticket.getStatus();
            // 若工单状态非【待审批/已驳回状态】，禁止重跑
            if (!AuditTicketStatus.PENDING.name().equals(status)
                    && !AuditTicketStatus.REJECTED.name().equals(status)) {
                throw new BizException("仅待审批/已驳回状态可修改重跑（当前 " + status + "）");
            }
            // 获取重跑次数
            int rerun = ticket.getRerunCount() == null ? 0 : ticket.getRerunCount();
            if (rerun >= MAX_RERUN) {
                throw new BizException("已达重跑上限（" + MAX_RERUN + " 次），无法再次修改重跑");
            }

            // 获取任务信息
            AgentTask task = taskService.getRequired(taskId);
            // 获取当前金额
            BigDecimal before = currentAmount(ticket);
            // 构建报销单数据快照
            Map<String, Object> beforeData = reimbursementService.buildSnapshot(reimbId);
            // 更新报销业务数据，保留原有title/dept等基础信息不变
            ReimbursementResubmitResult ctx = reimbursementService.resubmit(reimbId, request, tenantId);
            // CAS 更新任务入参并重置为RUNNING（附超时计时起点刷新）：竞争失败说明任务
            // 已被并发迁移，拒绝重跑——原「任务RUNNING即拒绝」的读后判断由 CAS 取代
            if (!taskService.prepareRerun(task, ctx.inputParams())) {
                throw new BizException("任务状态已变更，请刷新后重试");
            }
            // 根据新入参重新生成任务步骤plan，修复旧逻辑inputParams陈旧问题（重新生成步骤）
            List<TaskPlanStep> plan = flowEngine.plan(task);
            // 各个步骤进行重规划
            stepService.replan(ctx.taskId(), tenantId, plan);
            // 更新任务总步骤数量，并将已完成步骤置0
            taskService.markPlanned(task, plan.size());
            // 构建改后快照
            Map<String, Object> afterData = reimbursementService.buildSnapshot(reimbId);
            // 工单置【AMENDED(修改重跑中)】，重跑计数+1，不覆盖auditorId（保留上一次财务处理人）
            ticket.applySubmitterAmend(ctx.totalAmount(), null);
            // 更新工单信息
            ticketMapper.updateById(ticket);
            // 快照留痕
            insertRecord(ticket, AuditAction.AMEND, before, ctx.totalAmount(), null,
                    userId, username, null, beforeData, afterData);
            log.info("工单 {} 提交人修改重跑 {} → {}（第 {} 次）", ticket.getTicketNo(), before, ctx.totalAmount(), rerun + 1);
            return taskId;
        });
    }

    /**
     * 提交人主动撤回报销单（仅PENDING状态）
     * <p>工单变为WITHDRAWN；任务、报销单置CANCELLED作废。</p>
     * @param reimbId 报销单id
     * @param userId 当前提交人id
     * @param username 当前提交人名称
     * @return 更新后工单VO
     */
    @Transactional
    public AuditTicketVO withdraw(Long reimbId, Long userId, String username) {
        // 根据报销单Id查询任务Id
        Long taskId = reimbursementService.getTaskIdByReimbId(reimbId);
        if (taskId == null) {
            throw new BizException("报销单不存在或未关联审核任务: " + reimbId);
        }
        // 根据任务Id查询工单信息
        AuditTicket holder = getByTask(taskId);
        if (holder == null) {
            throw new BizException("未找到关联审批工单，无法撤回: " + reimbId);
        }
        // 锁内重读数据库实体，禁止使用锁外查询出来的holder做业务判断，防止丢失更新
        return lockTemplate.executeInTx("audit:ticket:" + holder.getId(), () -> {
            // 根据工单ID查询工单信息
            AuditTicket ticket = getRequired(holder.getId());
            // 校验是否本人提交撤回
            if (!userId.equals(ticket.getCreatedBy())) {
                throw new BizException("仅提交人可撤回");
            }
            // 若工单状态非【待审批】，则抛出异常
            if (!AuditTicketStatus.PENDING.name().equals(ticket.getStatus())) {
                throw new BizException("仅待审批状态可撤回（当前 " + ticket.getStatus() + "）");
            }
            // 获取任务信息
            AgentTask task = taskService.getRequired(ticket.getTaskId());
            // 构建报销单数据快照
            Map<String, Object> beforeData = snapshotOf(task);
            // CAS 将任务更新为【已作废】状态并记录原因；竞争失败则整个动作回滚
            if (!taskService.markCancelled(task, "提交人撤回")) {
                throw new BizException("任务状态已变更，请刷新后重试");
            }
            // 将报销单更新【已作废】状态，并解绑相关的附件
            reimbursementService.markCancelledByTaskId(ticket.getTaskId());
            // 将工单状态设置为【已撤回】
            ticket.applyWithdraw(userId, null);
            // 更新工单信息
            ticketMapper.updateById(ticket);
            // 获取当前金额
            BigDecimal amt = currentAmount(ticket);
            // 快照留痕
            Map<String, Object> afterData = snapshotOf(task);
            insertRecord(ticket, AuditAction.WITHDRAW, amt, amt, null, userId, username, null, beforeData, afterData);
            log.info("工单 {} 提交人撤回，任务/报销单作废", ticket.getTicketNo());
            return AuditTicketVO.from(ticket);
        });
    }

    /**
     * 提交人对【已通过】工单发起撤销申请：工单变为WITHDRAW_PENDING，等待财务同意/拒绝。
     * <p>幂等：已经处于WITHDRAW_PENDING直接返回，防前端重复点击。</p>
     * @param reimbId 报销单id
     * @param userId 当前提交人id
     * @param username 当前提交人名称
     * @return 更新后工单VO
     */
    @Transactional
    public AuditTicketVO requestWithdraw(Long reimbId, Long userId, String username) {
        // 根据报销单Id查询任务Id
        Long taskId = reimbursementService.getTaskIdByReimbId(reimbId);
        if (taskId == null) {
            throw new BizException("报销单不存在或未关联审核任务: " + reimbId);
        }
        // 根据任务Id查询工单信息
        AuditTicket holder = getByTask(taskId);
        if (holder == null) {
            throw new BizException("未找到关联审批工单，无法发起撤销: " + reimbId);
        }
        // 锁内重读数据库实体，禁止使用锁外查询出来的holder做业务判断，防止丢失更新
        return lockTemplate.executeInTx("audit:ticket:" + holder.getId(), () -> {
            // 根据工单ID查询工单信息
            AuditTicket ticket = getRequired(holder.getId());
            // 校验是否本人提交撤回
            if (!userId.equals(ticket.getCreatedBy())) {
                throw new BizException("仅提交人可发起撤销申请");
            }
            String status = ticket.getStatus();
            // 若工单已是【撤销待审】状态，直接返回数据
            if (AuditTicketStatus.WITHDRAW_PENDING.name().equals(status)) {
                return AuditTicketVO.from(ticket);
            }
            // 若工单状态非【已通过】，则抛出异常
            if (!AuditTicketStatus.APPROVED.name().equals(status)) {
                throw new BizException("仅已通过状态可发起撤销申请（当前 " + status + "）");
            }
            // 获取任务信息
            AgentTask task = taskService.getRequired(ticket.getTaskId());
            // 构建报销单数据快照
            Map<String, Object> snap = snapshotOf(task);
            // 将任务更新为【待审批】状态，并添加相关备注
            ticket.applyWithdrawRequest(null);
            // 更新工单信息
            ticketMapper.updateById(ticket);
            // 快照留痕
            insertRecord(ticket, AuditAction.WITHDRAW_REQ, currentAmount(ticket), currentAmount(ticket),
                    null, userId, username, null, snap, snap);
            log.info("工单 {} 提交人发起撤销申请", ticket.getTicketNo());
            return AuditTicketVO.from(ticket);
        });
    }

    // ===================== 财务审批动作：approve/reject/terminate / agreeWithdraw / refuseWithdraw =====================

    /**
     * 财务统一审批入口，所有财务操作均走此方法，分布式锁保护。
     * <p>
     * 状态约束：
     * <ul>
     * <li>APPROVE / REJECT / TERMINATE：仅允许PENDING工单</li>
     * <li>WITHDRAW_AGREE / WITHDRAW_REFUSE：仅允许WITHDRAW_PENDING工单</li>
     * </ul>
     * </p>
     * @param id 工单id
     * @param target 审批动作枚举
     * @param userId 财务操作人id
     * @param username 财务操作人名称
     * @param roles 操作人角色（落审计留痕 operatorRoles；快照/JWT 降级源）
     * @param request 审批请求（审批意见等）
     * @return 动作执行完成后的工单VO
     */
    @Transactional
    public AuditTicketVO action(Long id, AuditAction target, Long userId, String username, String roles,
                                AuditActionRequest request) {
        if (target == null) {
            throw new BizException("审批动作不能为空");
        }
        // 锁内重读数据库实体，禁止使用锁外查询出来的holder做业务判断，防止丢失更新
        return lockTemplate.executeInTx("audit:ticket:" + id, () -> {
            // 根据ID查询工单信息
            AuditTicket ticket = getRequired(id);
            // 权限兜底（Controller 已挂 @RequirePerm("audit:approve")；此处防内部直连/遗漏调用，fail-closed）
            if (!UserContextHolder.hasPerm("audit:approve")) {
                throw new BizException("无审批权限：需要 audit:approve 权限");
            }
            // 校验当前工单状态是否允许执行该动作
            if (!allowedByStatus(target, ticket.getStatus())) {
                throw new BizException("工单状态不允许该操作（当前 " + ticket.getStatus() + "）");
            }
            // 根据操作类型执行对应的逻辑
            return switch (target) {
                // 审批通过
                case APPROVE -> approve(ticket, userId, username, roles, request);
                // 审批驳回
                case REJECT -> reject(ticket, userId, username, roles, request);
                // 终止
                case TERMINATE -> terminate(ticket, userId, username, roles, request);
                // 撤销同意
                case WITHDRAW_AGREE -> agreeWithdraw(ticket, userId, username, roles, request);
                // 撤销拒绝
                case WITHDRAW_REFUSE -> refuseWithdraw(ticket, userId, username, roles, request);
                default -> throw new BizException("不支持的动作: " + target);
            };
        });
    }

    /**
     * 校验【动作‑工单状态】合法性
     * @param target 审批动作
     * @param status 当前工单状态字符串
     * @return true允许执行，false不允许
     */
    private static boolean allowedByStatus(AuditAction target, String status) {
        return switch (target) {
            case APPROVE, REJECT, TERMINATE -> AuditTicketStatus.PENDING.name().equals(status);
            case WITHDRAW_AGREE, WITHDRAW_REFUSE -> AuditTicketStatus.WITHDRAW_PENDING.name().equals(status);
            default -> false;
        };
    }

    /**
     * 财务审批通过
     * 工单→APPROVED；任务→SUCCESS；报销单→SUCCESS；写入APPROVE审计留痕。
     */
    private AuditTicketVO approve(AuditTicket ticket, Long userId, String username, String roles,
                                  AuditActionRequest request) {
        // 获取任务信息
        AgentTask task = taskService.getRequired(ticket.getTaskId());
        // CAS 更新任务为成功状态（期望态含 APPROVAL_PENDING），写入相应结果
        // （markApprovalPending阶段已经落库result与finishedSteps，直接复用）；竞争失败则整个动作回滚
        if (!taskService.markSuccess(task, task.getResult(),
                task.getFinishedSteps() == null ? 0 : task.getFinishedSteps())) {
            throw new BizException("任务状态已变更，请刷新后重试");
        }
        // 更新报销单为成功状态
        reimbursementService.updateStatusByTaskId(ticket.getTaskId(), ReimbursementStatus.SUCCESS);
        // 填充ticket信息，工单置为【APPROVED（已通过）】
        ticket.applyAudit(AuditTicketStatus.APPROVED, userId, commentOf(request));
        // 更新工单信息
        ticketMapper.updateById(ticket);
        // 获取当前任务金额
        BigDecimal amt = currentAmount(ticket);
        // 快照留痕
        Map<String, Object> snap = snapshotOf(task);
        insertRecord(ticket, AuditAction.APPROVE, amt, amt, commentOf(request), userId, username, roles, snap, snap);
        log.info("工单 {} 审批通过，任务 {} 置 SUCCESS", ticket.getTicketNo(), task.getTaskNo());
        return AuditTicketVO.from(ticket);
    }

    /**
     * 财务驳回报销
     * 工单→REJECTED；任务→REJECTED；报销单→FAILED；写入REJECT留痕。
     */
    private AuditTicketVO reject(AuditTicket ticket, Long userId, String username, String roles,
                                 AuditActionRequest request) {
        // 根据任务ID查询任务信息
        AgentTask task = taskService.getRequired(ticket.getTaskId());
        // CAS 更新任务为【人工驳回】状态；竞争失败则整个动作回滚
        if (!taskService.markRejected(task)) {
            throw new BizException("任务状态已变更，请刷新后重试");
        }
        // 将报销单设置为【审核失败】状态
        reimbursementService.updateStatusByTaskId(ticket.getTaskId(), ReimbursementStatus.FAILED);
        // 工单状态填充为【已驳回】
        ticket.applyAudit(AuditTicketStatus.REJECTED, userId, commentOf(request));
        // 更新工单信息
        ticketMapper.updateById(ticket);
        // 获取当前任务金额
        BigDecimal amt = currentAmount(ticket);
        // 快照留痕
        Map<String, Object> snap = snapshotOf(task);
        insertRecord(ticket, AuditAction.REJECT, amt, amt, commentOf(request), userId, username, roles, snap, snap);
        log.info("工单 {} 已驳回，任务 {} 置 REJECTED", ticket.getTicketNo(), task.getTaskNo());
        return AuditTicketVO.from(ticket);
    }

    /**
     * 财务强制终止流程（区别驳回：业务强制中止，不允许再修改重跑）
     * 工单→TERMINATED；任务→REJECTED；报销单→FAILED；写入TERMINATE留痕。
     */
    private AuditTicketVO terminate(AuditTicket ticket, Long userId, String username, String roles,
                                    AuditActionRequest request) {
        // 获取任务信息
        AgentTask task = taskService.getRequired(ticket.getTaskId());
        // CAS 任务置为【人工终止】状态；竞争失败则整个动作回滚
        if (!taskService.markTerminated(task)) {
            throw new BizException("任务状态已变更，请刷新后重试");
        }
        // 将报销单设置为【审核失败】状态
        reimbursementService.updateStatusByTaskId(ticket.getTaskId(), ReimbursementStatus.FAILED);
        // 工单状态填充为【已终止】
        ticket.applyAudit(AuditTicketStatus.TERMINATED, userId, commentOf(request));
        // 更新工单信息
        ticketMapper.updateById(ticket);
        // 获取当前任务金额
        BigDecimal amt = currentAmount(ticket);
        // 快照留痕
        Map<String, Object> snap = snapshotOf(task);
        insertRecord(ticket, AuditAction.TERMINATE, amt, amt, commentOf(request), userId, username, roles, snap, snap);
        log.info("工单 {} 已终止，任务 {} 置 REJECTED", ticket.getTicketNo(), task.getTaskNo());
        return AuditTicketVO.from(ticket);
    }

    /**
     * 财务同意提交人的撤销申请
     * 工单 WITHDRAW_PENDING → WITHDRAWN；任务/报销单作废CANCELLED。
     */
    private AuditTicketVO agreeWithdraw(AuditTicket ticket, Long userId, String username, String roles,
                                        AuditActionRequest request) {
        // 获取任务信息
        AgentTask task = taskService.getRequired(ticket.getTaskId());
        // 构建报销单数据快照
        Map<String, Object> beforeData = snapshotOf(task);
        // CAS 将任务更新为【已作废】状态；竞争失败则整个动作回滚
        if (!taskService.markCancelled(task, "财务同意撤销")) {
            throw new BizException("任务状态已变更，请刷新后重试");
        }
        // 将报销单设置为【已作废】状态
        reimbursementService.markCancelledByTaskId(ticket.getTaskId());
        // 工单状态填充为【已撤销】
        ticket.applyWithdraw(userId, commentOf(request));
        // 更新工单信息
        ticketMapper.updateById(ticket);
        // 获取当前任务金额
        BigDecimal amt = currentAmount(ticket);
        // 构建报销单数据快照
        Map<String, Object> afterData = snapshotOf(task);
        insertRecord(ticket, AuditAction.WITHDRAW_AGREE, amt, amt, commentOf(request),
                userId, username, roles, beforeData, afterData);
        log.info("工单 {} 财务同意撤销，任务/报销单作废", ticket.getTicketNo());
        return AuditTicketVO.from(ticket);
    }

    /**
     * 财务拒绝提交人的撤销申请：工单回退APPROVED，业务状态保持不变。
     */
    private AuditTicketVO refuseWithdraw(AuditTicket ticket, Long userId, String username, String roles,
                                         AuditActionRequest request) {
        // 获取任务信息
        AgentTask task = taskService.getRequired(ticket.getTaskId());
        // 工单状态填充为【已通过】（拒绝撤销，回到原状态）
        ticket.applyWithdrawRefuse(commentOf(request));
        // 更新工单信息
        ticketMapper.updateById(ticket);
        // 获取当前任务金额
        BigDecimal amt = currentAmount(ticket);
        // 构建报销单数据快照
        Map<String, Object> snap = snapshotOf(task);
        insertRecord(ticket, AuditAction.WITHDRAW_REFUSE, amt, amt, commentOf(request),
                userId, username, roles, snap, snap);
        log.info("工单 {} 财务拒绝撤销，回 APPROVED", ticket.getTicketNo());
        return AuditTicketVO.from(ticket);
    }

    // ===================== 重跑失败兜底回调：编排器任务失败时调用，防止工单变成死端AMENDED =====================

    /**
     * 重跑失败复位：AMENDED 工单（提交人修改重跑后任务 FAILED）→ 复位 PENDING，追加 RERUN_FAILED 留痕。
     * <p>否则工单永久停 AMENDED：财务不能动作（action 要求 PENDING/WITHDRAW_PENDING）、
     * 提交人不能改（要求 PENDING/REJECTED），变孤儿工单。复位后保留 adjustedAmount/rerun_count，
     * 财务可基于新数据重新审批，提交人也可再改或撤回。由编排器 failTask 调用。</p>
     */
    /**
     * 修改重跑后任务执行失败兜底处理。
     * <p>场景：提交人resubmit，任务重跑直接FAILED；工单停留在AMENDED会变成孤儿：财务不能操作、提交人不能再次resubmit。
     * 处理：工单AMENDED复位PENDING，保留adjustedAmount、rerun_count不变，追加RERUN_FAILED留痕。
     * 由AgentOrchestrator failTask回调调用。</p>
     * @param task 重跑失败的agent任务
     */
    @Transactional
    public void onRerunFail(AgentTask task) {
        // 若不是【报销单】类型，直接结束
        if (!TaskType.REIMBURSEMENT.name().equals(task.getTaskType())) {
            return;
        }
        AuditTicket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<AuditTicket>()
                .eq(AuditTicket::getTaskId, task.getId())
                .last("limit 1"));
        // 若工单不存在或不是AMENDED（修改重跑中）状态，直接结束
        if (ticket == null || !AuditTicketStatus.AMENDED.name().equals(ticket.getStatus())) {
            return;
        }
        // 工单状态填充为【待审核】
        ticket.applyRerunReset();
        // 更新工单信息
        ticketMapper.updateById(ticket);
        // 获取当前任务金额
        BigDecimal amt = currentAmount(ticket);
        // 快照留痕
        Map<String, Object> snap = snapshotOf(task);
        insertRecord(ticket, AuditAction.RERUN_FAILED, amt, amt, null, null, null, null, snap, snap);
        log.info("任务 {} 重跑失败，工单 {} 复位 PENDING（防 AMENDED 死端）", task.getTaskNo(), ticket.getTicketNo());
    }

    // ===================== 内部私有工具方法 =====================

    /**
     * 根据主键查询工单，不存在抛出业务异常
     * <p>租户隔离由mybatis‑plus多租户拦截器自动附加where条件</p>
     * @param id 工单主键
     * @return 工单实体
     */
    private AuditTicket getRequired(Long id) {
        AuditTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BizException("审批工单不存在: " + id);
        }
        return ticket;
    }

    /**
     * 根据taskId查询关联工单：业务1:1，最多返回一条。
     * @param taskId agent任务id
     * @return 工单实体，null代表不存在
     */
    public AuditTicket getByTask(Long taskId) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<AuditTicket>()
                .eq(AuditTicket::getTaskId, taskId)
                .last("limit 1"));
    }

    /**
     * 获取任务原始申报总额，从inputParams.claimedTotal读取
     * 解析异常/空值返回BigDecimal.ZERO
     * @param task agent任务
     * @return 申报金额
     */
    private static BigDecimal claimedTotal(AgentTask task) {
        // 获取申报总额
        Object v = task.getInputParams() == null ? null : task.getInputParams().get("claimedTotal");
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 获取工单当前生效金额：优先取adjustedAmount，没有则取originAmount
     * @param ticket 工单实体
     * @return 当前金额
     */
    private static BigDecimal currentAmount(AuditTicket ticket) {
        return ticket.getAdjustedAmount() != null ? ticket.getAdjustedAmount() : ticket.getOriginAmount();
    }

    /**
     * 安全获取审批意见，request允许为null
     * @param request 审批action请求对象
     * @return comment字符串，null安全
     */
    private static String commentOf(AuditActionRequest request) {
        return request == null ? null : request.comment();
    }

    /**
     * 根据任务inputParams里面reimbId，构建报销业务快照。
     * 构建失败不阻断主流程，打印warn日志返回null。
     * @param task agent任务
     * @return 报销单快照map，失败返回null
     */
    private Map<String, Object> snapshotOf(AgentTask task) {
        if (task == null || task.getInputParams() == null) {
            return null;
        }
        // 获取报销单ID
        Object reimbId = task.getInputParams().get("reimbId");
        if (reimbId == null) {
            return null;
        }
        try {
            // 构建报销单数据快照
            return reimbursementService.buildSnapshot(Long.valueOf(reimbId.toString()));
        } catch (Exception e) {
            log.warn("构建报销单快照失败（不影响主流程）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 审计记录统一插入入口，所有业务动作都经过此方法生成append‑only留痕。
     * @param ticket 工单实体
     * @param action 动作枚举
     * @param before 修改前金额
     * @param after 修改后金额
     * @param comment 操作意见
     * @param operatorId 操作人id
     * @param operatorName 操作人名称
     * @param operatorRoles 操作人角色
     * @param beforeData 修改前业务快照
     * @param afterData 修改后业务快照
     */
    private void insertRecord(AuditTicket ticket, AuditAction action, BigDecimal before, BigDecimal after,
                              String comment, Long operatorId, String operatorName, String operatorRoles,
                              Map<String, Object> beforeData, Map<String, Object> afterData) {
        recordMapper.insert(AuditRecord.ofSnapshot(ticket.getTenantId(), ticket.getId(), action,
                before, after, comment, operatorId, operatorName, operatorRoles, beforeData, afterData));
    }
}

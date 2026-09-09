package com.finaudit.agentcore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.agentcore.config.AgentExecutionProperties;
import com.finaudit.agentcore.domain.AuditConclusion;
import com.finaudit.agentcore.domain.FlowDecision;
import com.finaudit.agentcore.domain.RiskAssessment;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.AgentRole;
import com.finaudit.agentcore.enums.ReimbursementStatus;
import com.finaudit.agentcore.enums.StepStatus;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.starter.mq.message.ToolResultMessage;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.security.PromptInjectionGuard;
import com.finaudit.starter.web.security.PromptInjectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 编排器（核心状态机 + 事件驱动推进）
 * 整体设计说明：
 * 1. 两层状态模型：任务粒度状态 + 步骤粒度状态
 *    任务状态流转：PENDING(待启动) → RUNNING(执行中) → SUCCESS(成功)/FAILED(失败)
 *    步骤状态流转：PENDING(待执行) → RUNNING(执行中) → SUCCESS(成功)/FAILED(失败)；TOOL步骤失败支持重试回RUNNING
 * 2. 两种步骤执行模式：
 *    LLM步骤：本地同步调用大模型内联执行，执行完成直接驱动下一步
 *    TOOL工具步骤：发布MQ事件 tool.execute 交由 tool-service 远程执行；消费 tool.result 事件回调驱动流程推进
 * 3. 核心能力：任务启动、自动链式推进、工具失败重试、断点续跑(resume)、状态持久化落库
 * 4. 事务边界：任务初始化规划步骤加事务，防止步骤生成一半中断导致数据不一致
 * <p>本类只负责跨实体编排，任务/步骤实体的查询与更新分别委托
 * {@link AgentTaskService} / {@link AgentTaskStepService}，不直接触碰 Mapper。</p>
 */
@Component
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** TOOL 步骤最大重试次数 */
    private static final int MAX_RETRY = 3;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentTaskService taskService;
    private final AgentTaskStepService stepService;
    private final TaskPlanner planner;
    /** 规则驱动流水线引擎（REIMBURSEMENT 规划，P3a） */
    private final RuleBasedFlowEngine flowEngine;
    /** 流水线结果分支判定器（REIMBURSEMENT 收尾，P3a） */
    private final ReviewFlowDecider reviewFlowDecider;
    private final TaskEventPublisher eventPublisher;
    // AI大模型客户端，DeepSeek模型实例
    private final AiClient modelClient;
    /** 报销单服务（任务终态回写审核状态，报销域数据访问收敛本服务） */
    private final ReimbursementService reimbursementService;
    /** 审批工单服务（P3b：NEED_REVIEW 进入审批态 / AUTO_PASS 闭合 AMENDED 工单） */
    private final AuditTicketService auditTicketService;
    /** 执行加固配置（P3.5d：任务级超时预算） */
    private final AgentExecutionProperties executionProperties;

    /**
     * 构造注入所有依赖组件
     * @param taskService 任务服务
     * @param stepService 任务步骤服务
     * @param planner 任务步骤规划器（GENERIC）
     * @param flowEngine 规则驱动流水线引擎（REIMBURSEMENT，P3a）
     * @param reviewFlowDecider 流水线结果分支判定器（P3a）
     * @param eventPublisher MQ事件发布组件
     * @param modelFactory 大模型客户端工厂，获取DeepSeek对话实例
     * @param reimbursementService 报销单服务（终态回写审核状态）
     * @param auditTicketService 审批工单服务（P3b 审批态进入/闭合）
     * @param executionProperties 执行加固配置（任务级超时预算）
     */
    public AgentOrchestrator(AgentTaskService taskService, AgentTaskStepService stepService,
                             TaskPlanner planner, RuleBasedFlowEngine flowEngine,
                             ReviewFlowDecider reviewFlowDecider, TaskEventPublisher eventPublisher,
                             ChatClientFactory modelFactory, ReimbursementService reimbursementService,
                             AuditTicketService auditTicketService,
                             AgentExecutionProperties executionProperties) {
        this.taskService = taskService;
        this.stepService = stepService;
        this.planner = planner;
        this.flowEngine = flowEngine;
        this.reviewFlowDecider = reviewFlowDecider;
        this.eventPublisher = eventPublisher;
        this.modelClient = modelFactory.getClient(ModelType.DEEPSEEK);
        this.reimbursementService = reimbursementService;
        this.auditTicketService = auditTicketService;
        this.executionProperties = executionProperties;
    }

    /**
     * 任务启动入口方法
     * 执行流程：
     * 1. 校验任务存在性与状态，仅PENDING待启动任务允许执行
     * 2. 修改任务状态为RUNNING执行中
     * 3. 调用规划器生成执行步骤，批量持久化所有步骤到数据库
     * 4. 更新任务总步骤数、已完成步骤计数
     * 5. 调用推进方法，开始执行第一个步骤
     * @param taskId 任务主键ID
     */
    @Transactional
    public void start(Long taskId) {
        // 校验任务是否存在，不存在时抛出异常
        AgentTask task = taskService.getRequired(taskId);
        // 若非【已提交/待执行】状态则直接结束
        if (!TaskStatus.PENDING.name().equals(task.getStatus())) {
            log.warn("任务 {} 状态为 {}，跳过启动", taskId, task.getStatus());
            return;
        }
        // CAS 修改任务状态为【执行中】：失败说明已被并发实例/线程启动（多实例部署下
        // task.submit 重复消费的防线），放弃本次启动，避免双份规划+双份步骤
        if (!taskService.markRunning(task)) {
            log.warn("任务 {} 启动竞争失败（状态已被并发迁移），跳过", taskId);
            return;
        }
        // 报销单状态同步为【审核中】
        syncReimbStatus(task, ReimbursementStatus.RUNNING);
        /**
         * 报销单类型（固定化流水线步骤）：走flowEngine.plan(task)
         * 通用任务类型（由大模型决定步骤）：走planner.plan(task)
         */
        List<TaskPlanStep> plan = TaskType.of(task.getTaskType()) == TaskType.REIMBURSEMENT
                ? flowEngine.plan(task)
                : planner.plan(task);
        // 将相关子步骤列表进行落库
        stepService.insertPlan(taskId, task.getTenantId(), plan);
        // 更新任务总步骤、已完成初始值0
        taskService.markPlanned(task, plan.size());

        log.info("任务 {} 规划完成，共 {} 步", task.getTaskNo(), plan.size());
        // 驱动流程执行第一个步骤
        continueTask(taskId);
    }

    /**
     * 任务流程推进核心方法
     * 逻辑：找到第一个未成功的步骤进行执行；无剩余步骤则标记任务整体成功结束
     * @param taskId 任务主键ID
     */
    public void continueTask(Long taskId) {
        // 校验任务是否存在
        AgentTask task = taskService.getRequired(taskId);
        // 仅RUNNING执行中任务允许推进，成功/失败终态直接返回
        if (!TaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }
        // 查询当前任务所有步骤，按步骤序号升序
        List<AgentTaskStep> steps = stepService.listByTask(taskId);
        // 筛选第一个未成功的步骤作为待执行步骤
        AgentTaskStep next = steps.stream()
                .filter(s -> !StepStatus.SUCCESS.name().equals(s.getStatus()))
                .findFirst()
                .orElse(null);
        // 所有步骤均执行成功，任务整体收尾（收尾不做超时拦截：工作已完成，失败反而丢结果）
        if (next == null) {
            // 标记任务成功
            finalizeSuccess(task, steps);
            return;
        }
        // 任务级超时防线（P3.5d）：本次执行超过预算仍未到收尾，强制失败终止，
        // 防止重试风暴/极端慢步骤无限占用任务与 MQ 消费线程
        if (isTaskTimeout(task)) {
            failTask(task, "任务执行超时（预算 " + executionProperties.getTaskTimeoutMinutes()
                    + " 分钟），已终止，可通过续跑接口人工恢复");
            return;
        }
        // 当前步骤正在执行中，等待MQ回调结果，不重复分发
        if (StepStatus.RUNNING.name().equals(next.getStatus())) {
            return;
        }
        // 分发执行当前待执行步骤
        dispatch(task, next);
    }

    /**
     * 任务级超时判定：以本次执行开始时间（started_at，启动/重跑刷新）为计时起点；
     * 存量行 started_at 为空时回退 createdAt。预算 &lt;=0 视为关闭检查。
     */
    private boolean isTaskTimeout(AgentTask task) {
        int budget = executionProperties.getTaskTimeoutMinutes();
        if (budget <= 0) {
            return false;
        }
        LocalDateTime startedAt = task.getStartedAt() != null ? task.getStartedAt() : task.getCreatedAt();
        return startedAt != null
                && Duration.between(startedAt, LocalDateTime.now()).toMinutes() >= budget;
    }

    /**
     * 步骤分发器：根据步骤类型分发不同执行逻辑
     * 1. 先将步骤状态更新为RUNNING执行中落库
     * 2. LLM类型：同步本地调用大模型执行
     * 3. TOOL工具类型：发布MQ消息，交由远程工具服务异步执行
     * 4. 未知类型：直接标记任务失败
     * @param task 任务实体
     * @param step 当前待执行步骤实体
     */
    private void dispatch(AgentTask task, AgentTaskStep step) {
        // CAS 更新步骤状态为【执行中】：失败说明该步骤已被并发分发（多实例重复投递防线），
        // 放弃执行——否则 LLM 步骤会双份调用计费、TOOL 步骤会双份执行
        if (!stepService.markRunning(step)) {
            log.warn("步骤 {} 分发竞争失败（状态已被并发迁移），跳过执行", step.getId());
            return;
        }
        // 若为LLM类型，执行LLM步骤
        if ("LLM".equalsIgnoreCase(step.getStepType())) {
            executeLlmStep(task, step);
        } else if ("TOOL".equalsIgnoreCase(step.getStepType())) {
            // 若为TOOL类型，发布MQ消息，交由远程工具服务异步执行（ToolExecuteConsumer）
            eventPublisher.publishToolExecute(task, step);
        } else {
            // 若为未知类型，直接标记任务失败
            failTask(task, "步骤[" + step.getStepName() + "] 未知类型: " + step.getStepType());
        }
    }

    /**
     * 同步执行LLM大模型步骤
     * 流程：
     * 1. 组装系统提示词、用户任务上下文
     * 2. 调用DeepSeek大模型获取输出文本
     * 3. 步骤输出结果入库，状态置SUCCESS
     * 4. 更新任务已完成步骤计数，自动推进下一个步骤
     * 异常捕获：大模型调用失败时，步骤标记FAILED，任务整体失败
     * @param task 所属任务
     * @param step 当前LLM步骤实体
     */
    private void executeLlmStep(AgentTask task, AgentTaskStep step) {
        try {
            // 组装审核上下文：任务入参 + 前序步骤结果（尤其 TOOL 核验结果）+ 当前步骤入参，
            // 避免 LLM 步骤因看不到数据而误判"入参不完整"
            StringBuilder ctx = new StringBuilder();
            ctx.append("【任务入参】\n").append(toJsonText(task.getInputParams())).append("\n");
            List<AgentTaskStep> done = stepService.listByTask(task.getId()).stream()
                    .filter(s -> StepStatus.SUCCESS.name().equals(s.getStatus()))
                    .toList();
            if (!done.isEmpty()) {
                ctx.append("【前序步骤结果】\n");
                for (AgentTaskStep s : done) {
                    ctx.append("- 步骤").append(s.getStepNo()).append(" ").append(s.getStepName())
                            .append(" 输出: ").append(s.getOutput() == null ? "（无）" : toJsonText(s.getOutput()))
                            .append("\n");
                }
            }
            if (step.getInputParams() != null) {
                ctx.append("【当前步骤入参】\n").append(toJsonText(step.getInputParams())).append("\n");
            }
            String user = "任务标题：" + task.getTitle() + "\n" + ctx;

            /**
             * 按步骤 agentRole 选 system prompt 与结构化输出形状。
             * 风控语义步骤(RISK_AUDITOR) → RiskAssessment（存疑标记/置信度）；
             * 其余（SCHEDULER/无角色，
             */
            // 获取执行步骤中设定的Agent角色
            AgentRole role = AgentRole.of(step.getAgentRole());
            // 若未设置角色，默认为SCHEDULER
            String system = (role == null || role.systemPrompt().isBlank())
                    ? AgentRole.SCHEDULER.systemPrompt()
                    : role.systemPrompt();

            // 识别传入的Prompt是否有注入风险
            PromptInjectionResult injection = PromptInjectionGuard.scan("LLM_STEP", user);
            // 存在风险
            if (injection.hit()) {
                log.warn("检测到疑似Prompt注入，强制人工复核: step={}, detail={}",
                        step.getStepName(), injection.detail());
                Object data;
                // 若角色为风控智能体，则直接构造风险结果，不调用模型
                if (role == AgentRole.RISK_AUDITOR) {
                    data = new RiskAssessment("HIGH", BigDecimal.ZERO, true,
                            "检测到疑似Prompt注入，已强制人工复核", List.of(injection.detail()));
                }
                // 若为其他智能体，则构造相应结果，不调用模型
                else {
                    data = new AuditConclusion("检测到疑似Prompt注入，已强制人工复核", "NEED_INFO",
                            null, null, null, null, List.of(injection.detail()), null);
                }
                // 将data转为Map<String, Object> 入库，状态置SUCCESS
                // CAS 失败说明步骤已被并发迁移（如断点续跑重置），丢弃本次结果避免脏写
                if (!stepService.markSuccess(step,
                        OBJECT_MAPPER.convertValue(data, new TypeReference<Map<String, Object>>() {}))) {
                    log.warn("步骤 {} 结果写入竞争失败（状态已被并发迁移），丢弃", step.getId());
                    return;
                }
                // 刷新完成的步骤数
                refreshFinishedSteps(task.getId());
                // 正常推进（finalizeSuccess 将按 NEED_REVIEW 进入审批工单）
                continueTask(task.getId());
                return;
            }

            // 未触发Prompt注入风险
            Object data;
            // 若为风控Agent，则返回RiskAssessment结构体（包含置信度等）
            if (role == AgentRole.RISK_AUDITOR) {
                data = modelClient.chatStructured(system, user, RiskAssessment.class).data();
            }
            // 若为统筹调度Agent，则返回AuditConclusion
            else {
                data = modelClient.chatStructured(system, user, AuditConclusion.class).data();
            }
            // 将data转为Map<String, Object> 入库，状态置SUCCESS（CAS 同上，竞争失败丢弃）
            if (!stepService.markSuccess(step,
                    OBJECT_MAPPER.convertValue(data, new TypeReference<Map<String, Object>>() {}))) {
                log.warn("步骤 {} 结果写入竞争失败（状态已被并发迁移），丢弃", step.getId());
                return;
            }
            // 刷新任务已完成步骤数量
            refreshFinishedSteps(task.getId());
            // 自动推进执行下一个步骤
            continueTask(task.getId());
        } catch (Exception e) {
            log.error("LLM 步骤 {} 失败: {}", step.getStepName(), e.getMessage(), e);
            // 异常记录步骤错误信息，标记步骤失败（CAS 失败=已被并发迁移，放弃任务级失败联动）
            if (stepService.markFailed(step, e.getMessage())) {
                // 整个任务标记失败终止流程
                failTask(task, "LLM 步骤[" + step.getStepName() + "] 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 对象 → JSON 文本（用于 LLM 步骤上下文），序列化失败时原样输出。
     */
    private String toJsonText(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    /**
     * MQ回调消费方法：接收tool-service返回的工具执行结果消息
     * 分支逻辑：
     * 1. 工具执行成功：步骤标记成功，刷新计数，自动推进下一步
     * 2. 工具执行失败：判断是否达到最大重试次数
     *    - 未超限：重试次数+1，重新发布MQ消息再次执行工具
     *    - 已超限：步骤标记失败，整个任务终止失败
     * @param msg MQ工具结果消息体，包含任务ID、步骤ID、执行结果、错误信息
     */
    public void onToolResult(ToolResultMessage msg) {
        // 根据stepId查询步骤信息
        AgentTaskStep step = stepService.findById(msg.stepId());
        // 不存在直接结束
        if (step == null) {
            log.warn("收到 tool.result 但步骤不存在: stepId={}", msg.stepId());
            return;
        }
        // 校验任务是否存在，不存在抛出异常
        AgentTask task = taskService.getRequired(msg.taskId());
        // 迟到回调防御（P3.5d 收紧为白名单）：仅 RUNNING 任务允许消费工具结果。
        // 此前只拉黑 CANCELLED/REJECTED，FAILED/APPROVAL_PENDING/SUCCESS 的迟到结果仍会把
        // 步骤改写成 SUCCESS（脏状态）；改为白名单后一律丢弃。
        String taskStatus = task.getStatus();
        if (!TaskStatus.RUNNING.name().equals(taskStatus)) {
            log.warn("任务 {} 非执行中（{}），丢弃迟到的 tool.result: stepId={}",
                    msg.taskId(), taskStatus, msg.stepId());
            return;
        }

        // 若工具执行成功
        if (msg.success()) {
            // CAS 更新步骤为成功：失败说明步骤已被并发迁移（重复投递/迟到结果），丢弃本次结果
            if (!stepService.markSuccess(step, msg.result())) {
                log.warn("步骤 {} 结果写入竞争失败（状态已被并发迁移），丢弃 tool.result", msg.stepId());
                return;
            }
            // 刷新任务已完成步骤数量
            refreshFinishedSteps(task.getId());
            log.info("工具 {} 步骤成功: stepId={}", msg.toolCode(), msg.stepId());
            // 自动推进执行下一个步骤
            continueTask(task.getId());
            return;
        }

        // 若工具执行失败，执行重试机制（最多3次）
        int retry = step.getRetryCount() == null ? 0 : step.getRetryCount();
        if (retry < MAX_RETRY) {
            // 重试次数自增，CAS 重置步骤状态为执行中；失败则不重发，防双份工具执行
            if (!stepService.markRetrying(step, retry + 1, msg.errorMsg())) {
                log.warn("步骤 {} 重试置位竞争失败（状态已被并发迁移），放弃重试", msg.stepId());
                return;
            }
            log.warn("工具 {} 失败，第 {} 次重试: {}", msg.toolCode(), retry + 1, msg.errorMsg());
            eventPublisher.publishToolExecute(task, step);
        } else {
            // 重试次数已达到最大次数，CAS 标记步骤失败；失败说明已被并发迁移，放弃任务级联动
            if (stepService.markFailed(step, msg.errorMsg())) {
                // 任务终止失败
                failTask(task, "步骤[" + step.getStepName() + "] 重试 " + MAX_RETRY
                        + " 次仍失败: " + msg.errorMsg());
            }
        }
    }

    /**
     * 任务断点续跑/恢复入口
     * 使用场景：服务重启、MQ消息丢失、进程崩溃后恢复任务执行
     * 逻辑分支：
     * 1. 任务已成功/失败终态：抛出业务异常禁止恢复
     * 2. 任务PENDING未启动：直接走完整start启动流程
     * 3. 任务RUNNING执行中：重置所有残留RUNNING步骤为PENDING（清理中断中的步骤），重新驱动流程推进
     * @param taskId 待恢复任务ID
     * @throws BizException 任务已终结时抛出异常
     */
    public void resume(Long taskId) {
        // 校验任务是否存在
        AgentTask task = taskService.getRequired(taskId);
        // 终态任务不允许续跑（SUCCESS/FAILED；P3a 起含 APPROVAL_PENDING 待审批 / REJECTED 人工驳回；
        // P3b 含 CANCELLED 已作废——防对撤回/撤销的作废任务误触发重跑）
        if (TaskStatus.SUCCESS.name().equals(task.getStatus())
                || TaskStatus.FAILED.name().equals(task.getStatus())
                || TaskStatus.APPROVAL_PENDING.name().equals(task.getStatus())
                || TaskStatus.REJECTED.name().equals(task.getStatus())
                || TaskStatus.CANCELLED.name().equals(task.getStatus())) {
            throw new BizException(
                    "任务已终结（" + task.getStatus() + "），无需续跑");
        }
        // 从未启动的任务，直接完整启动
        if (TaskStatus.PENDING.name().equals(task.getStatus())) {
            start(taskId);
            return;
        }
        // 将状态为【执行中】的任务重置为【待执行】
        stepService.listByTask(taskId).stream()
                .filter(s -> StepStatus.RUNNING.name().equals(s.getStatus()))
                .forEach(stepService::resetRunningToPending);
        // CAS 重回【执行中】并刷新本次执行开始时间（续跑重新计时）；竞争失败则放弃续跑
        if (!taskService.markRunning(task)) {
            log.warn("任务 {} 续跑竞争失败（状态已被并发迁移），跳过", taskId);
            return;
        }
        // 自动推进执行下一个步骤
        continueTask(taskId);
    }

    // ---------- 内部 ----------

    /**
     * 任务全部步骤执行完成，标记任务成功收尾
     * 1. 组装全步骤执行结果明细
     * 2. 写入任务result字段保存完整流程输出
     * 3. 更新任务状态SUCCESS、已完成步骤总数
     * @param task 任务实体
     * @param steps 任务全部步骤列表
     */
    private void finalizeSuccess(AgentTask task, List<AgentTaskStep> steps) {
        List<Map<String, Object>> stepResults = steps.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepNo", s.getStepNo());
            m.put("stepName", s.getStepName());
            m.put("stepType", s.getStepType());
            m.put("toolName", s.getToolName());
            m.put("agentRole", s.getAgentRole());
            m.put("output", s.getOutput());
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "审核流程执行完成");
        result.put("stepCount", steps.size());
        result.put("steps", stepResults);

        // P3a 结果分支：REIMBURSEMENT 走确定性判定（AUTO_PASS / NEED_REVIEW），GENERIC 维持原 LLM 决策回写
        if (TaskType.REIMBURSEMENT.name().equals(task.getTaskType())) {
            // 执行步骤，获取判定结果
            FlowDecision decision = reviewFlowDecider.decide(steps);
            result.put("flowBranch", decision.flowBranch());
            result.put("reviewReasons", decision.reviewReasons());
            // 需要人工审核
            if (FlowDecision.NEED_REVIEW.equals(decision.flowBranch())) {
                // 流水线判定 NEED_REVIEW 时进入审批态（命中触发条件（大额/超标/风控存疑）或 LLM 结论非通过 → 生成审批工单进入审批态，终审权在人）
                auditTicketService.enterApproval(task, result, steps.size(), decision.reviewReasons());
                log.info("任务 {} 命中人工复核分支，原因: {}", task.getTaskNo(), decision.reviewReasons());
                return;
            }
            // CAS 更新任务为成功状态（AUTO_PASS）
            if (!taskService.markSuccess(task, result, steps.size())) {
                log.warn("任务 {} 收尾竞争失败（状态已被并发迁移），放弃 AUTO_PASS 收尾", task.getId());
                return;
            }
            log.info("任务 {} 自动通过（AUTO_PASS），共 {} 步", task.getTaskNo(), steps.size());
            // 报销单审核状态回写
            syncReimbStatus(task, ReimbursementStatus.SUCCESS);
            // amend 重跑自动通过：闭合 AMENDED 工单（PENDING 不可能任务 SUCCESS，安全）
            auditTicketService.closeOnAutoPass(task);
            return;
        }

        // CAS 更新任务为成功（GENERIC）
        if (!taskService.markSuccess(task, result, steps.size())) {
            log.warn("任务 {} 收尾竞争失败（状态已被并发迁移），放弃收尾", task.getId());
            return;
        }
        log.info("任务 {} 执行成功，共 {} 步", task.getTaskNo(), steps.size());
        // 报销单状态回写：按 LLM 汇总决策细化（REJECT→FAILED、NEED_INFO→MANUAL_REVIEW，其余→SUCCESS）
        syncReimbStatus(task, resolveSuccessStatus(extractDecision(steps)));
    }

    /**
     * 标记任务整体失败，终止整个流程
     * @param task 待失败任务实体
     * @param error 失败原因描述
     */
    private void failTask(AgentTask task, String error) {
        // CAS 标记失败：竞争失败说明任务已被并发迁移（如已作废），放弃全部失败联动
        if (!taskService.markFailed(task, error)) {
            log.warn("任务 {} 失败标记竞争失败（状态已被并发迁移），放弃失败联动", task.getId());
            return;
        }
        log.error("任务 {} 失败: {}", task.getTaskNo(), error);
        // 报销单状态回写【审核失败】
        syncReimbStatus(task, ReimbursementStatus.FAILED);
        // 提交人修改重跑（AMENDED 工单）失败时复位工单 PENDING + RERUN_FAILED 留痕，
        // 防工单永久停 AMENDED 变孤儿（财务不能动作、提交人不能再改）
        auditTicketService.onRerunFail(task);
    }

    /**
     * 报销单审核状态回写（仅 REIMBURSEMENT 类任务；回写失败不阻断任务本身）。
     * 报销域数据访问收敛：更新仅经 {@link ReimbursementService}，本类不触碰报销单 Mapper。
     * @param task 当前任务实体
     * @param status 目标报销单审核状态
     */
    private void syncReimbStatus(AgentTask task, ReimbursementStatus status) {
        if (!TaskType.REIMBURSEMENT.name().equals(task.getTaskType())) {
            return;
        }
        try {
            reimbursementService.updateStatusByTaskId(task.getId(), status);
        } catch (Exception e) {
            log.warn("报销单状态回写失败（不影响任务结果）: taskId={} status={}: {}",
                    task.getId(), status, e.getMessage());
        }
    }

    /**
     * 任务成功时按 LLM 汇总决策细化报销单状态：
     * REJECT→FAILED（审核失败）、NEED_INFO→MANUAL_REVIEW（人工复核）、APPROVE/无决策→SUCCESS（审核通过）。
     * @param decision LLM 汇总步骤的 decision 字段（可空）
     */
    private static ReimbursementStatus resolveSuccessStatus(String decision) {
        if (decision == null) {
            return ReimbursementStatus.SUCCESS;
        }
        String d = decision.toUpperCase();
        if ("REJECT".equals(d)) {
            return ReimbursementStatus.FAILED;
        }
        if ("NEED_INFO".equals(d)) {
            return ReimbursementStatus.MANUAL_REVIEW;
        }
        return ReimbursementStatus.SUCCESS;
    }

    /**
     * 提取最后一个 LLM 步骤输出中的 decision 字段（汇总审核结论步骤）。
     * 无 LLM 步骤或输出缺少 decision 返回 null，由调用方兜底。
     * 兼容低版本编译器：用类型测试 + 转型，避免 instanceof 绑定模式。
     * @param steps 任务全部步骤（含结果）
     */
    private static String extractDecision(List<AgentTaskStep> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            AgentTaskStep s = steps.get(i);
            if ("LLM".equalsIgnoreCase(s.getStepType()) && s.getOutput() instanceof Map<?, ?>) {
                Object d = ((Map<?, ?>) s.getOutput()).get("decision");
                return d == null ? null : d.toString();
            }
        }
        return null;
    }

    /**
     * 重新统计并更新任务已完成步骤数量
     * 遍历所有步骤，统计SUCCESS状态步骤数量更新到任务表
     * @param taskId 任务ID
     */
    private void refreshFinishedSteps(Long taskId) {
        AgentTask task = taskService.getRequired(taskId);
        int finished = (int) stepService.listByTask(taskId).stream()
                .filter(s -> StepStatus.SUCCESS.name().equals(s.getStatus()))
                .count();
        taskService.setFinishedSteps(task, finished);
    }
}

package com.finaudit.agentcore.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.agentcore.enums.StepStatus;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.starter.mq.message.ToolResultMessage;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.web.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private final TaskEventPublisher eventPublisher;
    // AI大模型客户端，DeepSeek模型实例
    private final AiClient modelClient;

    /**
     * 构造注入所有依赖组件
     * @param taskService 任务服务
     * @param stepService 任务步骤服务
     * @param planner 任务步骤规划器
     * @param eventPublisher MQ事件发布组件
     * @param modelFactory 大模型客户端工厂，获取DeepSeek对话实例
     */
    public AgentOrchestrator(AgentTaskService taskService, AgentTaskStepService stepService,
                             TaskPlanner planner, TaskEventPublisher eventPublisher,
                             ChatClientFactory modelFactory) {
        this.taskService = taskService;
        this.stepService = stepService;
        this.planner = planner;
        this.eventPublisher = eventPublisher;
        this.modelClient = modelFactory.getClient(ModelType.DEEPSEEK);
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
        // 修改任务状态为【执行中】
        taskService.markRunning(task);
        // 规划器生成完整步骤清单（涉及模型调用操作）
        List<TaskPlanStep> plan = planner.plan(task);
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
        // 所有步骤均执行成功，任务整体收尾
        if (next == null) {
            // 标记任务成功
            finalizeSuccess(task, steps);
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
     * 步骤分发器：根据步骤类型分发不同执行逻辑
     * 1. 先将步骤状态更新为RUNNING执行中落库
     * 2. LLM类型：同步本地调用大模型执行
     * 3. TOOL工具类型：发布MQ消息，交由远程工具服务异步执行
     * 4. 未知类型：直接标记任务失败
     * @param task 任务实体
     * @param step 当前待执行步骤实体
     */
    private void dispatch(AgentTask task, AgentTaskStep step) {
        // 更新步骤状态为【执行中】
        stepService.markRunning(step);
        // 若为LLM类型，执行LLM步骤
        if ("LLM".equalsIgnoreCase(step.getStepType())) {
            executeLlmStep(task, step);
        } else if ("TOOL".equalsIgnoreCase(step.getStepType())) {
            // 若为TOOL类型，发布MQ消息，交由远程工具服务异步执行
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
            String system = """
                    你是财务审核 Agent。基于任务入参与前序步骤结果，给出确定性、可执行的审核结论。
                    金额核验等工具已给出量化结果（match/total/diff）时，以该结果为准下结论，不要以"缺少单据/发票/审批材料"为由拒绝给出结论；
                    若入参确实不足，明确指出缺少的具体字段，而非笼统索要材料。输出简洁专业。""";
            String user = "任务标题：" + task.getTitle() + "\n" + ctx;
            // 发起模型调用
            String text = modelClient.chat(system, user);
            // 保存模型输出结果，步骤置成功
            stepService.markSuccess(step, Map.of("content", text));
            // 刷新任务已完成步骤数量
            refreshFinishedSteps(task.getId());
            // 自动推进执行下一个步骤
            continueTask(task.getId());
        } catch (Exception e) {
            log.error("LLM 步骤 {} 失败: {}", step.getStepName(), e.getMessage(), e);
            // 异常记录步骤错误信息，标记步骤失败
            stepService.markFailed(step, e.getMessage());
            // 整个任务标记失败终止流程
            failTask(task, "LLM 步骤[" + step.getStepName() + "] 失败: " + e.getMessage());
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

        // 若工具执行成功
        if (msg.success()) {
            // 更新步骤为成功
            stepService.markSuccess(step, msg.result());
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
            // 重试次数自增，重置步骤状态为执行中，重新发布执行事件
            stepService.markRetrying(step, retry + 1, msg.errorMsg());
            log.warn("工具 {} 失败，第 {} 次重试: {}", msg.toolCode(), retry + 1, msg.errorMsg());
            eventPublisher.publishToolExecute(task, step);
        } else {
            // 重试次数已达到最大次数，标记步骤失败
            stepService.markFailed(step, msg.errorMsg());
            // 任务终止失败
            failTask(task, "步骤[" + step.getStepName() + "] 重试 " + MAX_RETRY
                    + " 次仍失败: " + msg.errorMsg());
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
        // 终态任务不允许续跑（SUCCESS/FAILED）
        if (TaskStatus.SUCCESS.name().equals(task.getStatus())
                || TaskStatus.FAILED.name().equals(task.getStatus())) {
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
        // 重新驱动任务执行
        taskService.markRunning(task);
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
            m.put("output", s.getOutput());
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "审核流程执行完成");
        result.put("stepCount", steps.size());
        result.put("steps", stepResults);
        taskService.markSuccess(task, result, steps.size());
        log.info("任务 {} 执行成功，共 {} 步", task.getTaskNo(), steps.size());
    }

    /**
     * 标记任务整体失败，终止整个流程
     * @param task 待失败任务实体
     * @param error 失败原因描述
     */
    private void failTask(AgentTask task, String error) {
        taskService.markFailed(task, error);
        log.error("任务 {} 失败: {}", task.getTaskNo(), error);
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

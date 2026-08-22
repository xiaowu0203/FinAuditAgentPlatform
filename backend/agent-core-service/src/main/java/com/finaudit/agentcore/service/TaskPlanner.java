package com.finaudit.agentcore.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.model.client.StructuredChatReply;
import com.finaudit.starter.web.feign.ToolServiceFeign;
import com.finaudit.starter.web.feign.dto.ToolInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务规划器：用 LLM 把任务拆解为有序步骤。
 * <p>输出形状由 Spring AI {@code BeanOutputConverter} 依据 {@link TaskPlanStep} 生成的
 * JSON Schema 约束（结构化输出），解析失败回退内置模板（明细金额 → 金额核验 + 汇总），
 * 保证 P1 闭环可用。</p>
 * <p>P2a 起按业务类型分派：{@link TaskType#REIMBURSEMENT} 注入财务专用提示词段 + 财务工具集，
 * {@link TaskType#GENERIC} 走通用分析且不注入财务工具，避免不同业务共用同一份写死提示词导致规划漂移。</p>
 */
@Component
public class TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanner.class);

    private final AiClient modelClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolServiceFeign toolServiceFeign;

    /** 工具目录拉取失败时的降级工具（保证 LLM 仍能规划出 amount_verify，闭环可用） */
    private static final ToolInfo DEFAULT_AMOUNT_VERIFY = new ToolInfo(
            "amount_verify", "金额核验工具",
            "加总明细金额并与申报总额比对，返回是否一致及差额。入参 items:[{name,amount}] + claimedTotal。",
            null, "FINANCE");

    public TaskPlanner(ChatClientFactory modelFactory, ToolServiceFeign toolServiceFeign) {
        this.modelClient = modelFactory.getClient(ModelType.DEEPSEEK);
        this.toolServiceFeign = toolServiceFeign;
    }

    /**
     * 规划任务执行步骤。
     * <p>按业务类型注入对应提示词指令段与工具集：报销审核走财务专用提示词 + 财务工具，
     * 通用任务不注入财务工具（防止 LLM 在非报销业务上规划出财务工具步骤）。</p>
     */
    public List<TaskPlanStep> plan(AgentTask task) {
        List<ToolInfo> tools = List.of();
        try {
            TaskType taskType = TaskType.of(task.getTaskType());
            // 动态拉取工具目录（经 Feign 读 tool-service；失败降级内置工具，保证闭环）
            try {
                List<ToolInfo> fetched = toolServiceFeign.listEnabled(task.getTenantId()).getData();
                tools = fetched == null ? List.of() : fetched;
            } catch (Exception e) {
                log.warn("获取工具目录失败，降级内置工具: {}", e.getMessage());
                tools = List.of(DEFAULT_AMOUNT_VERIFY);
            }
            // 按业务类型收敛工具目录，防提示词膨胀与工具漂移
            tools = filterTools(taskType, tools);
            log.info("任务[{}] {} 规划拉取工具 {} 个：{}", task.getId(), taskType, tools.size(),
                    tools.stream().map(ToolInfo::toolCode).collect(Collectors.joining(",")));
            String toolBlock = tools.stream()
                    .map(t -> "- " + t.toolCode() + "（" + t.toolName() + "）：" + t.description()
                            + (t.inputSchema() == null ? "" : "\n  入参 Schema：" + toJson(t.inputSchema())))
                    .collect(Collectors.joining("\n"));
            // 系统提示词 = 公共行为约束 + 业务指令段 + 工具目录 + 设计原则，输出形状由自动生成的 JSON Schema 接管
            String system = """
                    你是财务审核 Agent 的任务规划器。将任务拆解为有序执行步骤，步骤要最小化、无冗余。
                    %s
                    可用工具（TOOL 步骤的 toolName 只能填以下编码，且必须带对应 inputParams）：
                    %s
                    设计原则：
                    1. 能调用工具完成核验时，先规划 TOOL 步骤，最后放一个 LLM 步骤汇总审核结论；
                    2. LLM 步骤只允许一个，放在最后做汇总结论；除非工具入参确需前置整理，才允许额外一个前置 LLM；
                    3. 禁止添加与汇总步骤职责重叠的前置"提取/核对/初步分析"LLM 步骤；
                    4. TOOL 步骤必须带 inputParams；LLM 步骤可省略 toolName 与 inputParams。
                    只输出符合下方 JSON Schema 的步骤数组，不要输出任何其他内容。
                    """.formatted(businessInstruction(taskType), toolBlock);
            String user = "任务标题：" + task.getTitle() + "\n任务入参：\n" + toJson(task.getInputParams());
            // 结构化输出：Schema 注入提示词，模型回复反序列化为 List<TaskPlanStep>
            StructuredChatReply<List<TaskPlanStep>> reply = modelClient.chatStructured(
                    system, user, new ParameterizedTypeReference<List<TaskPlanStep>>() {
                    });
            List<TaskPlanStep> steps = reply.data();
            // 若结果为空，则走内置回退模板（回退结果同样过清洗）
            if (steps == null || steps.isEmpty()) {
                return sanitize(fallback(task.getInputParams()), tools);
            }
            log.info("LLM 规划成功，共 {} 步", steps.size());
            // P3a 规划层校验：剔除 LLM 虚构/目录外工具步骤（根治工具幻觉，见 future-roadmap 登记项）；
            // LLM 无权指派角色，步骤 agentRole 统一置 null（角色化仅 RuleBasedFlowEngine 绑定）
            return sanitize(steps, tools);
        } catch (Exception e) {
            log.warn("LLM 拆解失败，回退内置模板: {}", e.getMessage());
            // 若异常，则回退内置模板并沿用已获取的工具目录进行清洗
            return sanitize(fallback(task.getInputParams()), tools);
        }
    }

    /**
     * 按业务类型收敛工具目录：报销审核保留全部（财务场景）；通用任务过滤财务专属工具，
     * 防止 LLM 在非报销业务上规划出金额核验/OCR 等财务步骤。
     * <p>P2b 起元数据驱动：工具场景标签来自 tool_registry（scenario），
     * scenario=FINANCE 视为财务专用；null/blank 视为通用工具（GENERIC 任务保留）。</p>
     */
    static List<ToolInfo> filterTools(TaskType taskType, List<ToolInfo> tools) {
        if (tools == null || tools.isEmpty() || taskType == TaskType.REIMBURSEMENT) {
            return tools;
        }
        // GENERIC：仅保留非财务通用工具（无通用工具时目录为空 → 纯 LLM 分析）
        return tools.stream()
                .filter(t -> !t.isFinance())
                .toList();
    }

    /**
     * 规划步骤清洗（P3a 工具幻觉根治）：
     * 1. TOOL 步骤 toolName 不在有效工具目录（含 null）→ 剔除并告警——LLM 在目录为空时会虚构工具编码致任务失败；
     * 2. LLM 步骤保留，agentRole 统一置 null（LLM 无权指派角色，角色化仅 RuleBasedFlowEngine 绑定）；
     * 3. 清洗后为空 → 兜底单 LLM 分析步骤（纯 LLM 任务仍可完成）。
     *
     * @param validTools 有效工具目录（已按业务类型收敛，filterTools 之后）
     */
    private static List<TaskPlanStep> sanitize(List<TaskPlanStep> steps, List<ToolInfo> validTools) {
        if (steps == null || steps.isEmpty()) {
            return steps;
        }
        Set<String> validCodes = validTools == null ? Set.of()
                : validTools.stream().map(ToolInfo::toolCode).collect(Collectors.toSet());
        List<TaskPlanStep> clean = new ArrayList<>();
        for (TaskPlanStep s : steps) {
            if ("TOOL".equalsIgnoreCase(s.stepType())
                    && (s.toolName() == null || !validCodes.contains(s.toolName()))) {
                log.warn("剔除幻觉工具步骤[{}] toolName={}（不在工具目录）", s.stepName(), s.toolName());
                continue;
            }
            clean.add(new TaskPlanStep(s.stepName(), s.stepType(), s.toolName(), s.inputParams(), null));
        }
        if (clean.isEmpty()) {
            log.warn("规划步骤清洗后为空，兜底单 LLM 分析步骤");
            clean.add(new TaskPlanStep("任务分析", "LLM", null, null, null));
        }
        return clean;
    }

    /** 业务指令段：按业务类型注入，让 LLM 明确业务场景与产出目标。 */
    private static String businessInstruction(TaskType taskType) {
        return switch (taskType) {
            case REIMBURSEMENT -> """
                    业务场景：报销单审核。依据报销单明细、附件引用与申报总额，逐项核对费用合规与金额一致性，
                    汇总输出审核结论（是否通过、差异与疑点）。
                    """;
            case GENERIC -> """
                    业务场景：通用任务分析。依据任务入参完成分析或核验，不要假设财务报销场景，无财务工具可用。
                    """;
        };
    }

    /**
     * 内置模板回退：入参含 items + claimedTotal 时 → 金额核验 TOOL + LLM 汇总；否则仅 LLM 分析。
     */
    private List<TaskPlanStep> fallback(Map<String, Object> input) {
        List<TaskPlanStep> steps = new ArrayList<>();
        if (input != null && input.containsKey("items") && input.containsKey("claimedTotal")) {
            steps.add(new TaskPlanStep("金额核验", "TOOL", "amount_verify", input));
            steps.add(new TaskPlanStep("审核结论汇总", "LLM", null, null));
        } else {
            steps.add(new TaskPlanStep("任务分析", "LLM", null, null));
        }
        log.info("使用内置模板规划，共 {} 步", steps.size());
        return steps;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}

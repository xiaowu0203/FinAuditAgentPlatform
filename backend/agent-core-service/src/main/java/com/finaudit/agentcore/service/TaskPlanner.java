package com.finaudit.agentcore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.starter.web.feign.ToolServiceFeign;
import com.finaudit.starter.web.feign.dto.ToolInfo;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 任务规划器：用 LLM 把任务拆解为有序步骤（结构化 JSON）。
 * <p>解析失败回退内置模板（明细金额 → 金额核验 + 汇总），保证 P1 闭环可用。</p>
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
            null);

    public TaskPlanner(ChatClientFactory modelFactory, ToolServiceFeign toolServiceFeign) {
        this.modelClient = modelFactory.getClient(ModelType.DEEPSEEK);
        this.toolServiceFeign = toolServiceFeign;
    }

    /**
     * 规划任务执行步骤。
     */
    public List<TaskPlanStep> plan(AgentTask task) {
        try {
            // 动态拉取工具目录（经 Feign 读 tool-service；失败降级内置工具，保证闭环）
            List<ToolInfo> tools;
            try {
                tools = toolServiceFeign.listEnabled(task.getTenantId()).getData();
            } catch (Exception e) {
                log.warn("获取工具目录失败，降级内置工具: {}", e.getMessage());
                tools = List.of(DEFAULT_AMOUNT_VERIFY);
            }
            log.info("规划拉取工具目录 {} 个：{}", tools.size(),
                    tools.stream().map(ToolInfo::toolCode).collect(Collectors.joining(",")));
            String toolBlock = tools.stream()
                    .map(t -> "- " + t.toolCode() + "（" + t.toolName() + "）：" + t.description()
                            + (t.inputSchema() == null ? "" : "\n  入参 Schema：" + toJson(t.inputSchema())))
                    .collect(Collectors.joining("\n"));
            String system = """
                    你是财务审核 Agent 的任务规划器。将报销审核任务拆解为有序执行步骤，步骤要最小化、无冗余。
                    只能输出一个 JSON 数组，不要输出任何其他内容，不要用代码块包裹。
                    数组元素结构：{"stepName":"步骤名","stepType":"LLM或TOOL","toolName":"TOOL步骤的工具编码","inputParams":{工具入参}}
                    可用工具（TOOL 步骤的 toolName 只能填以下编码，且必须带对应 inputParams）：
                    %s
                    设计原则：
                    1. 能调用工具完成核验时，先规划 TOOL 步骤，最后放一个 LLM 步骤汇总审核结论；
                    2. LLM 步骤只允许一个，放在最后做汇总结论；除非工具入参确需前置整理，才允许额外一个前置 LLM；
                    3. 禁止添加与汇总步骤职责重叠的前置"提取/核对/初步分析"LLM 步骤；
                    4. TOOL 步骤必须带 inputParams；LLM 步骤可省略 toolName 与 inputParams。
                    """.formatted(toolBlock);
            String user = "任务标题：" + task.getTitle() + "\n任务入参：\n" + toJson(task.getInputParams());
            // 调用 LLM 模型
            String reply = modelClient.chat(system, user);
            // 解析模型返回的结果，并转换为 List<TaskPlanStep>
            List<TaskPlanStep> steps = parse(reply);
            // 若结果为空，则走内置回退模板
            if (steps.isEmpty()) {
                return fallback(task.getInputParams());
            }
            log.info("LLM 规划成功，共 {} 步", steps.size());
            return steps;
        } catch (Exception e) {
            log.warn("LLM 拆解失败，回退内置模板: {}", e.getMessage());
            // 若异常，则回退内置模板
            return fallback(task.getInputParams());
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组。
     * @param text 模型返回的文本
     * @return 步骤列表
     */
    private List<TaskPlanStep> parse(String text) throws Exception {
        String json = text.trim();
        // 剥离可能的 ```json ... ``` 包裹
        if (json.startsWith("```")) {
            int first = json.indexOf('\n');
            int last = json.lastIndexOf("```");
            json = first > 0 && last > first ? json.substring(first + 1, last) : json;
        }
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("返回内容不含 JSON 数组: " + json);
        }
        List<TaskPlanStep> steps = objectMapper.readValue(
                json.substring(start, end + 1), new TypeReference<List<TaskPlanStep>>() {
                });
        return steps == null ? List.of() : steps;
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

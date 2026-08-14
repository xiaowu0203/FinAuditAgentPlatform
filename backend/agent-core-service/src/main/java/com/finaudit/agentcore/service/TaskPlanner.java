package com.finaudit.agentcore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.starter.model.ModelType;
import com.finaudit.starter.model.client.AiClient;
import com.finaudit.starter.model.client.ChatClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务规划器：用 LLM 把任务拆解为有序步骤（结构化 JSON）。
 * <p>解析失败回退内置模板（明细金额 → 金额核验 + 汇总），保证 P1 闭环可用。</p>
 */
@Component
public class TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanner.class);

    private final AiClient modelClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TaskPlanner(ChatClientFactory modelFactory) {
        this.modelClient = modelFactory.getClient(ModelType.DEEPSEEK);
    }

    /**
     * 规划任务执行步骤。
     */
    public List<TaskPlanStep> plan(AgentTask task) {
        try {
            String system = """
                    你是财务审核 Agent 的任务规划器。将报销审核任务拆解为有序的执行步骤，
                    只能输出一个 JSON 数组，不要输出任何其他内容，不要用代码块包裹。
                    数组元素结构：{"stepName":"步骤名","stepType":"LLM或TOOL","toolName":"TOOL步骤的工具编码","inputParams":{工具入参}}
                    可用的工具编码：amount_verify（金额核验，入参含 items 明细列表与 claimedTotal 申报总额）。
                    TOOL 步骤必须带 inputParams；LLM 步骤可省略 toolName 与 inputParams。
                    """;
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

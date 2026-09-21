package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.FlowDefinition;
import com.finaudit.agentcore.domain.TaskPlanStep;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则流水线引擎（仅 {@code REIMBURSEMENT} 报销单）。
 *
 * <p><b>P3.8 R6-5 起结构声明化</b>：本类不再用 if 堆叠拼装步骤，
 * 而是持有 {@link FlowDefinition} 声明并按入参物化。这样做的收益：</p>
 * <ul>
 *   <li>流水线结构成为一份可读数据（步骤顺序 / 条件 / 入参投影一目了然）；</li>
 *   <li>改结构只动声明，不再跨方法改多处；</li>
 *   <li>声明来源可整体替换（后续可接 Nacos），物化契约不变。</li>
 * </ul>
 * <p>本轮刻意不引入 DDL 表：声明量小且与代码强耦合，落表只会增加一致性负担。</p>
 */
@Component
public class RuleBasedFlowEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedFlowEngine.class);

    /** 报销单审核默认流水线声明（Java 内默认定义，见 {@link FlowDefinition#reimbursementDefault()}） */
    private static final FlowDefinition REIMBURSEMENT_DEFAULT = FlowDefinition.reimbursementDefault();

    /**
     * 组装报销场景流水线步骤。
     *
     * <p>条件步骤：存在附件才生成票据解析/票据核验；传入 deptName 才生成预算核算；
     * 其余金额核验、规则校验、重复检测、LLM 风控、LLM 汇总为必选步骤。</p>
     *
     * @param task Agent 任务对象，携带报销入参
     * @return 规划后的任务步骤列表 TaskPlanStep（顺序即执行顺序）
     */
    public List<TaskPlanStep> plan(AgentTask task) {
        List<TaskPlanStep> steps = REIMBURSEMENT_DEFAULT.materialize(task.getInputParams());
        log.info("任务[{}] REIMBURSEMENT 规则流水线规划完成（声明 {}），共 {} 步",
                task.getId(), REIMBURSEMENT_DEFAULT.name(), steps.size());
        return steps;
    }

    /** 供测试/文档核对流水线声明结构 */
    public static FlowDefinition definition() {
        return REIMBURSEMENT_DEFAULT;
    }
}

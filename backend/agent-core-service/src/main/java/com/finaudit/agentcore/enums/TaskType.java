package com.finaudit.agentcore.enums;

import java.util.Locale;

/**
 * 任务业务类型：任务分派与提示词/工具收敛的依据。
 * <p>P2a 引入：报销单提交与 P1 手工任务都走 {@code createTask}，若无业务类型，
 * 规划器无法区分业务、只能按写死的财务提示词处理所有任务。加本字段后：
 * 报销审核走财务专用提示词与工具集，通用任务走通用分析（P3 多 Agent 角色化的地基字段）。</p>
 */
public enum TaskType {
    /** 报销单审核（财务专用提示词 + 财务工具集） */
    REIMBURSEMENT,
    /** 通用任务分析（P1 手工任务的默认类型，不注入财务工具） */
    GENERIC;

    /** 按名解析，空/未知回退 GENERIC（兼容旧数据与缺省调用方）。 */
    public static TaskType of(String name) {
        if (name == null || name.isBlank()) {
            return GENERIC;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GENERIC;
        }
    }
}

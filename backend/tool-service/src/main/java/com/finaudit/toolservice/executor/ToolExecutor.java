package com.finaudit.toolservice.executor;

import com.finaudit.toolservice.enums.ToolCode;

import java.util.Map;

/**
 * 工具执行器接口。每个内置工具一个实现，通过 {@link #toolCode()} 与 tool_registry 对齐。
 */
public interface ToolExecutor {

    /** 工具编码（唯一真相见 {@link ToolCode}；须与 tool_registry.tool_code 一致） */
    ToolCode toolCode();

    /**
     * 执行工具。
     *
     * @param tenantId    租户ID（P2b 起工具可能经 Feign 委托其他服务取数，需显式传递租户）
     * @param inputParams 入参（JSON Schema 校验通过后的 Map）
     * @return 执行结果 Map
     * @throws com.finaudit.starter.web.exception.BizException 入参非法 / 业务校验失败
     */
    Map<String, Object> execute(Long tenantId, Map<String, Object> inputParams);
}

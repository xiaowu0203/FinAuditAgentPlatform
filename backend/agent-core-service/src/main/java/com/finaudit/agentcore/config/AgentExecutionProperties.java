package com.finaudit.agentcore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 执行加固配置（前缀 {@code finaudit.agent}）。
 * <p>P3.5d 任务级超时：单次执行（含修改重跑，started_at 每次启动/重跑刷新）超过预算仍未收尾时，
 * 由编排器在推进下一个步骤前强制失败终止，防止重试风暴/极端慢步骤长期占用任务与消费线程。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "finaudit.agent")
public class AgentExecutionProperties {

    /** 任务单次执行超时预算（分钟），<=0 表示关闭任务级超时检查 */
    private int taskTimeoutMinutes = 30;
}

package com.finaudit.toolservice.service;

import com.finaudit.starter.mq.message.ToolExecuteMessage;
import com.finaudit.toolservice.enums.ToolExecStatus;
import com.finaudit.toolservice.mapper.ToolExecutionLogMapper;
import com.finaudit.toolservice.pojo.entity.ToolExecutionLog;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工具执行日志服务：执行日志实体（tool_execution_log）的持久化收敛于此，
 * 外部（MQ 消费者等）不直接触碰执行日志 Mapper。
 */
@Service
public class ToolExecutionLogService {

    private final ToolExecutionLogMapper logMapper;

    public ToolExecutionLogService(ToolExecutionLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    /**
     * 保存工具执行记录（沿用实体静态工厂 {@link ToolExecutionLog#from}）。
     *
     * @param msg    原始执行请求消息
     * @param result 工具执行结果（失败时为 null）
     * @param status 执行状态：成功/失败
     * @param cost   总执行耗时（毫秒）
     */
    public void save(ToolExecuteMessage msg, Map<String, Object> result, ToolExecStatus status, long cost) {
        logMapper.insert(ToolExecutionLog.from(msg, result, status, cost));
    }
}

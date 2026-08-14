package com.finaudit.toolservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.starter.mq.message.ToolExecuteMessage;
import com.finaudit.starter.mq.message.ToolResultMessage;
import com.finaudit.toolservice.enums.ToolExecStatus;
import com.finaudit.toolservice.mq.ToolResultPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * 工具执行服务：承载工具执行的完整管线（Redis 缓存 → 注册表执行 → 落执行日志 → 发布执行结果）。
 * <p>供 MQ 消费者 {@link com.finaudit.toolservice.mq.ToolExecuteConsumer} 委托调用；
 * 调试直调 {@link com.finaudit.toolservice.controller.ToolController} 走 {@link ToolRegistryService#execute} 裸执行，不经过本管线。</p>
 */
@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ToolRegistryService registryService;
    private final ToolResultPublisher resultPublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ToolExecutionLogService logService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolExecutionService(ToolRegistryService registryService, ToolResultPublisher resultPublisher,
                                RedisTemplate<String, Object> redisTemplate, ToolExecutionLogService logService) {
        this.registryService = registryService;
        this.resultPublisher = resultPublisher;
        this.redisTemplate = redisTemplate;
        this.logService = logService;
    }

    /**
     * 工具执行完整管线（原 {@code ToolExecuteConsumer#onToolExecute}）：
     * 1. 带缓存执行工具（命中缓存直接返回历史结果）
     * 2. 执行完成/异常均持久化保存工具执行日志
     * 3. 发送工具执行结果消息 tool.result 供下游消费
     *
     * @param msg 工具执行请求消息，包含任务ID、步骤ID、工具编码、入参、租户信息等
     */
    public void executeAndPublish(ToolExecuteMessage msg) {
        // 记录方法开始时间，用于统计执行耗时
        long start = System.currentTimeMillis();
        log.info("收到工具执行消息: taskId={}, stepId={}, toolCode={}", msg.taskId(), msg.stepId(), msg.toolCode());
        try {
            // 带缓存逻辑执行工具，命中缓存直接返回历史结果
            Map<String, Object> result = executeWithCache(msg);
            // 计算总耗时
            long cost = System.currentTimeMillis() - start;
            // 保存成功执行日志
            logService.save(msg, result, ToolExecStatus.SUCCESS, cost);
            // 发布成功结果消息至MQ
            resultPublisher.publish(new ToolResultMessage(msg.taskId(), msg.stepId(), msg.tenantId(),
                    msg.toolCode(), result, true, null, cost));
        } catch (Exception e) {
            log.error("工具 {} 执行失败: {}", msg.toolCode(), e.getMessage(), e);
            // 计算总耗时
            long cost = System.currentTimeMillis() - start;
            // 保存失败执行日志，结果为空
            logService.save(msg, null, ToolExecStatus.FAILED, cost);
            // 发布失败结果消息，携带异常信息
            resultPublisher.publish(new ToolResultMessage(msg.taskId(), msg.stepId(), msg.tenantId(),
                    msg.toolCode(), null, false, e.getMessage(), cost));
        }
    }

    /**
     * 带缓存控制的工具执行逻辑
     * 优先读取Redis缓存，存在则直接返回缓存结果；无缓存则执行工具并写入1小时缓存
     *
     * @param msg 工具执行请求消息
     * @return 工具执行返回结果Map
     */
    private Map<String, Object> executeWithCache(ToolExecuteMessage msg) {
        // 构建缓存Key
        String key = cacheKey(msg.toolCode(), msg.inputParams());
        // 获取缓存值
        Object cached = redisTemplate.opsForValue().get(key);
        // 缓存命中直接返回
        if (cached != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) cached;
            log.info("工具 {} 命中 Redis 缓存", msg.toolCode());
            return result;
        }
        // 缓存不存在，调用工具注册表执行真实业务逻辑
        Map<String, Object> result = registryService.execute(msg.toolCode(), msg.tenantId(), msg.inputParams());
        // 执行结果存入Redis，缓存有效期1小时
        redisTemplate.opsForValue().set(key, result, Duration.ofHours(1));
        return result;
    }

    /**
     * 生成工具执行缓存唯一Key
     * 格式：tool:exec:{toolCode}:{SHA-256哈希字符串}
     * 逻辑：
     * 1. 将入参Map序列化为JSON字符串
     * 2. 使用SHA-256对JSON做摘要生成哈希，保证相同入参哈希一致
     * 3. SHA算法不存在时降级使用对象hashCode作为标识
     *
     * @param toolCode 工具唯一编码
     * @param input    工具入参Map
     * @return Redis缓存key字符串
     */
    private String cacheKey(String toolCode, Map<String, Object> input) {
        String json;
        try {
            // 将入参Map序列化为JSON字符串
            json = objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            // JSON序列化异常时，降级直接使用对象toString
            json = String.valueOf(input);
        }
        try {
            // SHA-256 摘要加密
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            // 字节数组转16进制字符串拼接key
            return "tool:exec:" + toolCode + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 极端情况SHA256算法不存在，降级使用字符串hashCode
            return "tool:exec:" + toolCode + ":" + json.hashCode();
        }
    }
}

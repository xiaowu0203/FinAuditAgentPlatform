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
            // 保存成功执行日志（旁路留痕，失败不阻断结果回吐）
            saveLogQuietly(msg, result, ToolExecStatus.SUCCESS, cost);
            // 发布成功结果消息至MQ
            resultPublisher.publish(new ToolResultMessage(msg.taskId(), msg.stepId(), msg.tenantId(),
                    msg.toolCode(), result, true, null, cost));
        } catch (Exception e) {
            log.error("工具 {} 执行失败: {}", msg.toolCode(), e.getMessage(), e);
            // 计算总耗时
            long cost = System.currentTimeMillis() - start;
            // 保存失败执行日志，结果为空（同样为旁路留痕）
            saveLogQuietly(msg, null, ToolExecStatus.FAILED, cost);
            // 发布失败结果消息，携带异常信息
            resultPublisher.publish(new ToolResultMessage(msg.taskId(), msg.stepId(), msg.tenantId(),
                    msg.toolCode(), null, false, e.getMessage(), cost));
        }
    }

    /**
     * 执行日志落库（旁路留痕）：任何异常只告警不抛出。
     * <p><b>为什么必须吞掉异常</b>：日志写入此前位于 {@code catch} 分支内且无保护，一旦落库失败
     * （如 {@code tool_execution_log.input_params} 为 {@code JSON NOT NULL} 而消息入参为 null，
     * MyBatis-Plus 默认 NOT_NULL 策略会跳过该列导致非空约束报错），异常会从 catch 块向上抛出——
     * 结果是 <b>既不回吐 {@code tool.result} 也不进 DLQ 的正常路径</b>，agent-core 侧步骤永久停在
     * RUNNING，只能等 30 分钟任务级超时或人工 resume 才能恢复。
     * 执行日志是审计留痕，不是主链路，<b>不得让留痕失败阻断任务推进</b>。</p>
     */
    private void saveLogQuietly(ToolExecuteMessage msg, Map<String, Object> result,
                                ToolExecStatus status, long cost) {
        try {
            logService.save(msg, result, status, cost);
        } catch (Exception e) {
            log.warn("工具执行日志落库失败（不影响结果回吐）: taskId={}, stepId={}, toolCode={}, status={}: {}",
                    msg.taskId(), msg.stepId(), msg.toolCode(), status, e.getMessage());
        }
    }

    /**
     * 带缓存控制的工具执行逻辑
     * 优先读取Redis缓存，存在则直接返回缓存结果；无缓存则执行工具并写入1小时缓存；
     * P2b 起：有状态工具（ocr_extract/budget_query/rule_check/duplicate_check，cacheable=0）
     * 跳过缓存读写，避免重新执行被旧结果截断或读到过期查询。
     *
     * @param msg 工具执行请求消息
     * @return 工具执行返回结果Map
     */
    private Map<String, Object> executeWithCache(ToolExecuteMessage msg) {
        // 缓存开关：有状态工具跳过缓存读写（P2b）
        if (!registryService.isCacheable(msg.toolCode(), msg.tenantId())) {
            log.debug("工具 {} cacheable=0，跳过缓存", msg.toolCode());
            return registryService.execute(msg.toolCode(), msg.tenantId(), msg.taskId(), msg.inputParams());
        }
        // 构建缓存Key（含租户前缀，防跨租户串号）
        String key = cacheKey(msg.tenantId(), msg.toolCode(), msg.inputParams());
        // 获取缓存值：Redis 不可用只降级为「未命中」，不得阻断工具执行（R6-3）
        Object cached = cacheGetQuietly(key);
        // 缓存命中直接返回
        if (cached != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) cached;
            log.info("工具 {} 命中 Redis 缓存", msg.toolCode());
            return result;
        }
        // 缓存不存在，调用工具注册表执行真实业务逻辑
        Map<String, Object> result = registryService.execute(msg.toolCode(), msg.tenantId(), msg.taskId(),
                msg.inputParams());
        // 执行结果存入Redis，缓存有效期1小时（写失败同样只告警）
        cacheSetQuietly(key, result);
        return result;
    }

    /**
     * 缓存读（R6-3 降级）：Redis 异常一律视为「未命中」。
     *
     * <p>此前缓存读写<b>裸调</b> Redis：一旦 Redis 抖动/重启，工具执行会直接抛异常，
     * 结果是不回吐 {@code tool.result}（与 R0-2 同类的"主链路被旁路组件拖死"）。
     * 缓存是性能优化而非正确性依赖，故障时必须降级为直连执行。</p>
     */
    private Object cacheGetQuietly(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("工具缓存读取失败（降级为直连执行）: key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /** 缓存写（R6-3 降级）：写失败只告警，不影响已获得的执行结果 */
    private void cacheSetQuietly(String key, Map<String, Object> result) {
        try {
            redisTemplate.opsForValue().set(key, result, Duration.ofHours(1));
        } catch (Exception e) {
            log.warn("工具缓存写入失败（不影响本次结果回吐）: key={}: {}", key, e.getMessage());
        }
    }

    /**
     * 生成工具执行缓存唯一Key。
     * <p>格式：{@code tool:exec:{tenantId}:{toolCode}:{SHA-256(入参)}}</p>
     *
     * <p><b>为什么必须带租户前缀（P3.8 R6-3）</b>：key 原先只有 {@code toolCode + 入参哈希}，
     * 两个租户用<b>完全相同的入参</b>调用同一工具时会命中同一条缓存——
     * 第二个租户直接读到第一个租户的结果，属于<b>跨租户数据泄漏</b>。
     * 虽然缓存只对 {@code cacheable=1} 的纯查询工具生效，且当前入参多含 reimbId/deptId
     * （天然带租户语义），但依赖"入参里恰好有租户标识"是不可靠的约定，必须在 key 上显式隔离。</p>
     *
     * @param tenantId  租户ID（缓存隔离维度）
     * @param toolCode  工具唯一编码
     * @param input     工具入参Map
     * @return Redis缓存key字符串
     */
    private String cacheKey(Long tenantId, String toolCode, Map<String, Object> input) {
        String json;
        try {
            // 将入参Map序列化为JSON字符串
            json = objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            // JSON序列化异常时，降级直接使用对象toString
            json = String.valueOf(input);
        }
        String prefix = "tool:exec:" + (tenantId == null ? "0" : tenantId) + ":" + toolCode + ":";
        try {
            // SHA-256 摘要加密
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            // 字节数组转16进制字符串拼接key
            return prefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 极端情况SHA256算法不存在，降级使用字符串hashCode
            return prefix + json.hashCode();
        }
    }
}

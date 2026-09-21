package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.config.NotifyProperties;
import com.finaudit.agentcore.domain.NotifyEvent;
import com.finaudit.agentcore.enums.NotifyCategory;
import com.finaudit.agentcore.mapper.NotifyDeliveryMapper;
import com.finaudit.agentcore.pojo.entity.NotifyDelivery;
import com.finaudit.agentcore.pojo.entity.NotifyWebhook;
import com.finaudit.agentcore.pojo.vo.DeliveryVO;
import com.finaudit.agentcore.support.NotifyEventTypes;
import com.finaudit.agentcore.support.WebhookSigner;
import com.finaudit.agentcore.support.WebhookUrlValidator;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Webhook 投递服务（P3.8 R8-2）：{@code notify_delivery} 的所有读写收敛于此，并负责真正的 HTTP 投递。
 *
 * <p><b>outbox 闭环</b>：业务事务内 {@link #enqueue} 落投递行 → 定时任务
 * {@link #deliverDue()} 取到期行 → {@link NotifyDeliveryMapper#claimAttempt} 乐观认领 →
 * HTTP 投递 → 回写结果（成功 / 退避重试 / DEAD）。</p>
 *
 * <p><b>为什么用 outbox 而不是 MQ</b>：投递行与业务数据同事务提交，"业务成功 ⇒ 必投递"是免费的；
 * 重启不丢、可用 SQL 直接排障、人工重投就是改一行。代价是多一次表写入与一个定时任务。
 * 这也回避了"先发 MQ 后提交业务、消费者抢在提交前就把消息处理掉"的经典竞态。</p>
 *
 * <p><b>多租户</b>：定时任务没有租户上下文，而 {@code notify_delivery} 的读写都被拦截器加上
 * {@code tenant_id} 条件。故先经一条<b>显式忽略租户</b>的查询枚举"有待投递记录的租户"，
 * 再逐个租户在 {@link TenantContextHolder#runWith} 内处理——这是本服务唯一跨租户的读取，
 * 已用 {@code @InterceptorIgnore} 标注并说明理由。</p>
 */
@Service
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    /** 事件负载里的时间字段格式（接收方按 ISO-8601 解析） */
    private static final String USER_AGENT = "FinAuditAgentPlatform-Webhook/1.0";

    private final NotifyDeliveryMapper deliveryMapper;
    private final NotifyWebhookService webhookService;
    private final NotifyMessageService messageService;
    private final NotifyProperties properties;
    private final WebhookUrlValidator urlValidator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebhookDeliveryService(NotifyDeliveryMapper deliveryMapper,
                                  NotifyWebhookService webhookService,
                                  NotifyMessageService messageService,
                                  NotifyProperties properties,
                                  WebhookUrlValidator urlValidator,
                                  ObjectMapper objectMapper) {
        this.deliveryMapper = deliveryMapper;
        this.webhookService = webhookService;
        this.messageService = messageService;
        this.properties = properties;
        this.urlValidator = urlValidator;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 把事件登记到各订阅方（**在业务事务内调用**，与业务数据同生共死）。
     *
     * @param event   事件
     * @param targets 订阅该事件且启用中的配置（调用方已过滤）
     * @return 登记条数
     */
    public int enqueue(NotifyEvent event, List<NotifyWebhook> targets) {
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String payloadJson = buildPayloadJson(event, eventId);
        Map<String, Object> payload = parsePayload(payloadJson);
        LocalDateTime now = LocalDateTime.now();
        List<NotifyDelivery> rows = new ArrayList<>(targets.size());
        for (NotifyWebhook webhook : targets) {
            rows.add(NotifyDelivery.pending(event.tenantId(), webhook.getId(),
                    event.eventType(), eventId, payload, now));
        }
        return deliveryMapper.insertBatch(rows);
    }

    /** 组装事件负载（站内信与 Webhook 共用同一份业务描述，避免两处口径漂移）。 */
    private String buildPayloadJson(NotifyEvent event, String eventId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("eventType", event.eventType());
        body.put("occurredAt", Instant.now().toString());
        body.put("tenantId", event.tenantId());
        body.put("bizType", event.bizType());
        body.put("bizId", event.bizId());
        body.put("title", event.title());
        body.put("content", event.content());
        body.put("link", event.link());
        body.put("data", event.data() == null ? Map.of() : event.data());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            // 序列化失败属编码缺陷（负载都是 Map/基本类型），抛出以暴露而不是发出空报文
            throw new IllegalStateException("序列化 Webhook 负载失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化 Webhook 负载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 投递一轮到期记录（定时任务入口，可被手工触发）。
     *
     * @return 本轮投递统计：尝试次数与成功数（用于日志与验证脚本）
     */
    public DeliveryRound deliverDue() {
        List<Long> tenantIds = deliveryMapper.selectPendingTenantIds();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return new DeliveryRound(0, 0, 0);
        }
        int attempted = 0;
        int succeeded = 0;
        int dead = 0;
        for (Long tenantId : tenantIds) {
            // 逐租户处理：拦截器据此为 selectDue/claimAttempt/updateResult 追加 tenant_id 条件
            int[] stat = TenantContextHolder.runWithResult(tenantId, this::deliverDueOfCurrentTenant);
            attempted += stat[0];
            succeeded += stat[1];
            dead += stat[2];
        }
        return new DeliveryRound(attempted, succeeded, dead);
    }

    private int[] deliverDueOfCurrentTenant() {
        List<NotifyDelivery> due = deliveryMapper.selectDue(LocalDateTime.now(), properties.getDeliveryBatchSize());
        int attempted = 0;
        int succeeded = 0;
        int dead = 0;
        for (NotifyDelivery delivery : due) {
            if (deliverOne(delivery)) {
                succeeded++;
                attempted++;
            } else {
                attempted++;
                // 失败后重新读一次状态：applyFailure 内部已决定是 PENDING 还是 DEAD
                NotifyDelivery latest = deliveryMapper.selectById(delivery.getId());
                if (latest != null && latest.dead()) {
                    dead++;
                }
            }
        }
        return new int[]{attempted, succeeded, dead};
    }

    /**
     * 投递一条记录。
     *
     * @return true=投递成功
     */
    private boolean deliverOne(NotifyDelivery delivery) {
        NotifyWebhook webhook = webhookService.getWebhookOrNull(delivery.getWebhookId());
        if (webhook == null || !webhook.enabled()) {
            // 配置已删/已停用：不再投递，也不重试（继续重试只会把台账刷满）
            markDead(delivery, "Webhook 配置不存在或已停用");
            return false;
        }
        // 乐观认领：多实例/多轮次并发时避免同一条被投递两次
        int claimed = deliveryMapper.claimAttempt(delivery.getId(), delivery.getAttemptCount());
        if (claimed == 0) {
            log.debug("投递记录已被其他实例认领，跳过: id={}", delivery.getId());
            return false;
        }
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);

        // 每次投递前重新校验地址（配置时校验过一次，但 DNS 可能已变；残余 TOCTOU 风险见类注释与文档）
        String urlError = urlValidator.check(webhook.getUrl());
        if (urlError != null) {
            markDead(delivery, "地址不可用: " + urlError);
            return false;
        }

        long timestamp = Instant.now().getEpochSecond();
        String body = toJson(delivery.getPayload());
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook.getUrl()))
                .timeout(Duration.ofMillis(webhook.getTimeoutMs() == null
                        ? NotifyWebhook.DEFAULT_TIMEOUT_MS : webhook.getTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", USER_AGENT)
                .header(WebhookSigner.HEADER_EVENT, delivery.getEventType())
                .header(WebhookSigner.HEADER_DELIVERY, delivery.getEventId())
                .header(WebhookSigner.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(WebhookSigner.HEADER_SIGNATURE,
                        WebhookSigner.headerValue(webhook.getSecret(), timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                delivery.applySuccess(status, LocalDateTime.now());
                deliveryMapper.updateResult(delivery);
                log.info("Webhook 投递成功: deliveryId={}, webhook={}, event={}, status={}",
                        delivery.getId(), webhook.getName(), delivery.getEventType(), status);
                return true;
            }
            onFailure(delivery, webhook, status, "HTTP " + status + " "
                    + truncate(response.body(), 200));
            return false;
        } catch (Exception e) {
            onFailure(delivery, webhook, null, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /** 失败处理：还有额度则排下次重试，用尽则 DEAD 并给配置创建人发站内信告警。 */
    private void onFailure(NotifyDelivery delivery, NotifyWebhook webhook, Integer httpStatus, String error) {
        int maxAttempts = webhook.getMaxAttempts() == null ? NotifyWebhook.DEFAULT_MAX_ATTEMPTS : webhook.getMaxAttempts();
        LocalDateTime nextRetry = LocalDateTime.now()
                .plusSeconds(properties.backoffSecondsFor(delivery.getAttemptCount()));
        boolean exhausted = delivery.applyFailure(httpStatus, error, maxAttempts, nextRetry);
        deliveryMapper.updateResult(delivery);
        if (exhausted) {
            log.error("Webhook 投递次数用尽，已放弃: deliveryId={}, webhook={}, event={}, attempts={}, lastError={}",
                    delivery.getId(), webhook.getName(), delivery.getEventType(),
                    delivery.getAttemptCount(), delivery.getLastError());
            alertOwner(delivery, webhook);
        } else {
            log.warn("Webhook 投递失败，将在 {} 后重试: deliveryId={}, attempt={}/{}, err={}",
                    nextRetry, delivery.getId(), delivery.getAttemptCount(), maxAttempts, delivery.getLastError());
        }
    }

    /**
     * DEAD 告警：给 Webhook 配置的创建人发一条站内信。
     *
     * <p>⚠️ <b>刻意不触发 WEBHOOK_DEAD 的 Webhook 事件</b>：否则"Webhook 挂了 → 发告警事件 → 走同一个
     * 挂掉的 Webhook → 又失败 → 又告警"会自我放大（每次告警都多一条 DEAD 记录）。
     * 平台自身的故障要用人能看到的通道（站内信）通知人，而不是继续喂给出故障的通道。</p>
     */
    private void alertOwner(NotifyDelivery delivery, NotifyWebhook webhook) {
        if (webhook.getCreatedBy() == null) {
            return;
        }
        String title = "Webhook 投递连续失败：" + webhook.getName();
        String content = "目标地址 " + webhook.getUrl() + " 连续 " + delivery.getAttemptCount()
                + " 次投递失败，已停止重试（事件 " + delivery.getEventType()
                + "，事件ID " + delivery.getEventId() + "，最后错误：" + delivery.getLastError()
                + "）。请检查对端可用性与地址配置后，在通知配置页手工重投。";
        messageService.send(List.of(com.finaudit.agentcore.pojo.entity.NotifyMessage.from(
                delivery.getTenantId(), webhook.getCreatedBy(), NotifyCategory.ALERT,
                NotifyEventTypes.WEBHOOK_DEAD, title, content, "WEBHOOK", webhook.getId(),
                null, "WEBHOOK_DEAD:" + delivery.getId() + ":" + webhook.getCreatedBy())));
    }

    private void markDead(NotifyDelivery delivery, String reason) {
        delivery.applyDead(reason);
        deliveryMapper.updateResult(delivery);
        log.warn("Webhook 投递终止: deliveryId={}, reason={}", delivery.getId(), reason);
    }

    /** 人工重投（管理端点）：清零次数并立刻到期。 */
    public boolean retryManually(Long deliveryId) {
        NotifyDelivery delivery = deliveryMapper.selectById(deliveryId);
        if (delivery == null) {
            return false;
        }
        delivery.resetForRetry();
        deliveryMapper.updateResult(delivery);
        log.info("人工重投已排期: deliveryId={}, event={}", deliveryId, delivery.getEventType());
        return true;
    }

    /**
     * 测试投递（管理端点）：立刻登记一条 {@code WEBHOOK_TEST} 记录并返回其 id。
     *
     * <p>用途：管理员配完地址后马上验证对端可达、签名校验通过，不必等真实业务事件。
     * 记录同样走 outbox（与正式投递同一条链路），因此它验证的是**完整链路**而不是"发一下试试"。</p>
     *
     * @param webhook 目标配置
     * @return 投递记录 id
     */
    public Long enqueueTest(NotifyWebhook webhook) {
        NotifyEvent event = new NotifyEvent(webhook.getTenantId(), NotifyEventTypes.WEBHOOK_TEST,
                NotifyCategory.ALERT, "Webhook 连通性测试",
                "这是一条来自 FinAuditAgentPlatform 的测试投递，用于验证回调地址可达性与签名校验。",
                "WEBHOOK", webhook.getId(), null, List.of(), null,
                Map.of("webhookName", webhook.getName(), "test", true));
        String eventId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> payload = parsePayload(buildPayloadJson(event, eventId));
        NotifyDelivery delivery = NotifyDelivery.pending(webhook.getTenantId(), webhook.getId(),
                NotifyEventTypes.WEBHOOK_TEST, eventId, payload, LocalDateTime.now());
        // ⚠️ 这里必须用 MP 的单行 insert（IdType.AUTO 会回填自增主键），**不能用自定义 insertBatch**：
        //    那个 XML 是「一次 INSERT 多行」且没有 useGeneratedKeys → delivery.getId() 恒为 null，
        //    端点契约承诺返回"投递记录ID"却回 null（运行时实测踩到：验证脚本拿不到 id，
        //    连带 `deliveries//retry` 拼出非法 URL、排障也指不到具体记录）。
        deliveryMapper.insert(delivery);
        log.info("Webhook 测试投递已登记: webhook={}, deliveryId={}", webhook.getName(), delivery.getId());
        return delivery.getId();
    }

    /** 投递台账分页（按 Webhook 过滤）。 */
    public Page<DeliveryVO> page(Long webhookId, String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<NotifyDelivery> wrapper = new LambdaQueryWrapper<NotifyDelivery>()
                .eq(webhookId != null, NotifyDelivery::getWebhookId, webhookId)
                .eq(status != null && !status.isBlank(), NotifyDelivery::getStatus, status)
                .orderByDesc(NotifyDelivery::getId);
        Page<NotifyDelivery> page = deliveryMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return (Page<DeliveryVO>) page.convert(DeliveryVO::from);
    }

    /** 待投递（含待重试）条数：排障时先看这个数（持续增长 = 对端不可用）。 */
    public long pendingCount() {
        Long count = deliveryMapper.selectCount(new LambdaQueryWrapper<NotifyDelivery>()
                .eq(NotifyDelivery::getStatus, "PENDING"));
        return count == null ? 0L : count;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化投递负载失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    /**
     * 一轮投递的统计结果。
     *
     * @param attempted 尝试条数
     * @param succeeded 成功条数
     * @param dead      本轮进入 DEAD 的条数
     */
    public record DeliveryRound(int attempted, int succeeded, int dead) {
    }
}

package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.pojo.dto.WebhookCreateRequest;
import com.finaudit.agentcore.pojo.dto.WebhookUpdateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Webhook 配置（notify_webhook，P3.8 R8-2）。
 *
 * <p>按租户可配多条：不同下游系统（如 OA、企业微信机器人、数据仓库）各自订阅不同事件。</p>
 *
 * <p><b>⚠️ 密钥处理</b>：{@code secret} 用于 HMAC-SHA256 签名，服务端必须能还原 → 明文入库
 * （生产应接 KMS 或密文列，已登记为已知取舍）。对外响应一律只回显掩码（见 {@code WebhookVO}），
 * 更新时空 secret 表示"不改动原密钥"。</p>
 */
@Getter
@Setter
@TableName(value = "notify_webhook", autoResultMap = true)
public class NotifyWebhook {

    /** 名称上限 */
    public static final int NAME_MAX_LEN = 64;
    /** URL 上限 */
    public static final int URL_MAX_LEN = 512;
    /** 密钥上限 */
    public static final int SECRET_MAX_LEN = 128;

    /** 投递次数缺省值（含首次） */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 单次超时缺省值（毫秒） */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "配置名称")
    private String name;

    @Schema(description = "回调地址")
    private String url;

    @Schema(description = "HMAC-SHA256 签名密钥（不对外返回）")
    private String secret;

    /** JSON 列：订阅的事件类型数组；空数组 = 订阅全部（§5.13：JSON 列必须走实体更新，禁止 wrapper.set） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "订阅事件类型数组；[]=全部")
    private List<String> eventTypes;

    @Schema(description = "启用: 1启用 0停用")
    private Integer enabled;

    @Schema(description = "最大投递次数（含首次）")
    private Integer maxAttempts;

    @Schema(description = "单次 HTTP 超时（毫秒）")
    private Integer timeoutMs;

    @Schema(description = "创建人用户ID")
    private Long createdBy;

    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /** 新建（静态工厂）。 */
    public static NotifyWebhook from(WebhookCreateRequest request, Long tenantId, Long createdBy) {
        NotifyWebhook w = new NotifyWebhook();
        w.setTenantId(tenantId);
        w.setName(truncate(request.name(), NAME_MAX_LEN));
        w.setUrl(truncate(request.url(), URL_MAX_LEN));
        w.setSecret(truncate(request.secret(), SECRET_MAX_LEN));
        w.setEventTypes(request.eventTypes() == null ? List.of() : List.copyOf(request.eventTypes()));
        w.setEnabled(request.enabled() == null ? 1 : request.enabled());
        w.setMaxAttempts(normalizeAttempts(request.maxAttempts()));
        w.setTimeoutMs(normalizeTimeout(request.timeoutMs()));
        w.setCreatedBy(createdBy);
        return w;
    }

    /**
     * 字段合并（更新）。**空值不动**——与前端「只改 URL」的表单语义一致；
     * {@code secret} 空串表示保留原密钥（前端不回显密钥，故不能要求每次都填）。
     */
    public void apply(WebhookUpdateRequest request) {
        if (isNotBlank(request.name())) {
            this.name = truncate(request.name(), NAME_MAX_LEN);
        }
        if (isNotBlank(request.url())) {
            this.url = truncate(request.url(), URL_MAX_LEN);
        }
        if (isNotBlank(request.secret())) {
            this.secret = truncate(request.secret(), SECRET_MAX_LEN);
        }
        if (request.eventTypes() != null) {
            this.eventTypes = List.copyOf(request.eventTypes());
        }
        if (request.enabled() != null) {
            this.enabled = request.enabled();
        }
        if (request.maxAttempts() != null) {
            this.maxAttempts = normalizeAttempts(request.maxAttempts());
        }
        if (request.timeoutMs() != null) {
            this.timeoutMs = normalizeTimeout(request.timeoutMs());
        }
    }

    /**
     * 是否订阅某事件。
     * <p>空数组 = 订阅全部：新建配置时不该强迫管理员先勾一遍全部事件码；
     * 而要"只订阅几个"时再显式勾选。</p>
     */
    public boolean subscribes(String eventType) {
        if (eventType == null) {
            return false;
        }
        if (eventTypes == null || eventTypes.isEmpty()) {
            return true;
        }
        return eventTypes.contains(eventType);
    }

    public boolean enabled() {
        return enabled != null && enabled == 1;
    }

    /** 密钥掩码（前 2 位 + 后 2 位），对外只回显这个。 */
    public String maskedSecret() {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return secret.substring(0, 2) + "****" + secret.substring(secret.length() - 2);
    }

    private static int normalizeAttempts(Integer attempts) {
        if (attempts == null || attempts < 1) {
            return DEFAULT_MAX_ATTEMPTS;
        }
        // 上限 10：再多的重试只是在拖延"承认失败"，而 DEAD 后有站内信告警与人工重投
        return Math.min(attempts, 10);
    }

    private static int normalizeTimeout(Integer timeoutMs) {
        if (timeoutMs == null || timeoutMs < 100) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.min(timeoutMs, 60_000);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}

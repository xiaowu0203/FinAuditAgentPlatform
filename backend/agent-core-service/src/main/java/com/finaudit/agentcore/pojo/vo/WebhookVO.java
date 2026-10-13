package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.NotifyWebhook;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Webhook 配置出参（P3.8 R8-2）。
 *
 * <p><b>密钥永不回显</b>：只给掩码（{@code ****abcd}），前端更新表单留空即表示"不改密钥"。
 * 回显明文密钥等于把"能伪造平台签名"的能力送给任何一个能读该接口的人（含审计日志、浏览器缓存）。</p>
 */
@Getter
@Setter
public class WebhookVO {

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "配置名称")
    private String name;

    @Schema(description = "回调地址")
    private String url;

    @Schema(description = "签名密钥掩码（明文永不回显）")
    private String secretMasked;

    @Schema(description = "订阅事件类型；[]=全部")
    private List<String> eventTypes;

    @Schema(description = "启用: 1启用 0停用")
    private Integer enabled;

    @Schema(description = "最大投递次数（含首次）")
    private Integer maxAttempts;

    @Schema(description = "单次 HTTP 超时（毫秒）")
    private Integer timeoutMs;

    @Schema(description = "创建人用户ID")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /** 实体 → VO（转换封装在目标类）。 */
    public static WebhookVO from(NotifyWebhook entity) {
        WebhookVO vo = new WebhookVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setUrl(entity.getUrl());
        vo.setSecretMasked(entity.maskedSecret());
        vo.setEventTypes(entity.getEventTypes());
        vo.setEnabled(entity.getEnabled());
        vo.setMaxAttempts(entity.getMaxAttempts());
        vo.setTimeoutMs(entity.getTimeoutMs());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}

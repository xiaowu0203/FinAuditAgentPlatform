package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建 Webhook 配置请求（P3.8 R8-2）。
 * <p>所属租户取请求上下文（X-Tenant-Id），不信任请求体。</p>
 *
 * @param name        配置名称（租户内未删除行不可重名）
 * @param url         回调地址（仅 http/https；默认拒绝私网/环回，见 WebhookUrlValidator）
 * @param secret      HMAC-SHA256 签名密钥（对外不回显）
 * @param eventTypes  订阅的事件类型；null 或空数组 = 订阅全部（见 NotifyEventTypes）
 * @param enabled     启用状态，缺省 1
 * @param maxAttempts 最大投递次数（含首次），缺省 3、上限 10
 * @param timeoutMs   单次 HTTP 超时（毫秒），缺省 5000、上限 60000
 */
public record WebhookCreateRequest(
        @NotBlank(message = "配置名称不能为空")
        @Size(max = 64, message = "配置名称最长 64 字符")
        @Schema(description = "配置名称")
        String name,

        @NotBlank(message = "回调地址不能为空")
        @Size(max = 512, message = "回调地址最长 512 字符")
        @Schema(description = "回调地址（http/https）")
        String url,

        @NotBlank(message = "签名密钥不能为空")
        @Size(max = 128, message = "签名密钥最长 128 字符")
        @Schema(description = "HMAC-SHA256 签名密钥")
        String secret,

        @Schema(description = "订阅事件类型数组；[]=全部")
        List<String> eventTypes,

        @Min(value = 0, message = "启用状态只能是 0 或 1")
        @Max(value = 1, message = "启用状态只能是 0 或 1")
        @Schema(description = "启用: 1启用 0停用")
        Integer enabled,

        @Min(value = 1, message = "最大投递次数至少 1")
        @Max(value = 10, message = "最大投递次数最多 10")
        @Schema(description = "最大投递次数（含首次）")
        Integer maxAttempts,

        @Min(value = 100, message = "超时至少 100 毫秒")
        @Max(value = 60000, message = "超时最多 60000 毫秒")
        @Schema(description = "单次 HTTP 超时（毫秒）")
        Integer timeoutMs) {
}

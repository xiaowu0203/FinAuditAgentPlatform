package com.finaudit.agentcore.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新 Webhook 配置请求（P3.8 R8-2）。
 *
 * <p>语义为**部分更新**：字段为空/空白表示"不改动该项"。{@code secret} 尤其如此——
 * 密钥从不回显给前端，若要求每次都填会把"只改 URL"变成"必须重新抄一遍密钥"。</p>
 */
public record WebhookUpdateRequest(
        @Size(max = 64, message = "配置名称最长 64 字符")
        @Schema(description = "配置名称（空=不改）")
        String name,

        @Size(max = 512, message = "回调地址最长 512 字符")
        @Schema(description = "回调地址（空=不改）")
        String url,

        @Size(max = 128, message = "签名密钥最长 128 字符")
        @Schema(description = "签名密钥（空=保留原密钥）")
        String secret,

        @Schema(description = "订阅事件类型数组（null=不改）")
        List<String> eventTypes,

        @Min(value = 0, message = "启用状态只能是 0 或 1")
        @Max(value = 1, message = "启用状态只能是 0 或 1")
        @Schema(description = "启用: 1启用 0停用（null=不改）")
        Integer enabled,

        @Min(value = 1, message = "最大投递次数至少 1")
        @Max(value = 10, message = "最大投递次数最多 10")
        @Schema(description = "最大投递次数（null=不改）")
        Integer maxAttempts,

        @Min(value = 100, message = "超时至少 100 毫秒")
        @Max(value = 60000, message = "超时最多 60000 毫秒")
        @Schema(description = "单次 HTTP 超时（毫秒，null=不改）")
        Integer timeoutMs) {
}

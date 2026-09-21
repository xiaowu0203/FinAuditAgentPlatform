package com.finaudit.agentcore.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.pojo.dto.WebhookCreateRequest;
import com.finaudit.agentcore.pojo.dto.WebhookUpdateRequest;
import com.finaudit.agentcore.pojo.entity.NotifyWebhook;
import com.finaudit.agentcore.pojo.vo.DeliveryVO;
import com.finaudit.agentcore.pojo.vo.WebhookVO;
import com.finaudit.agentcore.service.NotifyWebhookService;
import com.finaudit.agentcore.service.WebhookDeliveryService;
import com.finaudit.agentcore.support.NotifyEventTypes;
import com.finaudit.starter.web.auth.RequirePerm;
import com.finaudit.starter.web.auth.UserContext;
import com.finaudit.starter.web.auth.UserContextHolder;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook 配置与投递台账端点（P3.8 R8-2）。
 *
 * <p><b>权限</b>：整类挂 {@code notify:manage}（仅内置 admin 角色持有）。
 * 这是"平台级集成配置"：改一条配置就能把本租户的审核事件推给任意外部地址，
 * 属于比"看看报表"高得多的权限；默认角色（审核员）不该有。</p>
 *
 * <p><b>路由前缀</b>：{@code /api/v1/notify/webhooks}（需在网关新增路由，见 {@code gateway.md}）。</p>
 */
@Tag(name = "通知配置", description = "P3.8 R8-2：Webhook 配置管理 + 投递台账 + 测试投递")
@RestController
@RequestMapping("/api/v1/notify/webhooks")
@RequirePerm("notify:manage")
public class NotifyWebhookController {

    private final NotifyWebhookService webhookService;
    private final WebhookDeliveryService deliveryService;

    public NotifyWebhookController(NotifyWebhookService webhookService,
                                   WebhookDeliveryService deliveryService) {
        this.webhookService = webhookService;
        this.deliveryService = deliveryService;
    }

    @Operation(summary = "Webhook 配置列表", description = "本租户全部配置（含停用）；密钥只回显掩码")
    @GetMapping
    public R<List<WebhookVO>> list() {
        return R.success(webhookService.list());
    }

    @Operation(summary = "可选事件类型目录", description = "前端订阅多选框的数据源；空数组表示订阅全部")
    @GetMapping("/event-types")
    public R<List<String>> eventTypes() {
        return R.success(NotifyEventTypes.ALL);
    }

    @Operation(summary = "新建 Webhook 配置", description = "地址做 SSRF 校验（默认拒绝内网/环回）；重名拒绝")
    @PostMapping
    public R<WebhookVO> create(@Valid @RequestBody WebhookCreateRequest request) {
        UserContext user = UserContextHolder.get();
        Long tenantId = user == null || user.getTenantId() == null ? 1L : user.getTenantId();
        return R.success(webhookService.create(request, tenantId,
                user == null ? null : user.getUserId()));
    }

    @Operation(summary = "更新 Webhook 配置", description = "部分更新：空字段不改动；secret 留空表示保留原密钥")
    @PutMapping("/{id}")
    public R<WebhookVO> update(@PathVariable Long id, @Valid @RequestBody WebhookUpdateRequest request) {
        return R.success(webhookService.update(id, request));
    }

    @Operation(summary = "删除 Webhook 配置", description = "逻辑删除；投递台账保留以便事后核对")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        webhookService.delete(id);
        return R.success();
    }

    @Operation(summary = "测试投递", description = "立刻登记一条 WEBHOOK_TEST 投递（走完整 outbox 链路），返回投递记录ID；"
            + "用 GET /api/v1/notify/webhooks/deliveries 查结果")
    @PostMapping("/{id}/test")
    public R<Long> test(@PathVariable Long id) {
        NotifyWebhook webhook = webhookService.getRequired(id);
        return R.success(deliveryService.enqueueTest(webhook));
    }

    @Operation(summary = "投递台账", description = "按 Webhook / 状态过滤；投递失败的排障入口（lastError 含 HTTP 状态与响应片段）")
    @GetMapping("/deliveries")
    public R<Page<DeliveryVO>> deliveries(@RequestParam(required = false) Long webhookId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "1") int pageNum,
                                          @RequestParam(defaultValue = "20") int pageSize) {
        return R.success(deliveryService.page(webhookId, status, pageNum, pageSize));
    }

    @Operation(summary = "人工重投", description = "对 DEAD/长期失败的记录清零次数并立刻重投")
    @PostMapping("/deliveries/{deliveryId}/retry")
    public R<Boolean> retry(@PathVariable Long deliveryId) {
        return R.success(deliveryService.retryManually(deliveryId));
    }

    @Operation(summary = "待投递积压数", description = "排障指标：持续增长说明对端不可用或投递任务停摆")
    @GetMapping("/pending-count")
    public R<Map<String, Object>> pendingCount() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pending", deliveryService.pendingCount());
        return R.success(body);
    }
}

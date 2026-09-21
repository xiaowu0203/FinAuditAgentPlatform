package com.finaudit.agentcore.controller;

import com.finaudit.agentcore.service.WebhookDeliveryService;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知投递的内部触发端点（P3.8 R8-2，{@code /internal/**} 不经网关暴露）。
 *
 * <p><b>两个用途</b>：① 验证脚本需要确定性触发（否则只能等 15s 一轮的定时任务，断言时机不可控）；
 * ② 运维手工催投——对端恢复后不必干等到下一个轮次，也不必重启服务。</p>
 *
 * <p><b>为什么放内部前缀</b>：它是"让服务立刻做一件本该自动做的事"，属运维动作；
 * 挂到 {@code /api/v1/**} 会把它暴露给租户侧（且需要新的权限码）。
 * {@code /internal/**} 不在网关路由表内，外部经网关不可达。</p>
 *
 * <p>并发安全：投递本身走乐观认领（{@code attempt_count} 版本号），
 * 与定时任务同时触发也不会重复投递同一条。</p>
 */
@Tag(name = "通知-内部契约", description = "服务间/运维调用（/internal/notify, 网关不暴露）")
@RestController
@RequestMapping("/internal/notify")
public class InternalNotifyController {

    private final WebhookDeliveryService deliveryService;

    public InternalNotifyController(WebhookDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Operation(summary = "立即投递一轮（内部）",
            description = "按各租户投递所有到期 Webhook 记录，返回本轮尝试/成功/放弃条数；与定时任务共用同一认领机制")
    @PostMapping("/deliver")
    public R<Map<String, Object>> deliver() {
        WebhookDeliveryService.DeliveryRound round = deliveryService.deliverDue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempted", round.attempted());
        body.put("succeeded", round.succeeded());
        body.put("dead", round.dead());
        return R.success(body);
    }
}

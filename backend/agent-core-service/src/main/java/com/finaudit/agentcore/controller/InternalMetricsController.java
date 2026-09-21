package com.finaudit.agentcore.controller;

import com.finaudit.agentcore.service.ModelCallLogService;
import com.finaudit.starter.model.client.ChatClientFactory;
import com.finaudit.starter.model.metrics.ModelUsageSnapshot;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标读取端点（内部，P3.8 R9-1 的 {@code usageSnapshot()} 接出口）。
 *
 * <p><b>为什么放在 {@code /internal/**}</b>：本仓 {@code /internal/**} 刻意**不经网关暴露**，
 * 只允许服务间/运维直连访问。指标数据（Token 用量、成本）属运维视角，不该出现在面向租户的
 * {@code /api/v1/**} 契约里——那会带来"租户能否看到平台总用量"的口径争议，也扩大了攻击面。</p>
 *
 * <p><b>两类数据的分工</b>（与 {@code docs/architecture/metrics.md} 一致）：</p>
 * <ul>
 *   <li>{@code snapshot}：进程内即时观测（本进程启动至今），轻量、不查库；</li>
 *   <li>{@code window}：从 {@code model_call_log} 台账按时间窗聚合（跨重启、可指定区间），
 *       是成本指标的权威口径。</li>
 * </ul>
 * <p>P4 建看板时直接消费本端点或按指标文档写 SQL，二者口径相同。</p>
 */
@Tag(name = "内部指标", description = "P3.8 R9：模型用量的即时快照与台账窗口聚合（不经网关暴露）")
@RestController
@RequestMapping("/internal/metrics")
public class InternalMetricsController {

    private final ChatClientFactory chatClientFactory;
    private final ModelCallLogService modelCallLogService;

    public InternalMetricsController(ChatClientFactory chatClientFactory,
                                     ModelCallLogService modelCallLogService) {
        this.chatClientFactory = chatClientFactory;
        this.modelCallLogService = modelCallLogService;
    }

    @Operation(summary = "模型用量（内部）",
            description = "snapshot=进程内累计（本进程启动至今）；window=model_call_log 台账窗口聚合（不传区间则全量）")
    @GetMapping("/model-usage")
    public R<Map<String, Object>> modelUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        ModelUsageSnapshot snapshot = chatClientFactory.usageSnapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot", snapshot);
        body.put("snapshotSummary", snapshot.summary());
        body.put("snapshotFailRatePct", snapshot.failRatePct());
        body.put("window", modelCallLogService.summarize(from, to));
        body.put("windowFrom", from);
        body.put("windowTo", to);
        // 口径提示随响应返回：避免消费方把"进程内快照"当成"全量成本"（快照会随重启归零）
        body.put("note", "snapshot 为进程内累计（重启归零）；成本指标请以 window（model_call_log 台账）为准");
        return R.success(body);
    }
}

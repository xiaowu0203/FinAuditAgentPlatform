package com.finaudit.agentcore.config;

import com.finaudit.agentcore.service.WebhookDeliveryService;
import com.finaudit.agentcore.support.WebhookUrlValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 通知调度配置（P3.8 R8-2）。
 *
 * <p>Webhook 采用 DB outbox 模式，投递由本类的定时任务驱动（间隔可配、可整体关闭）。
 * 之所以需要在这里 {@code @EnableScheduling}：本仓此前<b>没有任何定时任务</b>
 * （{@code task-job-service} 尚未落地），故由本阶段首次开启调度能力。</p>
 *
 * <p>⚠️ 定时任务与 HTTP 请求共用线程池之外的独立调度线程：任务内部会为每个租户
 * 通过 {@code TenantContextHolder.runWith} 设置/清理上下文，不会污染请求线程。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyScheduleConfig {

    private static final Logger log = LoggerFactory.getLogger(NotifyScheduleConfig.class);

    private final NotifyProperties properties;
    private final WebhookDeliveryService deliveryService;
    private final WebhookUrlValidator urlValidator;

    public NotifyScheduleConfig(NotifyProperties properties,
                                WebhookDeliveryService deliveryService,
                                WebhookUrlValidator urlValidator) {
        this.properties = properties;
        this.deliveryService = deliveryService;
        this.urlValidator = urlValidator;
    }

    /** 启动期把"允许内网地址"这类危险配置显式喊出来。 */
    @PostConstruct
    public void logConfiguration() {
        urlValidator.logConfigurationOnStartup();
        log.info("主动通知已启用: webhook 投递={}, 间隔={}ms, 批量={}, 退避={}s, 允许内网地址={}",
                properties.isDeliveryEnabled(), properties.getDeliveryIntervalMs(),
                properties.getDeliveryBatchSize(), properties.getRetryBackoffSeconds(),
                properties.isAllowPrivateAddress());
    }

    /**
     * 投递到期 Webhook（固定延迟：上一轮跑完再等间隔，避免任务堆积）。
     *
     * <p>间隔经配置注入（{@code finaudit.notify.delivery-interval-ms}），并可用
     * {@code finaudit.notify.delivery-enabled=false} 关闭（本地自测时改由脚本手工触发，
     * 免得"后台偷偷发出去"干扰断言）。</p>
     */
    @Scheduled(fixedDelayString = "${finaudit.notify.delivery-interval-ms:15000}",
            initialDelayString = "${finaudit.notify.delivery-initial-delay-ms:5000}")
    public void deliverWebhooks() {
        if (!properties.isDeliveryEnabled()) {
            return;
        }
        try {
            WebhookDeliveryService.DeliveryRound round = deliveryService.deliverDue();
            if (round.attempted() > 0) {
                log.info("Webhook 投递轮次完成: 尝试={}, 成功={}, 放弃={}",
                        round.attempted(), round.succeeded(), round.dead());
            }
        } catch (Exception e) {
            // 定时任务抛异常会让后续轮次静默停摆（Spring 只记日志不重排），故兜底自愈
            log.error("Webhook 投递轮次异常（已忽略，等待下一轮）: {}", e.toString());
        }
    }
}

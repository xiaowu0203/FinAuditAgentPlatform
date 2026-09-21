package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.mapper.NotifyWebhookMapper;
import com.finaudit.agentcore.pojo.dto.WebhookCreateRequest;
import com.finaudit.agentcore.pojo.dto.WebhookUpdateRequest;
import com.finaudit.agentcore.pojo.entity.NotifyWebhook;
import com.finaudit.agentcore.pojo.vo.WebhookVO;
import com.finaudit.agentcore.support.NotifyEventTypes;
import com.finaudit.agentcore.support.WebhookUrlValidator;
import com.finaudit.starter.web.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Webhook 配置服务（P3.8 R8-2）：{@code notify_webhook} 的所有读写收敛于此（AGENTS.md §5.9）。
 */
@Service
public class NotifyWebhookService {

    private static final Logger log = LoggerFactory.getLogger(NotifyWebhookService.class);

    private final NotifyWebhookMapper webhookMapper;
    private final WebhookUrlValidator urlValidator;

    public NotifyWebhookService(NotifyWebhookMapper webhookMapper, WebhookUrlValidator urlValidator) {
        this.webhookMapper = webhookMapper;
        this.urlValidator = urlValidator;
    }

    /** 本租户全部配置（含停用；管理页要看得到）。 */
    public List<WebhookVO> list() {
        return webhookMapper.selectList(new LambdaQueryWrapper<NotifyWebhook>()
                        .orderByDesc(NotifyWebhook::getId))
                .stream().map(WebhookVO::from).toList();
    }

    /** 必取（不存在直接抛业务异常，避免调用方到处判空）。 */
    public NotifyWebhook getRequired(Long id) {
        NotifyWebhook webhook = webhookMapper.selectById(id);
        if (webhook == null) {
            throw new BizException("Webhook 配置不存在: " + id);
        }
        return webhook;
    }

    /**
     * 允许为空的取用（投递侧使用）：配置可能已被删除，投递任务不该因此抛异常中断整轮。
     *
     * @return 配置；不存在返回 null
     */
    public NotifyWebhook getWebhookOrNull(Long id) {
        return id == null ? null : webhookMapper.selectById(id);
    }

    /**
     * 找出订阅了某事件、且已启用的配置（投递目标）。
     *
     * <p>过滤在 Java 侧做：{@code event_types} 是 JSON 数组，用 SQL 判"包含某元素"要写
     * {@code JSON_CONTAINS}，既依赖 MySQL 5.7 的 JSON 函数又与 MP 的 wrapper 风格割裂；
     * 而配置量级是"每个租户几条"，全取回来按 {@link NotifyWebhook#subscribes} 判定更清晰。</p>
     */
    public List<NotifyWebhook> findSubscribers(Long tenantId, String eventType) {
        if (tenantId == null || eventType == null) {
            return List.of();
        }
        List<NotifyWebhook> enabled = webhookMapper.selectList(new LambdaQueryWrapper<NotifyWebhook>()
                .eq(NotifyWebhook::getTenantId, tenantId)
                .eq(NotifyWebhook::getEnabled, 1));
        List<NotifyWebhook> targets = new ArrayList<>();
        for (NotifyWebhook webhook : enabled) {
            if (webhook.subscribes(eventType)) {
                targets.add(webhook);
            }
        }
        return targets;
    }

    /** 新增配置（地址安全校验 + 重名校验 + 事件码校验）。 */
    @Transactional(rollbackFor = Exception.class)
    public WebhookVO create(WebhookCreateRequest request, Long tenantId, Long userId) {
        urlValidator.validate(request.url());
        validateEventTypes(request.eventTypes());
        validateNameUnique(tenantId, request.name(), null);
        NotifyWebhook webhook = NotifyWebhook.from(request, tenantId, userId);
        webhookMapper.insert(webhook);
        log.info("新增 Webhook 配置: id={}, tenant={}, name={}, url={}, events={}",
                webhook.getId(), tenantId, webhook.getName(), webhook.getUrl(), webhook.getEventTypes());
        return WebhookVO.from(webhook);
    }

    /** 更新配置（部分更新；改 URL 会重新做安全校验）。 */
    @Transactional(rollbackFor = Exception.class)
    public WebhookVO update(Long id, WebhookUpdateRequest request) {
        NotifyWebhook webhook = getRequired(id);
        if (request.url() != null && !request.url().isBlank()) {
            urlValidator.validate(request.url());
        }
        validateEventTypes(request.eventTypes());
        if (request.name() != null && !request.name().isBlank()) {
            validateNameUnique(webhook.getTenantId(), request.name(), id);
        }
        webhook.apply(request);
        // 走实体更新：eventTypes 是 JSON 列，用 wrapper.set 会因缺 typeHandler 报
        // "Cannot create a JSON value from a string with CHARACTER SET 'binary'"（AGENTS.md §5.13）
        webhookMapper.updateById(webhook);
        log.info("更新 Webhook 配置: id={}, enabled={}, url={}", id, webhook.getEnabled(), webhook.getUrl());
        return WebhookVO.from(webhook);
    }

    /** 删除（逻辑删除）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        NotifyWebhook webhook = getRequired(id);
        webhookMapper.deleteById(id);
        log.info("删除 Webhook 配置: id={}, name={}（投递台账保留，便于事后核对）", id, webhook.getName());
    }

    /**
     * 重名校验（租户内未删除行）。
     *
     * <p>为什么不用数据库唯一索引：{@code notify_webhook} 有逻辑删除列，
     * {@code uk(tenant_id, name)} 会把"删掉后重建同名配置"永久挡住
     * （{@code agent_task_step} 是用 {@code deleted=id} 的下标技巧绕开的，配置表不值得引入该复杂度）。</p>
     */
    private void validateNameUnique(Long tenantId, String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            return;
        }
        Long count = webhookMapper.selectCount(new LambdaQueryWrapper<NotifyWebhook>()
                .eq(NotifyWebhook::getTenantId, tenantId)
                .eq(NotifyWebhook::getName, name)
                .ne(excludeId != null, NotifyWebhook::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("Webhook 配置名称已存在: " + name);
        }
    }

    /** 订阅事件码校验：不认识的事件码会让配置"看起来生效、实际永不触发"。 */
    private void validateEventTypes(List<String> eventTypes) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            return;
        }
        for (String eventType : eventTypes) {
            if (!NotifyEventTypes.isValid(eventType)) {
                throw new BizException("未知的通知事件类型: " + eventType
                        + "（可选值见 docs/api/notify.md 事件目录）");
            }
        }
    }

    /** 启用中的配置数（验证脚本/排障用）。 */
    public long countEnabled(Long tenantId) {
        Long count = webhookMapper.selectCount(new LambdaQueryWrapper<NotifyWebhook>()
                .eq(NotifyWebhook::getTenantId, tenantId)
                .eq(NotifyWebhook::getEnabled, 1));
        return count == null ? 0L : count;
    }
}

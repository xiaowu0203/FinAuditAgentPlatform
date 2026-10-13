package com.finaudit.agentcore.service;

import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.starter.web.feign.TenantServiceFeign;
import com.finaudit.starter.web.result.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知收件人解析（P3.8 R8-2）。
 *
 * <p>两类收件人：</p>
 * <ul>
 *   <li><b>当事人</b>（申请人）：从业务数据直接取（{@code agent_task.created_by}），本地查询即可；</li>
 *   <li><b>角色收件人</b>（"有审批权限的人"、"管理员"）：本地无从得知，需按权限码向 tenant-service
 *       反查（见 {@code InternalUserController}）。</li>
 * </ul>
 *
 * <p><b>失败处理</b>：跨服务调用一律视为**可能失败的外部依赖**，失败即返回空列表——
 * 少发一条提醒可以接受，但因为"查不到审批人"把工单审批/任务收尾拖死不可接受。</p>
 *
 * <p><b>为什么不做本地缓存</b>：解析发生在"有人提交/审批"这类低频事件上，
 * 每次多一次 Feign 往返远小于一次 LLM 调用；而缓存会引入"改了角色权限后收件人仍是旧的"这类
 * 难查问题（权限变更事件已在 tenant-service 侧有专门的失效链路，本处不掺一脚）。
 * 若后续通知量级上来，应先加指标再谈缓存。</p>
 */
@Service
public class NotifyRecipientService {

    private static final Logger log = LoggerFactory.getLogger(NotifyRecipientService.class);

    /** 审批权限码：谁有它，谁该收到"有新工单待审批"。 */
    public static final String PERM_AUDIT_APPROVE = "audit:approve";

    /** 通知管理权限码（内置 admin 角色持有）：平台告警的收件人。 */
    public static final String PERM_NOTIFY_MANAGE = "notify:manage";

    private final TenantServiceFeign tenantServiceFeign;

    public NotifyRecipientService(TenantServiceFeign tenantServiceFeign) {
        this.tenantServiceFeign = tenantServiceFeign;
    }

    /** 申请人（任务创建人）。 */
    public List<Long> applicant(AgentTask task) {
        if (task == null || task.getCreatedBy() == null) {
            return List.of();
        }
        return List.of(task.getCreatedBy());
    }

    /** 有审批权限的人（转人工、撤销请求的提醒对象）。 */
    public List<Long> approvers(Long tenantId) {
        return byPerm(tenantId, PERM_AUDIT_APPROVE);
    }

    /** 平台管理员（MQ 死信、Webhook 连续失败等告警的提醒对象）。 */
    public List<Long> administrators(Long tenantId) {
        return byPerm(tenantId, PERM_NOTIFY_MANAGE);
    }

    /**
     * 按权限码取收件人（跨服务，兜底为空列表）。
     *
     * @param tenantId 租户ID（内部契约经 X-Tenant-Id 显式传递）
     * @param permCode 权限码
     * @return 用户 id 列表；调用失败或无命中返回空列表
     */
    public List<Long> byPerm(Long tenantId, String permCode) {
        if (tenantId == null || permCode == null || permCode.isBlank()) {
            return List.of();
        }
        try {
            R<List<Long>> resp = tenantServiceFeign.listUserIdsByPerm(tenantId, permCode);
            if (resp == null || resp.getCode() != 0 || resp.getData() == null) {
                log.warn("解析通知收件人失败（已降级为不通知该组）: tenant={}, perm={}, resp={}",
                        tenantId, permCode, resp == null ? "null" : resp.getMessage());
                return List.of();
            }
            return resp.getData();
        } catch (Exception e) {
            log.warn("解析通知收件人异常（已降级为不通知该组）: tenant={}, perm={}, err={}",
                    tenantId, permCode, e.toString());
            return List.of();
        }
    }
}

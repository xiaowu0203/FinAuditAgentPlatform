package com.finaudit.agentcore.service;

import com.finaudit.agentcore.domain.NotifyEvent;
import com.finaudit.agentcore.enums.AuditAction;
import com.finaudit.agentcore.enums.NotifyCategory;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AuditTicket;
import com.finaudit.agentcore.support.NotifyEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 业务通知门面（P3.8 R8-2）：把**业务语义**翻译成通知（谁收、什么文案、什么事件码、跳哪一页）。
 *
 * <p><b>为什么要单独一层</b>：编排器与工单服务里只该出现一行"发通知"的调用，
 * 而不该出现标题拼接、收件人解析、链接拼装这类展示逻辑——否则同一事件在多个触发点
 * 写出不同文案，或者改一次文案要动五处。所有文案集中在这里，也便于单测断言。</p>
 *
 * <p><b>收件人角色</b>：<ul>
 *   <li>申请人：任务/工单的 {@code createdBy}，跳转到自己的报销单详情；</li>
 *   <li>审批人：按权限码 {@code audit:approve} 反查（后台线程没有登录上下文），跳转到工单详情。</li>
 * </ul></p>
 *
 * <p><b>幂等键的取舍</b>：业务事件**不设**幂等键。原因是"驳回 → 修改重跑 → 再驳回"是三次真实事件，
 * 用 {@code (事件, 单据)} 做去重会把第二次提醒静默吃掉——这类"少发一条提醒"的 bug 几乎不可能被发现。
 * 只有"同一物理事件可能被重复投递"的场景（MQ 死信、Webhook 放弃）才设键。</p>
 *
 * <p>所有方法均兜底不抛：通知失败不影响审批与任务推进。</p>
 */
@Service
public class NotifyFacade {

    private static final Logger log = LoggerFactory.getLogger(NotifyFacade.class);

    private final NotifyEventPublisher publisher;
    private final NotifyRecipientService recipientService;

    public NotifyFacade(NotifyEventPublisher publisher, NotifyRecipientService recipientService) {
        this.publisher = publisher;
        this.recipientService = recipientService;
    }

    /** 自动通过（AUTO_PASS）：只通知申请人。 */
    public void autoPassed(AgentTask task) {
        try {
            if (task == null) {
                return;
            }
            String taskNo = task.getTaskNo() == null ? String.valueOf(task.getId()) : task.getTaskNo();
            publish(new NotifyEvent(task.getTenantId(), NotifyEventTypes.TASK_AUTO_PASSED,
                    NotifyCategory.AUDIT,
                    "报销单已自动通过",
                    "单据 " + taskNo + " 已由流水线自动审核通过，无需人工处理。",
                    "TASK", task.getId(), reimbLink(task), recipientService.applicant(task), null,
                    Map.of("taskNo", taskNo)));
        } catch (Exception e) {
            log.error("自动通过通知发布失败（已忽略）: taskId={}, err={}", taskId(task), e.toString());
        }
    }

    /**
     * 进入人工复核：通知申请人（你的单子需要人工看）**并**通知有审批权限的人（有新工单待处理）。
     *
     * @param task   任务
     * @param ticket 工单
     * @param rerun  true=修改重跑后再次命中（文案不同：提醒提交人白改了）
     */
    public void needReview(AgentTask task, AuditTicket ticket, boolean rerun) {
        try {
            if (task == null || ticket == null) {
                return;
            }
            String taskNo = task.getTaskNo() == null ? String.valueOf(task.getId()) : task.getTaskNo();
            String applicantTitle = rerun ? "修改后仍未通过，需人工复核" : "报销单需人工复核";
            String applicantContent = "单据 " + taskNo + " 命中复核规则（" + nvl(ticket.getTriggerType(), "未标注")
                    + "），已生成审批工单 " + nvl(ticket.getTicketNo(), "")
                    + "，等待财务处理。可复核原因：" + nvl(abbreviate(ticket.getRiskDesc()), "见工单详情");
            publish(new NotifyEvent(task.getTenantId(), NotifyEventTypes.TASK_NEED_REVIEW,
                    NotifyCategory.AUDIT, applicantTitle, applicantContent,
                    "TASK", task.getId(), reimbLink(task), recipientService.applicant(task), null,
                    Map.of("taskNo", taskNo, "ticketNo", nvl(ticket.getTicketNo(), ""), "rerun", rerun)));

            String approverTitle = (rerun ? "工单重新待审批：" : "新审批工单待处理：") + nvl(ticket.getTicketNo(), "");
            String approverContent = "单据 " + taskNo + " 命中复核规则（" + nvl(ticket.getTriggerType(), "未标注")
                    + "），请及时处理。可复核原因：" + nvl(abbreviate(ticket.getRiskDesc()), "见工单详情");
            publish(new NotifyEvent(ticket.getTenantId(), NotifyEventTypes.TICKET_CREATED,
                    NotifyCategory.AUDIT, approverTitle, approverContent,
                    "TICKET", ticket.getId(), ticketLink(ticket),
                    recipientService.approvers(ticket.getTenantId()), null,
                    Map.of("taskNo", taskNo, "ticketNo", nvl(ticket.getTicketNo(), ""))));
        } catch (Exception e) {
            log.error("人工复核通知发布失败（已忽略）: taskId={}, err={}", taskId(task), e.toString());
        }
    }

    /** 流水线失败：通知申请人。 */
    public void failed(AgentTask task, String error) {
        try {
            if (task == null) {
                return;
            }
            String taskNo = task.getTaskNo() == null ? String.valueOf(task.getId()) : task.getTaskNo();
            publish(new NotifyEvent(task.getTenantId(), NotifyEventTypes.TASK_FAILED, NotifyCategory.AUDIT,
                    "报销单审核失败",
                    "单据 " + taskNo + " 审核过程失败：" + nvl(abbreviate(error), "原因见任务详情")
                            + "。可联系管理员排查后重新提交。",
                    "TASK", task.getId(), reimbLink(task), recipientService.applicant(task), null,
                    Map.of("taskNo", taskNo, "error", nvl(abbreviate(error), ""))));
        } catch (Exception e) {
            log.error("任务失败通知发布失败（已忽略）: taskId={}, err={}", taskId(task), e.toString());
        }
    }

    /** 申请人发起撤销申请：通知有审批权限的人。 */
    public void withdrawRequested(AuditTicket ticket) {
        try {
            if (ticket == null) {
                return;
            }
            publish(new NotifyEvent(ticket.getTenantId(), NotifyEventTypes.TICKET_WITHDRAW_REQUESTED,
                    NotifyCategory.AUDIT,
                    "撤销申请待处理：" + nvl(ticket.getTicketNo(), ""),
                    "工单 " + nvl(ticket.getTicketNo(), "") + " 的提交人申请撤销已通过的单据，请确认是否同意作废。",
                    "TICKET", ticket.getId(), ticketLink(ticket),
                    recipientService.approvers(ticket.getTenantId()), null,
                    Map.of("ticketNo", nvl(ticket.getTicketNo(), ""))));
        } catch (Exception e) {
            log.error("撤销申请通知发布失败（已忽略）: ticketId={}, err={}",
                    ticket == null ? null : ticket.getId(), e.toString());
        }
    }

    /**
     * 财务审批动作的结局通知（通过 / 驳回 / 终止 / 撤销同意 / 撤销拒绝）。
     *
     * <p>收件人一律是申请人：这五件事都是"我的单子被怎么处理了"。
     * 未覆盖的动作（如未来的多级审批）只记 debug，不发通知——宁可少发，也不要发语义不明的提醒。</p>
     *
     * @param ticket  工单
     * @param action  动作
     * @param comment 审批意见（可为空）
     */
    public void ticketAction(AuditTicket ticket, AuditAction action, String comment) {
        try {
            if (ticket == null || action == null) {
                return;
            }
            String eventType = eventTypeOf(action);
            if (eventType == null) {
                log.debug("审批动作 {} 未配置通知事件，跳过通知: ticketId={}", action, ticket.getId());
                return;
            }
            String title = titleOf(action);
            String content = "工单 " + nvl(ticket.getTicketNo(), "") + " " + contentOf(action)
                    + (comment == null || comment.isBlank() ? "" : "。审批意见：" + abbreviate(comment));
            publish(new NotifyEvent(ticket.getTenantId(), eventType, NotifyCategory.AUDIT,
                    title, content, "TICKET", ticket.getId(), ticketLink(ticket),
                    ticket.getCreatedBy() == null ? List.of() : List.of(ticket.getCreatedBy()), null,
                    Map.of("ticketNo", nvl(ticket.getTicketNo(), ""), "action", action.name(),
                            "comment", nvl(abbreviate(comment), ""))));
        } catch (Exception e) {
            log.error("审批结局通知发布失败（已忽略）: ticketId={}, action={}, err={}",
                    ticket == null ? null : ticket.getId(), action, e.toString());
        }
    }

    /**
     * 动作 → 通知事件码。
     *
     * <p>其余动作（SUBMIT / AMEND / RERUN / RERUN_FAILED / WITHDRAW / WITHDRAW_REQ 等）
     * 是**过程性留痕**，不是"结局"：不主动提醒申请人，只落审计记录。给每一个内部动作都发提醒，
     * 只会让用户把通知当噪音（真正的结局反而被淹没）。</p>
     */
    private static String eventTypeOf(AuditAction action) {
        return switch (action) {
            case APPROVE -> NotifyEventTypes.TICKET_APPROVED;
            case REJECT -> NotifyEventTypes.TICKET_REJECTED;
            case TERMINATE, WITHDRAW_AGREE -> NotifyEventTypes.TICKET_TERMINATED;
            case WITHDRAW_REFUSE -> NotifyEventTypes.TICKET_WITHDRAW_REFUSED;
            default -> null;
        };
    }

    private static String titleOf(AuditAction action) {
        return switch (action) {
            case APPROVE -> "报销单已通过";
            case REJECT -> "报销单被驳回";
            case TERMINATE -> "审核已终止（单据作废）";
            case WITHDRAW_AGREE -> "撤销已同意（单据作废）";
            case WITHDRAW_REFUSE -> "撤销申请未通过";
            default -> "";
        };
    }

    private static String contentOf(AuditAction action) {
        return switch (action) {
            case APPROVE -> "已审批通过";
            case REJECT -> "被驳回，请按复核意见修改后重新提交";
            case TERMINATE -> "已被终止，单据作废";
            case WITHDRAW_AGREE -> "的撤销申请已同意，单据作废";
            case WITHDRAW_REFUSE -> "的撤销申请未通过，单据保持有效";
            default -> "";
        };
    }

    /** 申请人跳转：报销单详情（reimbId 取自任务入参快照，缺失则不跳）。 */
    private static String reimbLink(AgentTask task) {
        Object reimbId = inputParam(task, "reimbId");
        if (reimbId == null) {
            return null;
        }
        String id = String.valueOf(reimbId);
        // JSON 里的数字可能是 7 或 7.0，统一裁掉小数尾巴，避免拼出 /reimbursements/7.0
        if (id.endsWith(".0")) {
            id = id.substring(0, id.length() - 2);
        }
        return "/reimbursements/" + id;
    }

    private static String ticketLink(AuditTicket ticket) {
        return ticket.getId() == null ? null : "/audits/" + ticket.getId();
    }

    private static Object inputParam(AgentTask task, String key) {
        if (task == null || task.getInputParams() == null) {
            return null;
        }
        return task.getInputParams().get(key);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Long taskId(AgentTask task) {
        return task == null ? null : task.getId();
    }

    private void publish(NotifyEvent event) {
        publisher.publish(event);
    }
}

package com.finaudit.agentcore.pojo.vo;

import com.finaudit.agentcore.pojo.entity.NotifyMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内信出参（P3.8 R8-2）。
 *
 * <p>不直接回实体：实体带 {@code tenantId / deleted} 等内部字段，回实体等于把多租户实现细节
 * 暴露给前端（与 {@code TaskProgressVO} 同一取舍）。</p>
 */
@Getter
@Setter
public class NotifyMessageVO {

    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "类别: AUDIT 审核流转 / ALERT 平台告警")
    private String category;

    @Schema(description = "事件类型（见 docs/api/notify.md 事件目录）")
    private String eventType;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "业务类型: TASK / TICKET / REIMBURSEMENT / MQ")
    private String bizType;

    @Schema(description = "业务ID（前端可直接跳详情）")
    private Long bizId;

    @Schema(description = "跳转路径")
    private String link;

    @Schema(description = "是否已读")
    private Boolean read;

    @Schema(description = "已读时间（未读为空）")
    private LocalDateTime readAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 实体 → VO（转换封装在目标类，业务层禁止手写 set 组装——AGENTS.md §5.6）。 */
    public static NotifyMessageVO from(NotifyMessage entity) {
        NotifyMessageVO vo = new NotifyMessageVO();
        vo.setId(entity.getId());
        vo.setCategory(entity.getCategory());
        vo.setEventType(entity.getEventType());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setBizType(entity.getBizType());
        vo.setBizId(entity.getBizId());
        vo.setLink(entity.getLink());
        vo.setReadAt(entity.getReadAt());
        // 前端只需要一个布尔量判断红点，不必自己判 null
        vo.setRead(entity.getReadAt() != null);
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}

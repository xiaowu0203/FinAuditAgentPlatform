package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finaudit.agentcore.enums.NotifyCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内信（notify_message，P3.8 R8-2）。
 *
 * <p><b>一行 = 一个收件人的一条消息</b>：群发（如"通知所有审批人"）时按收件人展开成多行，
 * 已读状态各自独立。这样做而不是"一行消息 + 已读关联表"：通知的读态是**高频小写入**，
 * 展开后每次标记已读只改一行、不需要额外关联表，查询未读数也就是一条带索引的 COUNT。</p>
 */
@Getter
@Setter
@TableName("notify_message")
public class NotifyMessage {

    /** 标题上限（与 DDL 一致，实体边界截断：超长会让整行插入失败，静默丢掉一条通知） */
    public static final int TITLE_MAX_LEN = 128;
    /** 正文上限 */
    public static final int CONTENT_MAX_LEN = 1000;
    /** 跳转路径上限 */
    public static final int LINK_MAX_LEN = 255;
    /** 幂等键上限 */
    public static final int DEDUPE_KEY_MAX_LEN = 160;
    /** 事件码上限 */
    public static final int EVENT_TYPE_MAX_LEN = 48;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "收件人用户ID")
    private Long userId;

    @Schema(description = "类别: AUDIT 审核流转 / ALERT 平台告警")
    private String category;

    @Schema(description = "事件类型（见 NotifyEventTypes）")
    private String eventType;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "业务类型: TASK / TICKET / REIMBURSEMENT / MQ")
    private String bizType;

    @Schema(description = "业务ID")
    private Long bizId;

    @Schema(description = "前端跳转路径")
    private String link;

    @Schema(description = "幂等键（同租户唯一；NULL=不去重）")
    private String dedupeKey;

    @Schema(description = "已读时间（NULL=未读）")
    private LocalDateTime readAt;

    /** 创建时间走数据库默认值（与其余实体一致，不标注 fill） */
    private LocalDateTime createdAt;

    /** ⚠️ 必须标注 INSERT_UPDATE：否则 updateById 会把旧值写回，抑制 MySQL 的 ON UPDATE（见 §5 规范） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    /**
     * 新建一条站内信（静态工厂，业务层禁止手写 set 组装——AGENTS.md §5.6）。
     *
     * <p>各文本字段在此**按 DDL 长度截断**：本仓踩过"超长导致整行写库失败、被兜底 catch 静默丢弃"
     * 的坑（R9 的 {@code model_call_log.error_msg}）——通知丢一条不会报错，但用户就永远收不到提醒。</p>
     */
    public static NotifyMessage from(Long tenantId, Long userId, NotifyCategory category, String eventType,
                                     String title, String content, String bizType, Long bizId,
                                     String link, String dedupeKey) {
        NotifyMessage m = new NotifyMessage();
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setCategory(category == null ? NotifyCategory.AUDIT.name() : category.name());
        m.setEventType(truncate(eventType, EVENT_TYPE_MAX_LEN));
        m.setTitle(truncate(title, TITLE_MAX_LEN));
        m.setContent(truncate(content, CONTENT_MAX_LEN));
        m.setBizType(truncate(bizType, 32));
        m.setBizId(bizId);
        m.setLink(truncate(link, LINK_MAX_LEN));
        m.setDedupeKey(truncate(dedupeKey, DEDUPE_KEY_MAX_LEN));
        return m;
    }

    /** 标记已读（幂等：已读的再标一次不改变原时间，保留"第一次看到"的语义）。 */
    public void markRead(LocalDateTime at) {
        if (this.readAt == null) {
            this.readAt = at == null ? LocalDateTime.now() : at;
        }
    }

    /** 按列宽截断（null 原样返回）。 */
    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}

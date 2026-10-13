package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.mapper.NotifyMessageMapper;
import com.finaudit.agentcore.pojo.entity.NotifyMessage;
import com.finaudit.agentcore.pojo.vo.NotifyMessageVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内信服务（P3.8 R8-2）：{@code notify_message} 的所有读写收敛于此（AGENTS.md §5.9）。
 *
 * <p><b>核心纪律：发通知失败绝不能拖垮业务</b>。通知是"锦上添花"——工单该驳回归驳回、
 * 任务该收尾归收尾；若因为写一条提醒失败就回滚一次审批，那是拿关键路径给辅助功能陪葬。
 * 故写入路径全程兜底：单行失败降级为逐行插入，逐行仍失败只记 ERROR，不抛。</p>
 *
 * <p><b>⚠️ 关于"兜底必须留落库痕迹"（AGENTS.md §5.14）</b>：本处是那条规范的边界情形——
 * 失败的原因本身往往就是"写不进库"，此时没有任何可写的地方，只能落日志。
 * 因此把可预见的失败类型**全部前移**到可控位置：
 * 超长文本在实体工厂里按列宽截断（{@link NotifyMessage#from}）、
 * 重复提醒靠唯一索引 {@code uk_notify_dedupe} + {@link DuplicateKeyException} 显式识别
 * （"已提醒过"是正常语义，不是错误）。</p>
 */
@Service
public class NotifyMessageService {

    private static final Logger log = LoggerFactory.getLogger(NotifyMessageService.class);

    private final NotifyMessageMapper messageMapper;

    public NotifyMessageService(NotifyMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /**
     * 批量写入站内信（兜底不抛）。
     *
     * @param messages 待写入消息（空集合直接返回 0）
     * @return 实际写入行数（去重命中的行不计入）
     */
    public int send(List<NotifyMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        try {
            return messageMapper.insertBatch(messages);
        } catch (DuplicateKeyException e) {
            // 整批被一条重复行带崩：降级为逐行写入，让其余收件人照常收到提醒
            log.debug("站内信批量写入命中幂等键（uk_notify_dedupe），降级逐行写入: {}", messages.size());
            return sendOneByOne(messages);
        } catch (Exception e) {
            log.error("站内信批量写入失败（已忽略，不影响业务）: size={}, err={}", messages.size(), e.toString());
            return sendOneByOne(messages);
        }
    }

    private int sendOneByOne(List<NotifyMessage> messages) {
        int ok = 0;
        for (NotifyMessage m : messages) {
            try {
                ok += messageMapper.insertBatch(List.of(m));
            } catch (DuplicateKeyException e) {
                log.debug("站内信已存在（幂等键命中），跳过: user={}, event={}, dedupe={}",
                        m.getUserId(), m.getEventType(), m.getDedupeKey());
            } catch (Exception e) {
                // 到这里说明该行确实写不进去（列约束/库异常）：必须留完整上下文，否则一条通知会无声消失
                log.error("站内信写入失败（已忽略该行）: user={}, event={}, bizType={}, bizId={}, title={}, err={}",
                        m.getUserId(), m.getEventType(), m.getBizType(), m.getBizId(), m.getTitle(), e.toString());
            }
        }
        return ok;
    }

    /** 我的消息分页（只查本人：租户由拦截器追加，用户由条件显式限定——避免越权读他人提醒）。 */
    public Page<NotifyMessageVO> page(Long userId, boolean unreadOnly, int pageNum, int pageSize) {
        LambdaQueryWrapper<NotifyMessage> wrapper = new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .isNull(unreadOnly, NotifyMessage::getReadAt)
                .orderByDesc(NotifyMessage::getCreatedAt)
                .orderByDesc(NotifyMessage::getId);
        Page<NotifyMessage> page = messageMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return (Page<NotifyMessageVO>) page.convert(NotifyMessageVO::from);
    }

    /** 未读数（前端红点）。 */
    public long unreadCount(Long userId) {
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .isNull(NotifyMessage::getReadAt));
        return count == null ? 0L : count;
    }

    /**
     * 标记单条已读。
     *
     * <p>条件里带 {@code userId} 是**越权防线**：否则拿到别人消息 id 就能改他人读态。
     * 已读的再标一次不报错（{@code read_at} 为空才更新，保留"第一次看到"的时间）。</p>
     *
     * @return 是否命中一条未读消息
     */
    public boolean markRead(Long id, Long userId) {
        int rows = messageMapper.update(null, new LambdaUpdateWrapper<NotifyMessage>()
                .eq(NotifyMessage::getId, id)
                .eq(NotifyMessage::getUserId, userId)
                .isNull(NotifyMessage::getReadAt)
                .set(NotifyMessage::getReadAt, LocalDateTime.now()));
        return rows > 0;
    }

    /** 全部标记已读。 */
    public int markAllRead(Long userId) {
        return messageMapper.update(null, new LambdaUpdateWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .isNull(NotifyMessage::getReadAt)
                .set(NotifyMessage::getReadAt, LocalDateTime.now()));
    }
}

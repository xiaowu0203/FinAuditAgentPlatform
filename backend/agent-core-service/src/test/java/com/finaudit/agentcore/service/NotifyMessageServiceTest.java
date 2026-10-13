package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.enums.NotifyCategory;
import com.finaudit.agentcore.mapper.NotifyMessageMapper;
import com.finaudit.agentcore.pojo.entity.NotifyMessage;
import com.finaudit.agentcore.pojo.vo.NotifyMessageVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信服务单测（P3.8 R8-2）。
 *
 * <p>核心是"发通知失败不能中断调用方"这条纪律：批量失败要降级逐行（别让一条重复行带走其余收件人）、
 * 单行失败只记日志（别抛）、标记已读必须限定本人（越权防线）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifyMessageServiceTest {

    @Mock
    private NotifyMessageMapper messageMapper;

    @InjectMocks
    private NotifyMessageService service;

    /**
     * 注册实体元信息：{@code LambdaUpdateWrapper} 的方法引用要靠 MyBatis-Plus 的 TableInfo 缓存
     * 解析成列名，纯单测里没有 MyBatis 容器，不初始化就会报
     * "can not find lambda cache for this entity"（本仓既有测试同款做法）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NotifyMessage.class);
    }

    private static NotifyMessage message(long userId) {
        return NotifyMessage.from(1L, userId, NotifyCategory.AUDIT, "TICKET_APPROVED",
                "标题", "正文", "TICKET", 12L, "/audits/12", "TICKET_APPROVED:12:" + userId);
    }

    @Test
    void sendWritesAllMessagesInOneStatement() {
        when(messageMapper.insertBatch(any())).thenReturn(2);

        int written = service.send(List.of(message(1), message(2)));

        assertEquals(2, written);
        verify(messageMapper, times(1)).insertBatch(any());
    }

    @Test
    void emptyListIsNoOp() {
        assertEquals(0, service.send(List.of()));
        assertEquals(0, service.send(null));
        verify(messageMapper, times(0)).insertBatch(any());
    }

    @Test
    void duplicateKeyInBatchFallsBackToRowByRowSoOthersStillArrive() {
        // 调用序列：1) 批量 → 命中幂等键；2) 逐行第 1 条 → 成功；3) 逐行第 2 条 → 也成功
        when(messageMapper.insertBatch(any()))
                .thenThrow(new DuplicateKeyException("uk_notify_dedupe"))
                .thenReturn(1)
                .thenReturn(1);

        int written = service.send(List.of(message(1), message(2)));

        assertEquals(2, written, "一条重复不该让另一位收件人收不到提醒");
        // 1 次批量失败 + 2 次逐行成功 = 3 次调用
        verify(messageMapper, times(3)).insertBatch(any());
    }

    @Test
    void duplicateRowInFallbackIsSkippedWithoutCounting() {
        when(messageMapper.insertBatch(any()))
                .thenThrow(new DuplicateKeyException("uk_notify_dedupe"))
                .thenReturn(1)
                .thenThrow(new DuplicateKeyException("uk_notify_dedupe"));

        assertEquals(1, service.send(List.of(message(1), message(2))),
                "逐行阶段命中幂等键应跳过且不计入写入数");
    }

    @Test
    void rowFailureIsLoggedAndSwallowedInsteadOfPropagating() {
        when(messageMapper.insertBatch(any()))
                .thenThrow(new RuntimeException("Data too long for column 'title'"))
                .thenThrow(new RuntimeException("Data too long for column 'title'"))
                .thenReturn(1);

        // 不抛异常 = 业务事务不受影响
        int written = service.send(List.of(message(1), message(2)));

        assertEquals(1, written, "坏行被跳过，好行照常写入");
    }

    @Test
    void markReadScopesToCurrentUser() {
        when(messageMapper.update(any(), any())).thenReturn(1);

        assertTrue(service.markRead(5L, 7L));
        // 关键：更新条件里必须带 user_id，否则拿到别人的消息 id 就能改他人读态
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<NotifyMessage>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(messageMapper).update(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"), "更新条件必须包含 user_id，实际=" + sql);
        assertTrue(sql.contains("read_at"), "必须只更新未读行（保留第一次已读时间）");
    }

    @Test
    void markReadReturnsFalseWhenNothingMatched() {
        when(messageMapper.update(any(), any())).thenReturn(0);

        assertFalse(service.markRead(5L, 7L), "未命中（别人的消息/已读）应返回 false 而不是报错");
    }

    @Test
    void pageConvertsEntityToVoWithReadFlag() {
        NotifyMessage unread = message(7);
        Page<NotifyMessage> raw = new Page<>(1, 10);
        raw.setRecords(List.of(unread));
        raw.setTotal(1);
        when(messageMapper.selectPage(any(), any())).thenReturn(raw);

        Page<NotifyMessageVO> page = service.page(7L, true, 1, 10);

        assertEquals(1, page.getRecords().size());
        NotifyMessageVO vo = page.getRecords().get(0);
        assertFalse(vo.getRead(), "未读消息 read 必须为 false");
        assertEquals("TICKET_APPROVED", vo.getEventType());
        assertEquals("/audits/12", vo.getLink());
    }

    @Test
    void unreadCountHandlesNullResult() {
        when(messageMapper.selectCount(any())).thenReturn(null);

        assertEquals(0L, service.unreadCount(7L), "count 为 null 时不能 NPE");
    }
}

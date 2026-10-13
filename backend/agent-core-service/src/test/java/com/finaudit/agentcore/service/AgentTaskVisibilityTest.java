package com.finaudit.agentcore.service;

import com.finaudit.agentcore.mapper.AgentTaskMapper;
import com.finaudit.agentcore.mq.TaskEventPublisher;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.starter.web.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务可见性校验单测（P3.8 R7-4）。
 *
 * <p><b>为什么要单独测这个方法</b>：它是「读接口的越权闸门」——任务详情、步骤列表都靠它，
 * 而原实现在 {@code userId == null} 时直接 {@code userId.equals(...)} 抛 NPE，
 * 把一个本该是「403 无权查看」的场景变成了「500 服务器错误」。
 * 两者的区别在真实系统里很重要：500 会被当成故障排查，403 才是正常的拒绝语义。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentTaskVisibilityTest {

    @Mock
    private AgentTaskMapper taskMapper;
    @Mock
    private TaskEventPublisher eventPublisher;
    @Mock
    private AgentTaskStepService stepService;

    private AgentTaskService service() {
        return new AgentTaskService(taskMapper, eventPublisher, stepService, mock(TaskProgressService.class));
    }

    private void givenTask(Long taskId, Long createdBy) {
        AgentTask task = new AgentTask();
        task.setId(taskId);
        task.setTenantId(1L);
        task.setCreatedBy(createdBy);
        when(taskMapper.selectById(taskId)).thenReturn(task);
    }

    @Test
    void nullUserIdIsRejectedAsBusinessErrorNotNpe() {
        // 无登录上下文（userId=null）+ createdBy 有值 + 无全量权限 → 拒绝，且必须是 BizException（403 语义）
        givenTask(100L, 7L);

        BizException e = assertThrows(BizException.class,
                () -> service().requireVisible(100L, null, false));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("无权"), e.getMessage());
    }

    @Test
    void nullUserIdWithCreatedByNullIsRejected() {
        // 历史任务没有 createdBy（旧数据）：无全量权限者一律拒绝
        givenTask(100L, null);

        assertThrows(BizException.class, () -> service().requireVisible(100L, null, false));
    }

    @Test
    void ownerCanViewOwnTask() {
        givenTask(100L, 7L);

        assertDoesNotThrow(() -> service().requireVisible(100L, 7L, false));
    }

    @Test
    void nonOwnerIsRejected() {
        givenTask(100L, 7L);

        assertThrows(BizException.class, () -> service().requireVisible(100L, 8L, false));
    }

    @Test
    void viewAllPermissionSeesAnyTaskIncludingLegacy() {
        givenTask(100L, 7L);

        assertDoesNotThrow(() -> service().requireVisible(100L, 99L, true));
        assertDoesNotThrow(() -> service().requireVisible(100L, null, true));
    }
}

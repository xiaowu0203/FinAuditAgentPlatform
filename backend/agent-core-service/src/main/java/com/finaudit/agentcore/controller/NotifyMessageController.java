package com.finaudit.agentcore.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.agentcore.pojo.vo.NotifyMessageVO;
import com.finaudit.agentcore.service.NotifyMessageService;
import com.finaudit.starter.web.auth.UserContext;
import com.finaudit.starter.web.auth.UserContextHolder;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站内信端点（P3.8 R8-2）。
 *
 * <p><b>为什么不需要权限码</b>：这些接口只能操作**自己的**消息（收件人 ID 一律取登录上下文，
 * 不接受前端传参）。"能看自己的提醒"是登录用户的基本能力，加权限码只会让默认角色看不到红点。
 * 越权防线在数据层：所有查询与更新条件都带 {@code user_id = 当前用户}，
 * 故拿到别人的消息 id 也改不动、看不到。</p>
 */
@Tag(name = "站内信", description = "P3.8 R8-2：我的通知列表 / 未读数 / 标记已读")
@RestController
@RequestMapping("/api/v1/notify/messages")
public class NotifyMessageController {

    private final NotifyMessageService messageService;

    public NotifyMessageController(NotifyMessageService messageService) {
        this.messageService = messageService;
    }

    @Operation(summary = "我的消息分页", description = "unreadOnly=true 只看未读；按创建时间倒序")
    @GetMapping
    public R<Page<NotifyMessageVO>> page(@RequestParam(defaultValue = "1") int pageNum,
                                         @RequestParam(defaultValue = "10") int pageSize,
                                         @RequestParam(defaultValue = "false") boolean unreadOnly) {
        Long userId = currentUserId();
        return R.success(messageService.page(userId, unreadOnly, pageNum, pageSize));
    }

    @Operation(summary = "未读消息数", description = "前端红点/角标用；只统计本人未读")
    @GetMapping("/unread-count")
    public R<Map<String, Object>> unreadCount() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unread", messageService.unreadCount(currentUserId()));
        return R.success(body);
    }

    @Operation(summary = "标记单条已读", description = "只能标记本人的消息；已读再标不报错")
    @PostMapping("/{id}/read")
    public R<Boolean> markRead(@PathVariable Long id) {
        return R.success(messageService.markRead(id, currentUserId()));
    }

    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public R<Integer> markAllRead() {
        return R.success(messageService.markAllRead(currentUserId()));
    }

    /** 当前用户 id：缺失说明调用未经网关（网关会注入 X-User-Id），fail-closed 而不是取默认用户。 */
    private Long currentUserId() {
        UserContext user = UserContextHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BizException("缺少用户上下文，请通过网关访问");
        }
        return user.getUserId();
    }
}

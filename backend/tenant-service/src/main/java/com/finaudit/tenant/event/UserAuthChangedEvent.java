package com.finaudit.tenant.event;

/**
 * 用户身份变更事件（P3.5 实时生效）：用户角色绑定 / 用户信息（部门/状态）变更 / 用户删除后发布。
 * <p>{@code AuthService} 事务提交后监听并重写该用户的 Redis 权限快照——网关下一请求即读到新权限。
 * 用事件解耦而非 Service 直调，避免 SysUserService → AuthService → SysUserService 反向依赖成环
 * （CLAUDE.md 规范 12）。</p>
 *
 * @param userId 变更用户 ID
 */
public record UserAuthChangedEvent(Long userId) {
}

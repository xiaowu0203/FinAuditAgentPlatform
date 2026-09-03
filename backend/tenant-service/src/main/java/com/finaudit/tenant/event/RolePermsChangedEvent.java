package com.finaudit.tenant.event;

/**
 * 角色权限变更事件（P3.5 实时生效）：角色-权限替换式分配后发布。
 * <p>{@code AuthService} 事务提交后监听：反查该角色绑定用户并逐个重写权限快照，
 * 使权限调整对在线用户即时生效。事件解耦理由同 {@link UserAuthChangedEvent}。</p>
 *
 * @param roleId 权限发生变更的角色 ID
 */
public record RolePermsChangedEvent(Long roleId) {
}

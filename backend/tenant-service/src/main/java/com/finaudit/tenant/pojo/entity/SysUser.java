package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finaudit.tenant.pojo.dto.UserCreateRequest;
import com.finaudit.tenant.pojo.dto.UserUpdateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户（sys_user）。
 * <p>密码为 BCrypt 哈希，禁止明文；多租户拦截器按 tenant_id 自动隔离。</p>
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "登录名")
    private String username;

    @Schema(description = "密码（BCrypt 哈希）")
    private String password;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "部门ID（P3.5b 员工级归属；未绑定为 null）")
    private Long deptId;

    @Schema(description = "状态: 1启用 0禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由新增请求构造新用户。
     *
     * @param request        新增请求
     * @param tenantId       所属租户（取上下文，不信任请求体）
     * @param encodedPassword 已编码的密码（BCrypt）
     */
    public static SysUser from(UserCreateRequest request, Long tenantId, String encodedPassword) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setUsername(request.username());
        user.setPassword(encodedPassword);
        user.setRealName(request.realName());
        user.setPhone(request.phone());
        user.setDeptId(request.deptId());
        user.setStatus(request.status() == null ? 1 : request.status());
        return user;
    }

    /**
     * 应用更新请求。
     *
     * @param request        更新请求
     * @param encodedPassword 新密码哈希；为 null 表示不修改密码
     */
    public void apply(UserUpdateRequest request, String encodedPassword) {
        if (encodedPassword != null) {
            this.setPassword(encodedPassword);
        }
        if (request.realName() != null) {
            this.setRealName(request.realName());
        }
        if (request.phone() != null) {
            this.setPhone(request.phone());
        }
        if (request.deptId() != null) {
            // 0=解绑（清空员工级部门归属）；否则直接绑定
            this.setDeptId(request.deptId() == 0L ? null : request.deptId());
        }
        if (request.status() != null) {
            this.setStatus(request.status());
        }
    }
}

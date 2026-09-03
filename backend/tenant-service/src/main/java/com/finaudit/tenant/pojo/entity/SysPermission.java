package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 权限目录（sys_permission，P3.5a）。
 * <p><b>平台级全局表，无 tenant_id</b>：所有租户共用同一套权限标识符，权限码由迁移脚本
 * 种子定义（代码即目录），运行期不增删。多租户拦截器已注册忽略本表。</p>
 */
@Getter
@Setter
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    @Schema(description = "权限ID")
    private Long id;

    @Schema(description = "权限标识符（资源:操作 / 资源级，如 user:create / reimb:viewAll）")
    private String permCode;

    @Schema(description = "权限名称（分配界面展示）")
    private String permName;

    @Schema(description = "类型: MENU 菜单+接口 / API 仅接口")
    private String permType;

    @Schema(description = "分组: 系统管理/财务业务/预留")
    private String groupName;

    @Schema(description = "状态: 1启用 0禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}

package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 部门（sys_dept，P3.5b 部门实体）。
 * <p>租户内树形：{@code parent_id=0} 为根；{@code uk(tenant_id, dept_name)} 租户内部门名唯一，
 * 是报销单/预算/用户的 1:1 关联键——dept_name 从自由字符串退役为权威部门主数据，
 * 业务表仅存 dept_id + 提交时 dept_name 快照。</p>
 */
@Getter
@Setter
@TableName("sys_dept")
public class SysDept {

    @TableId(type = IdType.AUTO)
    @Schema(description = "部门ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "父部门ID（0=根）")
    private Long parentId;

    @Schema(description = "部门名称（租户内唯一）")
    private String deptName;

    @Schema(description = "状态: 1启用 0停用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除: 0未删 1已删")
    private Integer deleted;
}
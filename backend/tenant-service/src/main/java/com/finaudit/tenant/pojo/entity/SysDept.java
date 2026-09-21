package com.finaudit.tenant.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finaudit.tenant.pojo.dto.DeptCreateRequest;
import com.finaudit.tenant.pojo.dto.DeptUpdateRequest;
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

    /** 启用状态（DDL 缺省 1；创建时显式赋值，避免依赖列默认值） */
    public static final int STATUS_ENABLED = 1;

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

    /**
     * 由新增请求构造部门（P3.8 R7-5：与 {@code AgentTask.from} / {@code ToolRegistry.from} 同口径）。
     * <p>转换收敛在实体类，业务层不再手写 set 组装——P3.5b 引入部门实体时漏了这条，
     * 导致创建逻辑与"实体转换封装在实体类"的项目约定不一致。</p>
     *
     * @param request  新增部门请求（名称已由调用方 trim 校验）
     * @param tenantId 当前租户
     * @param parentId 父部门 ID（根为 0，已由调用方归一）
     */
    public static SysDept from(DeptCreateRequest request, Long tenantId, Long parentId) {
        SysDept dept = new SysDept();
        dept.setTenantId(tenantId);
        dept.setParentId(parentId);
        dept.setDeptName(request.deptName().trim());
        dept.setStatus(STATUS_ENABLED);
        return dept;
    }

    /**
     * 用更新请求合并修改自身（P3.8 R7-5）：只覆盖请求显式给出的字段。
     * <p>名称与父级的唯一性/防环校验由 Service 在调用前完成（需要查询，不属于实体职责）；
     * 本方法只做字段合并，保证「更新怎么写进实体」这件事只有一处。</p>
     */
    public void apply(DeptUpdateRequest request) {
        if (request.deptName() != null && !request.deptName().isBlank()) {
            this.deptName = request.deptName().trim();
        }
        if (request.parentId() != null) {
            this.parentId = request.parentId();
        }
        if (request.status() != null) {
            this.status = request.status();
        }
    }
}
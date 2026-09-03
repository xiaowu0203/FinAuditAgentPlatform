package com.finaudit.tenant.pojo.vo;

import com.finaudit.tenant.pojo.entity.SysDept;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点 VO（P3.5b）。
 * <p>树由 {@code SysDeptService.listTree()} 内存组树（MySQL 5.7 无递归 CTE），
 * 叶子节点 children 为空列表。</p>
 */
@Getter
@Setter
public class DeptVO {

    @Schema(description = "部门ID")
    private Long id;

    @Schema(description = "父部门ID（0=根）")
    private Long parentId;

    @Schema(description = "部门名称")
    private String deptName;

    @Schema(description = "状态: 1启用 0停用")
    private Integer status;

    @Schema(description = "子部门（叶子为空列表）")
    private List<DeptVO> children = new ArrayList<>();

    public static DeptVO from(SysDept dept) {
        DeptVO vo = new DeptVO();
        vo.setId(dept.getId());
        vo.setParentId(dept.getParentId());
        vo.setDeptName(dept.getDeptName());
        vo.setStatus(dept.getStatus());
        return vo;
    }
}
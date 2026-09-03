package com.finaudit.tenant.service;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.mapper.SysDeptMapper;
import com.finaudit.tenant.pojo.dto.DeptCreateRequest;
import com.finaudit.tenant.pojo.dto.DeptUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysDept;
import com.finaudit.tenant.pojo.vo.DeptVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部门服务单测（P3.5b）：树构建（含多层）、create parent 校验、update 防环、delete 引用拒删。
 */
@ExtendWith(MockitoExtension.class)
class SysDeptServiceTest {

    @Mock
    private SysDeptMapper deptMapper;
    @Mock
    private SysUserService userService;

    private SysDeptService deptService;

    @BeforeEach
    void setUp() {
        // @Mock 在构造后由扩展注入，不能用于字段初始化器
        deptService = new SysDeptService(deptMapper, userService);
    }

    private SysDept dept(long id, long parent, String name) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setParentId(parent);
        dept.setDeptName(name);
        dept.setStatus(1);
        return dept;
    }

    // ---------- 树构建 ----------

    @Test
    void listTree_buildsMultiLevelTree() {
        when(deptMapper.selectList(any())).thenReturn(List.of(
                dept(1, 0, "财务部"),
                dept(2, 1, "财务一部"),
                dept(3, 2, "财务一组")));

        List<DeptVO> roots = deptService.listTree();

        assertEquals(1, roots.size());
        assertEquals("财务部", roots.get(0).getDeptName());
        assertEquals(1, roots.get(0).getChildren().size());
        assertEquals("财务一部", roots.get(0).getChildren().get(0).getDeptName());
        assertEquals(1, roots.get(0).getChildren().get(0).getChildren().size());
        assertEquals("财务一组", roots.get(0).getChildren().get(0).getChildren().get(0).getDeptName());
    }

    @Test
    void listTree_orphanNodePromotedToRoot() {
        when(deptMapper.selectList(any())).thenReturn(List.of(
                dept(1, 0, "财务部"),
                dept(2, 99, "孤儿部门"))); // parent 99 不存在

        List<DeptVO> roots = deptService.listTree();

        assertEquals(2, roots.size());
    }

    // ---------- create ----------

    @Test
    void create_shallowRootNoParentValidation() {
        when(deptMapper.selectCount(any())).thenReturn(0L);
        when(deptMapper.insert(any(SysDept.class))).thenReturn(1);

        SysDept created = deptService.create(new DeptCreateRequest("新部门", null), 1L);

        assertEquals(0L, created.getParentId());
        assertEquals("新部门", created.getDeptName());
        verify(deptMapper, never()).selectById(any());
    }

    @Test
    void create_parentMustExist() {
        when(deptMapper.selectById(999L)).thenReturn(null);

        assertThrows(BizException.class,
                () -> deptService.create(new DeptCreateRequest("新部门", 999L), 1L));
    }

    @Test
    void create_duplicateNameRejected() {
        when(deptMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BizException.class,
                () -> deptService.create(new DeptCreateRequest("财务部", null), 1L));
    }

    // ---------- update 防环 ----------

    @Test
    void update_parentToOwnDescendantRejected() {
        // 树：1→2→3；把 1 挂到 3 下 = 挂到自己子孙 → 拒绝
        when(deptMapper.selectById(1L)).thenReturn(dept(1, 0, "财务部"));
        when(deptMapper.selectById(3L)).thenReturn(dept(3, 2, "财务一组"));
        when(deptMapper.selectById(2L)).thenReturn(dept(2, 1, "财务一部"));

        BizException ex = assertThrows(BizException.class,
                () -> deptService.update(1L, new DeptUpdateRequest(null, 3L, null)));
        assertTrue(ex.getMessage().contains("子孙"));
    }

    @Test
    void update_parentToSelfRejected() {
        when(deptMapper.selectById(1L)).thenReturn(dept(1, 0, "财务部"));
        // 把自己挂到自己下：第一跳即命中自身
        when(deptMapper.selectById(1L)).thenReturn(dept(1, 0, "财务部"));

        assertThrows(BizException.class,
                () -> deptService.update(1L, new DeptUpdateRequest(null, 1L, null)));
    }

    @Test
    void update_renameOnlyPasses() {
        SysDept current = dept(5, 0, "技术部");
        when(deptMapper.selectById(5L)).thenReturn(current);
        when(deptMapper.selectCount(any())).thenReturn(0L);

        SysDept updated = deptService.update(5L, new DeptUpdateRequest("技术一部", null, null));

        assertEquals("技术一部", updated.getDeptName());
        verify(deptMapper).updateById(current);
    }

    // ---------- delete 引用守卫 ----------

    @Test
    void delete_hasChildrenRejected() {
        when(deptMapper.selectById(1L)).thenReturn(dept(1, 0, "财务部"));
        when(deptMapper.selectCount(any())).thenReturn(1L); // 存在子部门

        assertThrows(BizException.class, () -> deptService.delete(1L));
        verify(userService, never()).countByDept(any());
    }

    @Test
    void delete_userBoundRejected() {
        when(deptMapper.selectById(1L)).thenReturn(dept(1, 0, "财务部"));
        when(deptMapper.selectCount(any())).thenReturn(0L); // 无子部门
        when(userService.countByDept(1L)).thenReturn(3L);    // 3 个用户绑定

        assertThrows(BizException.class, () -> deptService.delete(1L));
        verify(deptMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void delete_okWhenNoChildrenAndNoUsers() {
        when(deptMapper.selectById(1L)).thenReturn(dept(1, 0, "财务部"));
        when(deptMapper.selectCount(any())).thenReturn(0L);
        when(userService.countByDept(1L)).thenReturn(0L);

        deptService.delete(1L);

        verify(deptMapper).deleteById(1L);
    }
}
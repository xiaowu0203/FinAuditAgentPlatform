package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.mapper.SysDeptMapper;
import com.finaudit.tenant.pojo.dto.DeptCreateRequest;
import com.finaudit.tenant.pojo.dto.DeptUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysDept;
import com.finaudit.tenant.pojo.vo.DeptVO;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 部门服务
 * <p>树形语义：parent_id=0 为根；MySQL 5.7 无递归 CTE，树在内存组（全量查）。
 * 写约束：create parent 存在性；update 防环（不能把节点挂到自身子孙下）；
 * delete 受子部门/用户引用约束（经 {@link SysUserService} 委托，不触碰用户 Mapper）。</p>
 */
@Service
public class SysDeptService {

    private final SysDeptMapper deptMapper;
    private final SysUserService userService;

    public SysDeptService(SysDeptMapper deptMapper, @Lazy SysUserService userService) {
        this.deptMapper = deptMapper;
        this.userService = userService;
    }

    /**
     * 获取部门树结构
     * <p>包含全部部门，含已停用部门；根节点parent_id=0；父节点缺失的孤儿节点收敛为根节点</p>
     * <p>租户隔离：由多租户拦截器MyBatis‑Plus自动拼接tenant_id条件，业务层无需手动处理</p>
     * @return 组装完成的部门树根节点集合
     */
    public List<DeptVO> listTree() {
        // 查询当前租户全部部门，按ID升序
        List<SysDept> all = deptMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .orderByAsc(SysDept::getId));

        // 将所有部门放入Map，key=部门ID，用于快速查找父节点，LinkedHashMap保证顺序
        Map<Long, DeptVO> nodeMap = new LinkedHashMap<>();
        for (SysDept dept : all) {
            nodeMap.put(dept.getId(), DeptVO.from(dept));
        }
        List<DeptVO> roots = new ArrayList<>();
        // 遍历所有部门，构建父子层级关系
        for (SysDept dept : all) {
            DeptVO node = nodeMap.get(dept.getId());
            // parentId为null或0代表根节点
            DeptVO parent = dept.getParentId() == null || dept.getParentId() == 0L
                    ? null : nodeMap.get(dept.getParentId());
            if (parent == null) {
                // 根节点/孤儿节点，放入根集合
                roots.add(node);
            } else {
                // 挂载到父节点的children子集合
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    /**
     * 根据ID获取部门，不存在直接抛出业务异常
     * @param id 部门ID
     * @return 部门实体
     * @throws BizException 部门不存在抛出异常
     */
    public SysDept getRequired(Long id) {
        SysDept dept = deptMapper.selectById(id);
        if (dept == null) {
            throw new BizException("部门不存在: " + id);
        }
        return dept;
    }

    /**
     * 判断部门是否存在并且状态为启用
     * <p>用于跨服务调用、接口越权校验场景，只认可status=1有效部门</p>
     * @param deptId 部门ID
     * @return true：存在且启用；false：不存在或已停用
     */
    public boolean deptExists(Long deptId) {
        return deptId != null && deptMapper.selectCount(new LambdaQueryWrapper<SysDept>()
                .eq(SysDept::getId, deptId)
                .eq(SysDept::getStatus, 1)) > 0;
    }

    /**
     * 批量根据部门ID集合获取【ID‑部门名称】映射Map
     * <p>做了入参判空，避免MyBatis in()传入空集合产生SQL语法异常</p>
     * @param deptIds 部门ID列表
     * @return Map<部门ID,部门名称>，入参为空返回空Map
     */
    public Map<Long, String> mapDeptNameByIds(List<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Map.of();
        }
        List<SysDept> depts = deptMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .in(SysDept::getId, deptIds));
        Map<Long, String> map = new LinkedHashMap<>();
        for (SysDept dept : depts) {
            map.put(dept.getId(), dept.getDeptName());
        }
        return map;
    }

    /**
     * 新增部门
     * <ul>
     *     <li>父部门非根时，校验父部门必须真实存在</li>
     *     <li>同一租户内部门名称唯一，数据库唯一索引兜底，业务层前置校验抛出友好提示</li>
     * </ul>
     * @param request 新增部门请求参数
     * @param tenantId 当前操作租户ID
     * @return 保存后的部门实体
     */
    @Transactional
    public SysDept create(DeptCreateRequest request, Long tenantId) {
        // parentId为空则默认设置为0，代表根部门
        Long parentId = request.parentId() == null ? 0L : request.parentId();
        // 如果不是根节点，校验父部门必须存在
        if (parentId != 0L) {
            getRequired(parentId);
        }
        // 校验租户内部门名称不能重复
        checkNameUnique(request.deptName().trim(), null);
        // 实体转换封装在实体类（P3.8 R7-5），业务层不再手写 set 组装
        SysDept dept = SysDept.from(request, tenantId, parentId);
        deptMapper.insert(dept);
        return dept;
    }

    /**
     * 更新部门
     * <ul>
     *     <li>修改部门名称：校验租户内名称唯一，排除自身ID</li>
     *     <li>修改父级：执行防环校验，禁止将部门挂到自身或自己的子孙节点下，避免树形闭环</li>
     *     <li>支持修改状态：停用部门不会删除数据，仅业务存在性校验失效，保留历史业务引用</li>
     * </ul>
     * @param id 待更新部门ID
     * @param request 更新请求参数
     * @return 更新后的部门实体
     */
    @Transactional
    public SysDept update(Long id, DeptUpdateRequest request) {
        SysDept dept = getRequired(id);

        // 处理部门名称修改逻辑：仅名称真变化时才做唯一性校验
        String newName = request.deptName() == null ? null : request.deptName().trim();
        if (StringUtils.hasText(newName) && !newName.equals(dept.getDeptName())) {
            checkNameUnique(newName, id);
        }

        // 处理父节点变更：仅父ID变化时才做防环校验（禁止挂到自身或子孙节点下）
        if (request.parentId() != null && !Objects.equals(request.parentId(), dept.getParentId())) {
            ensureNoCycle(id, request.parentId());
        }

        // 字段合并收敛在实体（P3.8 R7-5：apply），校验留在 Service（需要查库，不属于实体职责）
        dept.apply(request);
        deptMapper.updateById(dept);
        return dept;
    }

    /**
     * 删除部门
     * <p>约束：存在子部门 / 存在用户绑定该部门，则禁止删除，防止产生悬空引用与孤儿数据</p>
     * <p>底层依赖MyBatis‑Plus @TableLogic实现逻辑删除；停用不等于删除，停用不会走此删除逻辑</p>
     * @param id 部门ID
     * @throws BizException 存在子部门或绑定用户抛出异常
     */
    @Transactional
    public void delete(Long id) {
        getRequired(id);
        // 判断是否存在子部门
        boolean hasChildren = deptMapper.selectCount(new LambdaQueryWrapper<SysDept>()
                .eq(SysDept::getParentId, id)) > 0;
        if (hasChildren) {
            throw new BizException("存在子部门，无法删除");
        }
        // 判断是否存在绑定该部门的用户
        if (userService.countByDept(id) > 0) {
            throw new BizException("存在绑定该部门的用户，无法删除");
        }
        deptMapper.deleteById(id);
    }

    /**
     * 校验租户内部门名称唯一性
     * @param deptName 待校验部门名称
     * @param excludeId 需要排除的部门ID，更新场景传入自身ID，新增场景传null
     */
    private void checkNameUnique(String deptName, Long excludeId) {
        LambdaQueryWrapper<SysDept> qw = new LambdaQueryWrapper<SysDept>()
                .eq(SysDept::getDeptName, deptName);
        // 更新场景排除自己本身
        if (excludeId != null) {
            qw.ne(SysDept::getId, excludeId);
        }
        if (deptMapper.selectCount(qw) > 0) {
            throw new BizException("部门名称已存在: " + deptName);
        }
    }

    /**
     * 树形防环校验：修改父节点时，向上遍历父链，禁止把节点挂载给自己或自己的子孙
     * <p>parentId=0根节点直接放行；seen集合做循环检测兜底，防止脏数据造成死循环</p>
     * @param deptId 当前待移动部门ID
     * @param newParent 目标父部门ID
     * @throws BizException 父部门不存在 / 检测到树形闭环抛出异常
     */
    private void ensureNoCycle(Long deptId, Long newParent) {
        if (newParent == null || newParent == 0L) {
            return;
        }
        Set<Long> seen = new HashSet<>();
        Long cursor = newParent;
        // 向上循环追溯父节点链条
        while (cursor != null && cursor != 0L) {
            SysDept node = deptMapper.selectById(cursor);
            if (node == null) {
                throw new BizException("父部门不存在: " + cursor);
            }
            // 向上追溯找到了自己，说明形成闭环
            if (node.getId().equals(deptId)) {
                throw new BizException("不能把部门挂到自己/子孙部门下");
            }
            // 检测到重复节点，说明数据已经存在环路，直接终止循环，防止死循环
            if (!seen.add(cursor)) {
                break;
            }
            cursor = node.getParentId();
        }
    }
}
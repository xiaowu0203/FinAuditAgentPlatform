package com.finaudit.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.tenant.mapper.SysTenantMapper;
import com.finaudit.tenant.pojo.dto.TenantCreateRequest;
import com.finaudit.tenant.pojo.dto.TenantUpdateRequest;
import com.finaudit.tenant.pojo.entity.SysTenant;
import com.finaudit.tenant.pojo.vo.TenantVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 租户服务：租户实体（sys_tenant）的所有查询与更新均收敛于此。
 */
@Service
public class SysTenantService {

    private final SysTenantMapper tenantMapper;

    public SysTenantService(SysTenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    @Transactional
    public TenantVO create(TenantCreateRequest request) {
        // 校验是否已存在相同的租户
        Long exists = tenantMapper.selectCount(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getTenantCode, request.tenantCode()));
        if (exists > 0) {
            throw new BizException("租户编码已存在: " + request.tenantCode());
        }
        SysTenant tenant = SysTenant.from(request);
        // 新增
        tenantMapper.insert(tenant);
        return TenantVO.from(tenant);
    }

    @Transactional
    public TenantVO update(Long id, TenantUpdateRequest request) {
        // 校验租户是否存在
        SysTenant tenant = getRequired(id);
        // 更新
        tenant.apply(request);
        tenantMapper.updateById(tenant);
        return TenantVO.from(tenant);
    }

    @Transactional
    public void delete(Long id) {
        // 校验租户是否存在
        getRequired(id);
        // 逻辑删除
        tenantMapper.deleteById(id);
    }

    public TenantVO get(Long id) {
        return TenantVO.from(getRequired(id));
    }

    public Page<TenantVO> page(int pageNum, int pageSize, String keyword) {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(SysTenant::getTenantCode, keyword)
                    .or().like(SysTenant::getTenantName, keyword));
        }
        wrapper.orderByDesc(SysTenant::getId);
        Page<SysTenant> page = tenantMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<TenantVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(TenantVO::from).toList());
        return voPage;
    }

    public SysTenant getRequired(Long id) {
        SysTenant tenant = tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException("租户不存在: " + id);
        }
        return tenant;
    }

    /** 按编码查租户（登录用；sys_tenant 为全局表，多租户拦截器已 ignore）。 */
    public SysTenant getByCode(String tenantCode) {
        return tenantMapper.selectOne(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getTenantCode, tenantCode)
                .last("LIMIT 1"));
    }
}

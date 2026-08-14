package com.finaudit.toolservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.toolservice.enums.ToolCode;
import com.finaudit.toolservice.enums.ToolEnabledStatus;
import com.finaudit.toolservice.pojo.dto.ToolRegistryRegisterRequest;
import com.finaudit.toolservice.pojo.entity.ToolRegistry;
import com.finaudit.toolservice.executor.ToolExecutor;
import com.finaudit.toolservice.mapper.ToolRegistryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表服务：注册 / 列表 / 执行器分发。
 */
@Service
public class ToolRegistryService {

    private final ToolRegistryMapper registryMapper;
    private final Map<ToolCode, ToolExecutor> executorMap;

    public ToolRegistryService(ToolRegistryMapper registryMapper, List<ToolExecutor> executors) {
        this.registryMapper = registryMapper;
        // List转Map：方便按工具编码查找
        this.executorMap = executors.stream()
                .collect(Collectors.toMap(ToolExecutor::toolCode, Function.identity()));
    }

    public List<ToolRegistry> listEnabled(Long tenantId) {
        return registryMapper.selectList(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getTenantId, tenantId)
                .eq(ToolRegistry::getEnabled, ToolEnabledStatus.ENABLED)
                .orderByAsc(ToolRegistry::getId));
    }

    @Transactional
    public ToolRegistry register(ToolRegistryRegisterRequest request, Long tenantId) {
        // 强校验：工具编码必须是代码里已实现的（ToolCode 为唯一真相），保证 DB 不会存进代码不认识的编码
        ToolCode.of(request.toolCode());

        // 根据租户ID、工具编码查看是否已存在
        ToolRegistry existing = findByCode(tenantId, request.toolCode());
        if (existing == null) {
            // 类型转换
            ToolRegistry reg = ToolRegistry.from(request, tenantId);
            registryMapper.insert(reg);
            return reg;
        }
        // 类型转换
        existing.apply(request);
        registryMapper.updateById(existing);
        return existing;
    }

    public Map<String, Object> execute(String toolCode, Long tenantId, Map<String, Object> inputParams) {
        // 先按枚举解析：未实现的编码直接业务报错，避免走到注册表兜底（ToolCode 为唯一真相）
        ToolCode code = ToolCode.of(toolCode);
        // 根据租户ID、工具编码查询工具信息
        ToolRegistry reg = findByCode(tenantId, toolCode);
        // 若不存在或状态为禁用，直接抛出异常
        if (reg == null || reg.getEnabled() != ToolEnabledStatus.ENABLED) {
            throw new BizException("工具未注册或已禁用: " + toolCode);
        }
        // 根据编码获取工具执行接口
        ToolExecutor executor = executorMap.get(code);
        if (executor == null) {
            throw new BizException("工具未实现执行器: " + toolCode);
        }
        // 工具接口执行相关工具（带入输入的参数）
        return executor.execute(inputParams);
    }

    private ToolRegistry findByCode(Long tenantId, String toolCode) {
        return registryMapper.selectOne(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getTenantId, tenantId)
                .eq(ToolRegistry::getToolCode, toolCode));
    }
}

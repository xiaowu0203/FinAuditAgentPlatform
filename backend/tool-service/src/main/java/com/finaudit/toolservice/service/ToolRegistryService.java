package com.finaudit.toolservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.toolservice.enums.ToolCode;
import com.finaudit.toolservice.enums.ToolEnabledStatus;
import com.finaudit.toolservice.pojo.dto.ToolRegistryRegisterRequest;
import com.finaudit.toolservice.pojo.entity.ToolRegistry;
import com.finaudit.toolservice.executor.ToolExecutor;
import com.finaudit.toolservice.mapper.ToolRegistryMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表服务：注册 / 列表 / 执行器分发 / 入参强校验 / 缓存开关。
 */
@Service
public class ToolRegistryService {

    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    private final ToolRegistryMapper registryMapper;
    private final Map<ToolCode, ToolExecutor> executorMap;
    private final ToolAccessGuard accessGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolRegistryService(ToolRegistryMapper registryMapper, List<ToolExecutor> executors,
                               ToolAccessGuard accessGuard) {
        this.registryMapper = registryMapper;
        this.accessGuard = accessGuard;
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
        // 入参 JSON Schema 强校验（P2b 工具做厚核心一环）：非法入参在进入执行器前拦截
        validateInput(reg, inputParams);
        // 根据编码获取工具执行接口
        ToolExecutor executor = executorMap.get(code);
        if (executor == null) {
            throw new BizException("工具未实现执行器: " + toolCode);
        }
        // P3c 安全风控：工具防越权统一校验（租户一致性 / 部门归属 / 单据归属），覆盖 HTTP 与 MQ 双链路
        accessGuard.check(tenantId, code, inputParams);
        // 工具接口执行相关工具（带入输入的参数 + 租户，供 Feign 委托跨服务取数）
        return executor.execute(tenantId, inputParams);
    }

    /**
     * 工具是否启用结果缓存（P2b：有状态工具 cacheable=0，跳过缓存读写，避免重新执行被旧结果截断）。
     */
    public boolean isCacheable(String toolCode, Long tenantId) {
        ToolRegistry reg = findByCode(tenantId, toolCode);
        return reg != null && reg.getCacheable() != null && reg.getCacheable() == 1;
    }

    /**
     * 入参 JSON Schema 校验：tool_registry.input_schema 存在时强校验 inputParams；
     * 无 Schema 放行（兼容旧注册工具）。非法入参抛 BizException（携带校验详情）。
     */
    private void validateInput(ToolRegistry reg, Map<String, Object> inputParams) {
        Map<String, Object> schemaMap = reg.getInputSchema();
        if (schemaMap == null || schemaMap.isEmpty()) {
            return;
        }
        try {
            JsonNode schemaNode = objectMapper.valueToTree(schemaMap);
            JsonSchema schema = SCHEMA_FACTORY.getSchema(schemaNode);
            JsonNode inputNode = objectMapper.valueToTree(inputParams == null ? Map.of() : inputParams);
            Set<ValidationMessage> errors = schema.validate(inputNode);
            if (!errors.isEmpty()) {
                String detail = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("; "));
                throw new BizException("工具入参校验失败[" + reg.getToolCode() + "]: " + detail);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("工具入参 Schema 校验异常[" + reg.getToolCode() + "]: " + e.getMessage());
        }
    }

    private ToolRegistry findByCode(Long tenantId, String toolCode) {
        return registryMapper.selectOne(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getTenantId, tenantId)
                .eq(ToolRegistry::getToolCode, toolCode));
    }
}

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
        // P3.8 R6-4：注册即校验 Schema 合法性与强度——契约有缺陷必须在注册时暴露，
        // 而不是等某次真实调用被拦（那时很难判断是调用方的错还是契约写错了）
        validateSchemaDefinition(request.toolCode(), request.inputSchema(), true);
        validateSchemaDefinition(request.toolCode(), request.outputSchema(), false);

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

    /**
     * 校验 Schema 定义的合法性与强度（P3.8 R6-4）。
     *
     * <p><b>合法性</b>：必须能被 JSON Schema 解析器接受（挡住手写 JSON 里的语法/关键字错误）。</p>
     * <p><b>强度</b>：必须声明 {@code type: object}；入参 Schema 还必须有非空 {@code properties}
     * ——没有属性的 object Schema 等于没校验，等于工具契约形同虚设
     * （这正是 P2b 之前"工具做厚"想解决的问题，只是当时没有在注册口把关）。</p>
     *
     * @param toolCode 工具编码（错误信息定位用）
     * @param schema   待校验的 Schema；{@code null} 时仅出参允许（入参由 DTO 的 @NotNull 保证）
     * @param required 是否为入参 Schema（true = 强制强度校验）
     */
    private void validateSchemaDefinition(String toolCode, Map<String, Object> schema, boolean required) {
        if (schema == null || schema.isEmpty()) {
            if (required) {
                throw new BizException("工具入参 Schema 不能为空: " + toolCode);
            }
            return;
        }
        try {
            JsonNode node = objectMapper.valueToTree(schema);
            // getSchema 会在 JSON Schema 非法时抛异常（关键字拼错、类型写错等）
            SCHEMA_FACTORY.getSchema(node);
            JsonNode type = node.get("type");
            if (type == null || !"object".equals(type.asText())) {
                throw new BizException("工具 Schema 必须声明 type=object: " + toolCode
                        + "（工具入参/出参都是具名键值结构）");
            }
            JsonNode properties = node.get("properties");
            if (required && (properties == null || !properties.isObject() || properties.isEmpty())) {
                throw new BizException("工具入参 Schema 的 properties 不能为空: " + toolCode
                        + "（无属性的 object Schema 等于没有校验）");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("工具 Schema 非法[" + toolCode + "]: " + e.getMessage());
        }
    }

    /**
     * 执行工具（内部/MQ 链路便捷入口）：凭证只带声明租户与任务 ID。
     * <p>MQ 链路的权威租户由任务归属反查代替（见 {@link ToolAccessGuard}）。</p>
     */
    public Map<String, Object> execute(String toolCode, Long tenantId, Long taskId, Map<String, Object> inputParams) {
        return execute(toolCode, ToolTenantCredential.mq(tenantId, taskId), inputParams);
    }

    /**
     * 执行工具（统一入口）：租户凭证显式传入，权威租户与声明租户分离（P3.8 R6-2）。
     */
    public Map<String, Object> execute(String toolCode, ToolTenantCredential credential,
                                       Map<String, Object> inputParams) {
        Long tenantId = credential.declaredTenantId();
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
        // P3c 安全风控：工具防越权统一校验（权威租户一致性 / 任务归属 / 部门归属 / 单据归属），覆盖 HTTP 与 MQ 双链路
        accessGuard.check(credential, code, inputParams);
        // 工具接口执行相关工具（带入输入的参数 + 租户，供 Feign 委托跨服务取数）
        Map<String, Object> output = executor.execute(tenantId, inputParams);
        // P3.8 R6-4：出参 Schema 校验（有 output_schema 才校验；缺失即跳过，兼容存量工具）
        validateOutput(reg, output);
        return output;
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

    /**
     * 出参 JSON Schema 校验（P3.8 R6-4）：{@code tool_registry.output_schema} 存在时校验执行器返回值。
     *
     * <p><b>与入参校验的区别</b>：入参非法说明调用方有错，必须拦；出参不符说明<b>工具自身实现或上游数据</b>
     * 出了问题，同样必须让调用方看到——否则错误的形状会一路流到 LLM 上下文与结构化问题项里，
     * 变成「工具静默给错数据」（R4-7 手工装配丢字段就是这类故障）。</p>
     *
     * <p>无 output_schema 直接放行（兼容存量工具）；校验异常本身也不放行（视为契约破裂）。</p>
     */
    private void validateOutput(ToolRegistry reg, Map<String, Object> output) {
        Map<String, Object> schemaMap = reg.getOutputSchema();
        if (schemaMap == null || schemaMap.isEmpty()) {
            return;
        }
        try {
            JsonNode schemaNode = objectMapper.valueToTree(schemaMap);
            JsonSchema schema = SCHEMA_FACTORY.getSchema(schemaNode);
            JsonNode outputNode = objectMapper.valueToTree(output == null ? Map.of() : output);
            Set<ValidationMessage> errors = schema.validate(outputNode);
            if (!errors.isEmpty()) {
                String detail = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("; "));
                throw new BizException("工具出参校验失败[" + reg.getToolCode() + "]: " + detail);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("工具出参 Schema 校验异常[" + reg.getToolCode() + "]: " + e.getMessage());
        }
    }

    private ToolRegistry findByCode(Long tenantId, String toolCode) {
        return registryMapper.selectOne(new LambdaQueryWrapper<ToolRegistry>()
                .eq(ToolRegistry::getTenantId, tenantId)
                .eq(ToolRegistry::getToolCode, toolCode));
    }
}

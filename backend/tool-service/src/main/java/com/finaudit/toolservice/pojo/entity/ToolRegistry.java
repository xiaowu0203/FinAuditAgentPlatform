package com.finaudit.toolservice.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.toolservice.enums.ToolEnabledStatus;
import com.finaudit.toolservice.pojo.dto.ToolRegistryRegisterRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工具注册表（tool_registry）。
 */
@Getter
@Setter
@TableName(value = "tool_registry", autoResultMap = true)
public class ToolRegistry {

    /** 注册缺省版本 */
    public static final String DEFAULT_VERSION = "1.0";

    @TableId(type = IdType.AUTO)
    @Schema(description = "工具ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "工具编码")
    private String toolCode;

    @Schema(description = "工具名称")
    private String toolName;

    @Schema(description = "工具描述")
    private String description;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "入参 JSON Schema")
    private Map<String, Object> inputSchema;

    /**
     * 出参 JSON Schema（P3.8 R6-4，可空）。
     * <p>存在时 {@code ToolRegistryService} 会在执行器返回后校验出参形状，让「工具给错数据」
     * 在工具边界就暴露，而不是一路流到 LLM 上下文里（R4-7 手工装配丢字段即此类故障）。
     * 为空表示不校验，兼容存量工具。</p>
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "出参 JSON Schema（可空；存在则执行后校验出参形状）")
    private Map<String, Object> outputSchema;

    @Schema(description = "是否启用（0 禁用 / 1 启用）")
    private ToolEnabledStatus enabled;

    @Schema(description = "工具版本")
    private String version;

    @Schema(description = "业务场景（FINANCE/GENERIC，P2b TaskPlanner 按此收敛工具目录；缺省 FINANCE）")
    private String scenario;

    @Schema(description = "结果缓存开关（1 缓存 / 0 不缓存；有状态工具置 0，缺省 1）")
    private Integer cacheable;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    /** 更新时间（P3.8 R9-2 修复 R2-10 遗留）：标 fill=INSERT_UPDATE，使 updateById/实体更新能刷新该列，
     * 否则实体里读出的旧值会被写回 SET、抑制 MySQL 的 ON UPDATE CURRENT_TIMESTAMP。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 由注册请求构造新工具（缺省：enabled 启用、version 1.0）。
     */
    public static ToolRegistry from(ToolRegistryRegisterRequest request, Long tenantId) {
        ToolRegistry reg = new ToolRegistry();
        reg.setTenantId(tenantId);
        reg.setToolCode(request.toolCode());
        reg.setToolName(request.toolName());
        reg.setDescription(request.description());
        reg.setInputSchema(request.inputSchema());
        reg.setOutputSchema(request.outputSchema());
        reg.setEnabled(ToolEnabledStatus.of(request.enabled()));
        reg.setVersion(request.version() == null ? DEFAULT_VERSION : request.version());
        // scenario/cacheable 空值不覆盖，走 DB 默认列值（FINANCE / 1）
        reg.setScenario(request.scenario());
        reg.setCacheable(request.cacheable());
        return reg;
    }

    /**
     * 用注册请求合并更新既有工具；enabled / version 仅在请求显式给定时覆盖。
     */
    public void apply(ToolRegistryRegisterRequest request) {
        this.toolName = request.toolName();
        this.description = request.description();
        this.inputSchema = request.inputSchema();
        // 出参 Schema：仅在请求显式给定时覆盖（与 enabled/version 同口径），避免误清空既有契约
        if (request.outputSchema() != null) {
            this.outputSchema = request.outputSchema();
        }
        if (request.enabled() != null) {
            this.enabled = ToolEnabledStatus.of(request.enabled());
        }
        if (request.version() != null) {
            this.version = request.version();
        }
        if (request.scenario() != null) {
            this.scenario = request.scenario();
        }
        if (request.cacheable() != null) {
            this.cacheable = request.cacheable();
        }
    }
}

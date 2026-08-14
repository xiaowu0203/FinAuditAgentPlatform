package com.finaudit.toolservice.pojo.entity;

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

    @Schema(description = "是否启用（0 禁用 / 1 启用）")
    private ToolEnabledStatus enabled;

    @Schema(description = "工具版本")
    private String version;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
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
        reg.setEnabled(ToolEnabledStatus.of(request.enabled()));
        reg.setVersion(request.version() == null ? DEFAULT_VERSION : request.version());
        return reg;
    }

    /**
     * 用注册请求合并更新既有工具；enabled / version 仅在请求显式给定时覆盖。
     */
    public void apply(ToolRegistryRegisterRequest request) {
        this.toolName = request.toolName();
        this.description = request.description();
        this.inputSchema = request.inputSchema();
        if (request.enabled() != null) {
            this.enabled = ToolEnabledStatus.of(request.enabled());
        }
        if (request.version() != null) {
            this.version = request.version();
        }
    }
}

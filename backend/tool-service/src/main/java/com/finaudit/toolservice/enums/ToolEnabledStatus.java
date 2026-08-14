package com.finaudit.toolservice.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 工具启用状态（0 禁用 / 1 启用）。
 */
public enum ToolEnabledStatus {

    /** 禁用 */
    DISABLED(0),
    /** 启用 */
    ENABLED(1);

    @EnumValue
    @JsonValue
    private final int value;

    ToolEnabledStatus(int value) {
        this.value = value;
    }

    /**
     * 根据value值查找ToolEnabledStatus枚举值（不存在则抛异常）
     * @param value 工具启用状态值
     * @return ToolEnabledStatus枚举值
     */
    public static ToolEnabledStatus of(Integer value) {
        if (value == null) {
            return ENABLED;
        }
        return Arrays.stream(values())
                .filter(e -> e.value == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("非法启用状态: " + value));
    }
}

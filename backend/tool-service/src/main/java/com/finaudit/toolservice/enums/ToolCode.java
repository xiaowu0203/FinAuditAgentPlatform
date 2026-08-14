package com.finaudit.toolservice.enums;

import com.finaudit.starter.web.exception.BizException;

import java.util.Arrays;

/**
 * 内置工具编码（唯一真相，代码侧权威）。
 * <p>执行器 {@code toolCode()}、注册/执行校验均以本枚举为准；
 * tool_registry.tool_code 只允许存枚举中的编码，注册时强校验，杜绝代码与 DB 两处失配。</p>
 */
public enum ToolCode {

    /** 金额核验工具 */
    AMOUNT_VERIFY("amount_verify");

    private final String code;

    ToolCode(String code) {
        this.code = code;
    }

    /** 编码字符串（tool_registry.tool_code 存储值） */
    public String code() {
        return code;
    }

    /** 由字符串解析编码；未实现的编码抛业务异常 */
    public static ToolCode of(String code) {
        if (code == null) {
            throw new BizException("工具编码不能为空");
        }
        return Arrays.stream(values())
                .filter(c -> c.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new BizException("未实现的工具编码: " + code));
    }
}

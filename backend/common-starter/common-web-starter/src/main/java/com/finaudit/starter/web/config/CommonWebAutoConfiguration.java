package com.finaudit.starter.web.config;

import com.finaudit.starter.web.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Web 通用能力自动装配：统一返回 / 全局异常 / 参数校验。
 */
@AutoConfiguration
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {
}

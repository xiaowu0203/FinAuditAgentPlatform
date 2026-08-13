package com.finaudit.starter.model.config;

import com.finaudit.starter.model.properties.ModelProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 模型通用配置自动装配。
 */
@AutoConfiguration
@EnableConfigurationProperties(ModelProperties.class)
public class CommonModelAutoConfiguration {
}

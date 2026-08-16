package com.finaudit.starter.ocr.config;

import com.finaudit.starter.ocr.OcrService;
import com.finaudit.starter.ocr.baidu.BaiduOcrService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * OCR 自动配置。
 * <p>引入本 starter 即代表工程需要使用 OCR，启动时对百度凭据做自检：
 * 未配置 {@code finaudit.ocr.baidu.api-key / secret-key}（或对应环境变量）直接失败并给出明确提示，
 * 不再静默跳过后再由业务工程报出晦涩的 "No qualifying bean" 错误。</p>
 * <p>业务工程如需接管实现（自定义 {@link OcrService} Bean），本配置经
 * {@link ConditionalOnMissingBean} 自动让位，凭据由其自管。</p>
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@EnableConfigurationProperties(OcrProperties.class)
public class CommonOcrAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OcrService.class)
    public OcrService ocrService(OcrProperties props) {
        OcrProperties.Baidu baidu = props.getBaidu();
        if (baidu == null || isBlank(baidu.getApiKey()) || isBlank(baidu.getSecretKey())) {
            throw new IllegalStateException(
                    "百度 OCR 凭据未配置：请设置 finaudit.ocr.baidu.api-key / secret-key，"
                            + "或经环境变量 FINAUDIT_OCR_BAIDU_API_KEY / FINAUDIT_OCR_BAIDU_SECRET_KEY 注入");
        }
        return new BaiduOcrService(baidu);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

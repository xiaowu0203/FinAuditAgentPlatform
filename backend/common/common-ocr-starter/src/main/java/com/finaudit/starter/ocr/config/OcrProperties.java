package com.finaudit.starter.ocr.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR 配置（前缀 {@code finaudit.ocr}）。
 * <p>凭据（apiKey/secretKey）禁止入库（CLAUDE.md §6），一律经环境变量注入：
 * {@code FINAUDIT_OCR_BAIDU_API_KEY} / {@code FINAUDIT_OCR_BAIDU_SECRET_KEY}。</p>
 */
@ConfigurationProperties(prefix = "finaudit.ocr")
@Getter
@Setter
public class OcrProperties {

    /** 百度智能云配置（当前唯一实现） */
    private Baidu baidu = new Baidu();

    @Getter
    @Setter
    public static class Baidu {

        /** API Key（百度智能云控制台 → 应用接入） */
        private String apiKey;
        /** Secret Key */
        private String secretKey;
        /** 单次识别超时（毫秒），缺省 10s */
        private int timeoutMs = 10_000;

    }
}

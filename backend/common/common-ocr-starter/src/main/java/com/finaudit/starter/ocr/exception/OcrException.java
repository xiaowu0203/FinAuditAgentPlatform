package com.finaudit.starter.ocr.exception;

import lombok.Getter;

/**
 * OCR 识别异常（unchecked）。
 * <p>百度 API 非 200、业务 error_code 非 0、图片非法、JSON 解析失败等均抛此异常；
 * 由 tool-service 的 {@code OcrExtractTool} 捕获并按「失败重试 ≤3 → 人工录入」兜底。</p>
 */
@Getter
public class OcrException extends RuntimeException {

    /** 百度返回的错误码（无则 -1） */
    private final long errorCode;

    public OcrException(String message) {
        this(-1L, message);
    }

    public OcrException(long errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OcrException(String message, Throwable cause) {
        this(-1L, message, cause);
    }

    public OcrException(long errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

}

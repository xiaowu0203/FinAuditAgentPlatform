package com.finaudit.starter.web.exception;

/**
 * 业务异常。由 {@link GlobalExceptionHandler} 统一捕获并转为 {@code R} 返回。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public int getCode() {
        return code;
    }
}

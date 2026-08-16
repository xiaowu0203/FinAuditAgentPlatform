package com.finaudit.starter.web.result;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 统一接口返回封装。
 * <p>code=0 表示成功，非 0 为业务或系统错误码。</p>
 */
@Getter
@Setter
public class R<T> {

    private int code;
    private String message;
    private T data;
    private LocalDateTime timestamp;

    public R() {
    }

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now();
    }

    public static <T> R<T> success() {
        return new R<>(0, "ok", null);
    }

    public static <T> R<T> success(T data) {
        return new R<>(0, "ok", data);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public static <T> R<T> fail(String message) {
        return new R<>(-1, message, null);
    }

}

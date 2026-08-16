package com.finaudit.file.exception;

import com.finaudit.starter.web.result.R;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * file-service 局部异常处理：与 common-code 的 GlobalExceptionHandler 共存，
 * 更具体的类型优先命中（否则超大文件落入兜底 500）。
 * <p>异常处理器属于横切关注点，不归 controller 包（controller 只做参数装配与返回），
 * 与 common-code 的 {@code com.finaudit.starter.web.exception} 约定对齐。</p>
 */
@RestControllerAdvice
public class FileExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return R.fail(400, "文件大小超出限制");
    }
}

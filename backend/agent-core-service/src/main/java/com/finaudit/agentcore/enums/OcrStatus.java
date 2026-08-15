package com.finaudit.agentcore.enums;

import com.finaudit.starter.web.exception.BizException;

/**
 * 附件 OCR 状态（expense_attachment.ocr_status，P2b 起使用）。
 */
public enum OcrStatus {

    /** 待识别 */
    PENDING,
    /** 识别成功 */
    SUCCESS,
    /** 识别失败（推送人工录入） */
    FAILED;

    /** 解析，非法值抛业务异常 */
    public static OcrStatus of(String v) {
        for (OcrStatus s : values()) {
            if (s.name().equalsIgnoreCase(v)) {
                return s;
            }
        }
        throw new BizException("OCR 状态不合法: " + v);
    }
}

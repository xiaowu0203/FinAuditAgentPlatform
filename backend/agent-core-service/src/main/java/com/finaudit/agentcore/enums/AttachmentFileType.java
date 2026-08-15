package com.finaudit.agentcore.enums;

import com.finaudit.starter.web.exception.BizException;

/**
 * 附件类型（expense_attachment.file_type）。
 * <p>P2a 重构后上传不再按业务类型分类（file-service 只收二进制），绑定默认 OTHER；
 * 分类归 P2b OCR 工具在审核管线产生。枚举保留待用。</p>
 */
public enum AttachmentFileType {

    /** 发票 */
    INVOICE,
    /** 行程单 */
    ITINERARY,
    /** 合同 */
    CONTRACT,
    /** 其他 */
    OTHER;

    /** 解析，非法值抛业务异常 */
    public static AttachmentFileType of(String v) {
        for (AttachmentFileType t : values()) {
            if (t.name().equalsIgnoreCase(v)) {
                return t;
            }
        }
        throw new BizException("附件类型不合法: " + v);
    }
}

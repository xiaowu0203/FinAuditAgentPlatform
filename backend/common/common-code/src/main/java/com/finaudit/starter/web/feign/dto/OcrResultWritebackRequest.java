package com.finaudit.starter.web.feign.dto;

import java.util.Map;

/**
 * OCR 结果回写请求（tool-service → agent-core，跨服务契约）。
 * <p>按 {@code file_record_id} 定位 {@code expense_attachment} 回填三字段；
 * 路径参数 fileRecordId 由 Feign 方法携带，本 DTO 只承载业务字段。</p>
 *
 * @param ocrStatus OCR 状态（PENDING/SUCCESS/FAILED）
 * @param fileType  票据分类映射后的附件类型（INVOICE/ITINERARY/CONTRACT/OTHER）
 * @param ocrResult OCR 抽取结果（归一化字段：amount/date/merchant/taxNo/receiptType 等）
 */
public record OcrResultWritebackRequest(String ocrStatus, String fileType, Map<String, Object> ocrResult) {
}

package com.finaudit.starter.ocr.model;

import lombok.Data;

import java.util.List;

/**
 * 智能财务票据识别（iocr finance）整体结果：一张图片里的多张票据。
 */
@Data
public class FinanceReceiptOcr {

    /** 识别出的票据列表（可能为空：未识别到票据，由调用方决定按失败兜底） */
    private List<ReceiptItem> items;

    /** 是否识别到至少一张票据 */
    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }
}

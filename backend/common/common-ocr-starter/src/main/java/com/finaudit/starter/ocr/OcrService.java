package com.finaudit.starter.ocr;

import com.finaudit.starter.ocr.model.FinanceReceiptOcr;
import com.finaudit.starter.ocr.model.VatInvoiceOcr;

/**
 * OCR 识别统一抽象（多厂商扩展位）。
 * <p>当前实现 {@link com.finaudit.starter.ocr.baidu.BaiduOcrService}（P2b D6 已定：百度）。
 * 后续接入阿里/本地轻量时新增实现即可，业务侧面向本接口编码。</p>
 */
public interface OcrService {

    /**
     * 智能财务票据识别（主线）：一次调用返回图片内每张票据的分类 + 结构化字段。
     *
     * @param image 票据图片字节（jpg/jpeg/png/bmp，≤4M，最短边 ≥15px，最长边 ≤4096px）
     * @return 票据列表；图片内无票据时为空列表（不抛异常）
     */
    FinanceReceiptOcr recognizeFinance(byte[] image);

    /**
     * 增值税发票识别（细节兜底）：当财务票据识别把图片判为 {@code vat_invoice} 时，
     * 二次调用补齐税号/金额细节。
     *
     * @param image 增值税发票图片字节（要求同 {@link #recognizeFinance(byte[])}）
     * @return 结构化字段；图片非增值税发票时字段可能为空
     */
    VatInvoiceOcr recognizeVat(byte[] image);
}

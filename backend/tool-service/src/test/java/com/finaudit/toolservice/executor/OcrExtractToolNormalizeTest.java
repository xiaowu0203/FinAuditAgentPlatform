package com.finaudit.toolservice.executor;

import com.finaudit.starter.ocr.model.VatInvoiceOcr;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OcrExtractTool 字段归一化单测（P3.8 R2）。
 * <p>核心回归点：发票代码/号码此前在 {@link VatInvoiceOcr} 里已解析却被 normalize 丢弃，
 * 导致下游查重只能靠金额+申请人+±30天的启发式。本测试锁定该字段不再丢失。</p>
 */
class OcrExtractToolNormalizeTest {

    /** 免构造依赖：normalize 为纯函数，不触碰 OCR/Feign 客户端 */
    private final OcrExtractTool tool = new OcrExtractTool(null, null, null);

    private static VatInvoiceOcr vatFixture() {
        VatInvoiceOcr vat = new VatInvoiceOcr();
        vat.setAmountInFiguers(new BigDecimal("1130.00"));
        vat.setInvoiceDate(LocalDate.of(2026, 8, 1));
        vat.setSellerName("某某科技有限公司");
        vat.setSellerRegisterNum("91310000MA1FL1234X");
        vat.setInvoiceCode("031002100311");
        vat.setInvoiceNum("12345678");
        vat.setInvoiceType("电子发票");
        return vat;
    }

    // ---------------- 发票代码/号码（R2 核心） ----------------

    @Test
    void vatInvoiceIsNormalizedWithInvoiceCodeAndNum() {
        Map<String, Object> out = tool.normalize("vat_invoice", Map.of(), vatFixture());

        // 原实现只输出 receiptType/amount/date/merchant/taxNo，此三项断言即回归护栏
        assertEquals("031002100311", out.get("invoiceCode"), "发票代码不得被丢弃");
        assertEquals("12345678", out.get("invoiceNum"), "发票号码不得被丢弃");
        assertEquals("vat_invoice", out.get("receiptType"));
        assertEquals(new BigDecimal("1130.00"), out.get("amount"));
        assertEquals("2026-08-01", out.get("date"));
        assertEquals("某某科技有限公司", out.get("merchant"));
        assertEquals("91310000MA1FL1234X", out.get("taxNo"));
    }

    @Test
    void electronicInvoiceWithoutCodeKeepsNumAndNullCode() {
        // 2018 年起电子发票代码并入号码，百度可能不返回 InvoiceCode
        VatInvoiceOcr vat = vatFixture();
        vat.setInvoiceCode(null);

        Map<String, Object> out = tool.normalize("vat_invoice", Map.of(), vat);

        assertNull(out.get("invoiceCode"), "缺失发票代码应为 null（不可为空串，否则污染唯一键）");
        assertEquals("12345678", out.get("invoiceNum"));
    }

    @Test
    void nonVatTemplateFallsBackToRawFields() {
        // 非增值税模板：无 vat 二次识别结果，从原始字段读中文 key
        Map<String, String> fields = new HashMap<>();
        fields.put("发票代码", "144031909110");
        fields.put("发票号码", "87654321");
        fields.put("价税合计", "560.00");

        Map<String, Object> out = tool.normalize("roll_normal_invoice", fields, null);

        assertEquals("144031909110", out.get("invoiceCode"));
        assertEquals("87654321", out.get("invoiceNum"));
        assertEquals(new BigDecimal("560.00"), out.get("amount"));
    }

    @Test
    void itineraryTicketHasNoInvoiceNumber() {
        // 火车票/打车票没有发票代码号码，两个字段都应为 null（不得凭空造值）
        Map<String, String> fields = new HashMap<>();
        fields.put("票价", "553.50");
        fields.put("乘车日期", "2026年08月03日");

        Map<String, Object> out = tool.normalize("train_ticket", fields, null);

        assertNull(out.get("invoiceCode"));
        assertNull(out.get("invoiceNum"));
        assertEquals(new BigDecimal("553.50"), out.get("amount"));
    }

    @Test
    void blankAndPaddedValuesAreNormalized() {
        // 唯一键要求确定性取值：空白归一为 null，首尾空白被去除
        Map<String, String> fields = new HashMap<>();
        fields.put("InvoiceNum", "   ");
        Map<String, Object> blank = tool.normalize("quota_invoice", fields, null);
        assertNull(blank.get("invoiceNum"), "全空白应归一为 null");

        Map<String, String> padded = new HashMap<>();
        padded.put("InvoiceNum", "  00998877  ");
        Map<String, Object> trimmed = tool.normalize("quota_invoice", padded, null);
        assertEquals("00998877", trimmed.get("invoiceNum"), "首尾空白必须去除，否则同票被判两张");
    }

    @Test
    void vatResultWinsOverRawFields() {
        // vat 二次识别的专用字段优先于通用原始字段
        Map<String, String> fields = new HashMap<>();
        fields.put("InvoiceNum", "00000000");

        Map<String, Object> out = tool.normalize("vat_invoice", fields, vatFixture());

        assertEquals("12345678", out.get("invoiceNum"), "应优先采用 vat 专用识别结果");
    }

    // ---------------- 结构化日期（invoice_record.inv_date 落库用） ----------------

    @Test
    void ocrDateParsedFromChineseFormat() {
        // 财会版 classifierId=10001 实际返回中文日期，DATE 列无法直接入库，必须显式解析
        Map<String, String> fields = new HashMap<>();
        fields.put("开票日期", "2026年08月01日");

        Map<String, Object> out = tool.normalize("roll_normal_invoice", fields, null);

        assertEquals("2026-08-01", out.get("ocrDate"), "中文日期应解析为 yyyy-MM-dd");
        assertEquals("2026年08月01日", out.get("date"), "展示用原样串应保留不变");
    }

    @Test
    void ocrDateFromVatLocalDate() {
        Map<String, Object> out = tool.normalize("vat_invoice", Map.of(), vatFixture());

        assertEquals("2026-08-01", out.get("ocrDate"));
    }

    @Test
    void ocrDateNullWhenUnparseable() {
        // 解析失败应静默返回 null，不得让整条 OCR 链路失败
        Map<String, String> fields = new HashMap<>();
        fields.put("乘车日期", "8月3日");

        Map<String, Object> out = tool.normalize("train_ticket", fields, null);

        assertNull(out.get("ocrDate"), "无法解析的日期应为 null");
        assertEquals("8月3日", out.get("date"), "原样串仍应保留供人工复核");
    }
}

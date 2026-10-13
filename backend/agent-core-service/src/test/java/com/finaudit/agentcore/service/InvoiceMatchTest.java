package com.finaudit.agentcore.service;

import com.finaudit.agentcore.mapper.InvoiceRecordMapper;
import com.finaudit.agentcore.pojo.entity.InvoiceRecord;
import com.finaudit.starter.web.feign.dto.InvoiceMatchVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 票据-明细交叉核验 + 离线验真单测（P3.8 R3-3 / R3-5）。
 *
 * <p>核心回归点：{@code amount_verify} 只校验「明细合计 == 申报总额」（同一份数据内部自洽），
 * {@code rule_check} 只校验限额标准，两者都<b>没有把票面与明细对上</b>。
 * 本测试锁定「明细远大于票面」这类虚报必须被识别。</p>
 */
@ExtendWith(MockitoExtension.class)
class InvoiceMatchTest {

    @Mock
    private InvoiceRecordMapper invoiceRecordMapper;

    @InjectMocks
    private InvoiceRecordService service;

    private static InvoiceRecord invoice(String code, String num, String amount, LocalDate date) {
        InvoiceRecord r = InvoiceRecord.from(code, num, "91310000MA1FL1234X", 1L, 2L, 3L,
                amount == null ? null : new BigDecimal(amount), date);
        return r;
    }

    private static List<Map<String, Object>> items(Object... nameAmountPairs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < nameAmountPairs.length; i += 2) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", nameAmountPairs[i]);
            m.put("amount", nameAmountPairs[i + 1]);
            list.add(m);
        }
        return list;
    }

    private void givenInvoices(InvoiceRecord... records) {
        when(invoiceRecordMapper.selectList(any())).thenReturn(List.of(records));
    }

    // ---------------- 交叉核验：票面 vs 明细 ----------------

    @Test
    void itemAmountFarExceedingInvoiceIsFlagged() {
        // 需求原句验收：100 元票 + 800 元明细 → 不一致
        givenInvoices(invoice("031002100311", "12345678", "100.00", LocalDate.now().minusDays(5)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("800.00")),
                new BigDecimal("800.00"));

        assertFalse(vo.consistent(), "明细远大于票面必须判为不一致");
        // 先固化两侧原始值，失败时便于定位是「取数」还是「算差额」的问题
        assertEquals(0, new BigDecimal("100.00").compareTo(vo.invoiceTotal()),
                "票面合计应为 100.00，实际=" + vo.invoiceTotal());
        assertEquals(0, new BigDecimal("800.00").compareTo(vo.claimTotal()),
                "申报合计应为 800.00，实际=" + vo.claimTotal());
        assertEquals(0, new BigDecimal("700.00").compareTo(vo.gap()), "差额应为 700，实际=" + vo.gap());
        assertTrue(vo.flags().stream().anyMatch(f -> "AMOUNT_MISMATCH".equals(f.code())));
        // 单笔明细 800 > 票面 100，也应同时命中「单笔越界」
        assertTrue(vo.flags().stream().anyMatch(f -> "ITEM_EXCEEDS_INVOICE".equals(f.code())));
    }

    @Test
    void matchingAmountsAreConsistent() {
        givenInvoices(invoice("031002100311", "12345678", "1130.00", LocalDate.now().minusDays(5)));

        InvoiceMatchVO vo = service.matchInvoices(1L,
                items("培训费", new BigDecimal("800.00"), "资料费", new BigDecimal("330.00")),
                new BigDecimal("1130.00"));

        assertTrue(vo.consistent(), "票面合计 == 明细合计，应判为一致");
        assertEquals(0, new BigDecimal("1130.00").compareTo(vo.invoiceTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.gap()));
        assertTrue(vo.flags().isEmpty());
    }

    @Test
    void toleranceWithinOneCentIsConsistent() {
        // 拆分四舍五入难免：差额 0.01 以内视为一致
        givenInvoices(invoice("031002100311", "12345678", "100.00", LocalDate.now().minusDays(1)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("100.01")),
                new BigDecimal("100.01"));

        assertTrue(vo.consistent(), "0.01 容差内应视为一致");
    }

    @Test
    void claimLessThanInvoiceIsNotFlagged() {
        // 票面比明细多（可能只报了部分明细）不构成虚报风险，不标记
        givenInvoices(invoice("031002100311", "12345678", "500.00", LocalDate.now().minusDays(1)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("300.00")),
                new BigDecimal("300.00"));

        assertTrue(vo.consistent(), "申报小于票面不应判为不一致");
        assertFalse(vo.flags().stream().anyMatch(f -> "AMOUNT_MISMATCH".equals(f.code())));
    }

    @Test
    void missingInvoiceIsFlaggedAsUnableToCrossCheck() {
        givenInvoices();

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("100.00")),
                new BigDecimal("100.00"));

        assertFalse(vo.consistent(), "有明细却无发票时不得判为一致（避免把没数据当成没问题）");
        assertTrue(vo.flags().stream().anyMatch(f -> "NO_INVOICE".equals(f.code())));
    }

    @Test
    void claimedTotalFallsBackToItemSum() {
        givenInvoices(invoice("031002100311", "12345678", "300.00", LocalDate.now().minusDays(1)));

        // 不传 claimedTotal，由服务端按明细求和
        InvoiceMatchVO vo = service.matchInvoices(1L,
                items("A", new BigDecimal("100.00"), "B", new BigDecimal("200.00")), null);

        assertEquals(0, new BigDecimal("300.00").compareTo(vo.claimTotal()));
        assertTrue(vo.consistent());
    }

    // ---------------- 离线验真（R3-5） ----------------

    @Test
    void invoiceCodeWithWrongLengthIsFlagged() {
        // 7 位既不是 10/12（增普票）也不是 20（电子发票）
        givenInvoices(invoice("1234567", "12345678", "100.00", LocalDate.now().minusDays(1)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("100.00")),
                new BigDecimal("100.00"));

        assertTrue(vo.flags().stream().anyMatch(f -> "INVOICE_CODE_FORMAT".equals(f.code())));
        // 形态异常属验真类，不影响「票据与明细是否对得上」的一致性判定
        assertTrue(vo.consistent(), "代码位数异常不应改变交叉核验结论");
    }

    @Test
    void invoiceCodeWithNonDigitIsFlagged() {
        givenInvoices(invoice("03100210031X", "12345678", "100.00", LocalDate.now().minusDays(1)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("100.00")),
                new BigDecimal("100.00"));

        assertTrue(vo.flags().stream().anyMatch(f -> "INVOICE_CODE_FORMAT".equals(f.code())));
    }

    @Test
    void missingInvoiceCodeIsAllowed() {
        // 2018 年起电子发票代码并入号码，缺失是合法的，不得误报
        givenInvoices(invoice("", "12345678", "100.00", LocalDate.now().minusDays(1)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("100.00")),
                new BigDecimal("100.00"));

        assertFalse(vo.flags().stream().anyMatch(f -> "INVOICE_CODE_FORMAT".equals(f.code())),
                "电子发票无代码不应被判为格式异常");
        assertTrue(vo.consistent());
    }

    @Test
    void futureInvoiceDateIsFlagged() {
        givenInvoices(invoice("031002100311", "12345678", "100.00", LocalDate.now().plusDays(3)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("100.00")),
                new BigDecimal("100.00"));

        assertTrue(vo.flags().stream().anyMatch(f -> "INVOICE_DATE_FUTURE".equals(f.code())),
                "开票日期晚于当前日期应命中");
    }

    @Test
    void tooOldInvoiceDateIsFlagged() {
        givenInvoices(invoice("031002100311", "12345678", "100.00", LocalDate.now().minusYears(11)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("办公用品", new BigDecimal("100.00")),
                new BigDecimal("100.00"));

        assertTrue(vo.flags().stream().anyMatch(f -> "INVOICE_DATE_TOO_OLD".equals(f.code())));
    }

    @Test
    void validVatInvoiceProducesNoFlags() {
        givenInvoices(invoice("031002100311", "12345678", "1130.00", LocalDate.now().minusDays(3)));

        InvoiceMatchVO vo = service.matchInvoices(1L, items("培训费", new BigDecimal("1130.00")),
                new BigDecimal("1130.00"));

        assertTrue(vo.flags().isEmpty(), "合法增值税发票不应产生任何异常: " + vo.flags());
        assertEquals(1, vo.invoiceCount());
        assertEquals(1, vo.itemCount());
    }

    @Test
    void noItemsAndNoInvoicesIsTreatedAsConsistent() {
        givenInvoices();

        InvoiceMatchVO vo = service.matchInvoices(1L, List.of(), null);

        assertTrue(vo.consistent(), "两者都为空时无核验对象，不应误报");
        assertTrue(vo.flags().isEmpty());
    }
}

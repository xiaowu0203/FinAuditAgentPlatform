package com.finaudit.agentcore.service;

import com.finaudit.agentcore.mapper.InvoiceRecordMapper;
import com.finaudit.agentcore.pojo.entity.InvoiceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发票标识符投影单测（P3.8 R2）。
 * <p>核心回归点：票号必须落成可索引的普通列；无票号不得占位落库（否则同租户无票号票据
 * 会挤在同一条唯一键上互相冲突）；同一张票重复识别必须幂等累加而非新增行。</p>
 */
@ExtendWith(MockitoExtension.class)
class InvoiceRecordServiceTest {

    private static final Long TENANT = 1L;

    @Mock
    private InvoiceRecordMapper invoiceRecordMapper;

    @InjectMocks
    private InvoiceRecordService service;    private static Map<String, Object> ocrResult(String code, String num, String taxNo,
                                                 String amount, String date) {
        Map<String, Object> m = new HashMap<>();
        m.put("invoiceCode", code);
        m.put("invoiceNum", num);
        m.put("taxNo", taxNo);
        m.put("amount", amount);
        m.put("ocrDate", date);
        return m;
    }

    // ---------------- 落库 ----------------

    @Test
    void projectsInvoiceIdentityToIndexedColumns() {
        when(invoiceRecordMapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<InvoiceRecord> captor = ArgumentCaptor.forClass(InvoiceRecord.class);

        service.project(77L, 88L, 9L,
                ocrResult("031002100311", "12345678", "91310000MA1FL1234X", "1130.00", "2026-08-01"));

        verify(invoiceRecordMapper).insert(captor.capture());
        InvoiceRecord saved = captor.getValue();
        // 票号落在普通列上，可被索引命中 —— 这正是 R2 要解决的问题
        assertEquals("031002100311", saved.getInvoiceCode());
        assertEquals("12345678", saved.getInvoiceNum());
        assertEquals("91310000MA1FL1234X", saved.getSellerTaxNo());
        assertEquals(9L, saved.getReimbId());
        assertEquals(88L, saved.getFileRecordId());
        assertEquals(77L, saved.getAttachmentId());
        assertEquals(0, new BigDecimal("1130.00").compareTo(saved.getAmount()));
        assertEquals(LocalDate.of(2026, 8, 1), saved.getInvDate());
        assertEquals(1, saved.getSeenCount());
        assertTrue(saved.hasInvoiceIdentity());
    }

    @Test
    void amountAcceptsBigDecimalAndNumber() {
        when(invoiceRecordMapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<InvoiceRecord> captor = ArgumentCaptor.forClass(InvoiceRecord.class);

        Map<String, Object> m = new HashMap<>();
        m.put("invoiceNum", "12345678");
        m.put("amount", new BigDecimal("560.50"));
        service.project(1L, 2L, 3L, m);

        verify(invoiceRecordMapper).insert(captor.capture());
        assertEquals(0, new BigDecimal("560.50").compareTo(captor.getValue().getAmount()));
    }

    // ---------------- 无票号：必须跳过 ----------------

    @Test
    void skipsProjectionWhenInvoiceNumMissing() {
        // 火车票/打车票没有票号：若用空串占位落库，同租户所有无票号票据会互相撞唯一键
        service.project(1L, 2L, 3L, ocrResult("031002100311", null, null, "553.50", "2026-08-03"));

        verify(invoiceRecordMapper, never()).insert(any(InvoiceRecord.class));
        verify(invoiceRecordMapper, never()).selectOne(any());
    }

    @Test
    void skipsProjectionWhenInvoiceNumBlank() {
        service.project(1L, 2L, 3L, ocrResult(null, "   ", null, "100.00", null));

        verify(invoiceRecordMapper, never()).insert(any(InvoiceRecord.class));
    }

    // ---------------- 幂等：同票重复识别 ----------------

    @Test
    void sameInvoiceSeenAgainAccumulatesInsteadOfInserting() {
        InvoiceRecord existing = InvoiceRecord.from("031002100311", "12345678", null, 5L, 6L, 7L, null, null);
        existing.setId(100L);
        existing.setSeenCount(1);
        when(invoiceRecordMapper.selectOne(any())).thenReturn(existing);

        service.project(77L, 88L, 9L, ocrResult("031002100311", "12345678", null, null, null));

        // 不新增行，只累加次数并刷新来源/归属
        verify(invoiceRecordMapper, never()).insert(any(InvoiceRecord.class));
        verify(invoiceRecordMapper).updateById(existing);
        assertEquals(2, existing.getSeenCount());
        assertEquals(77L, existing.getAttachmentId());
        assertEquals(88L, existing.getFileRecordId());
        assertEquals(9L, existing.getReimbId());
    }

    @Test
    void concurrentInsertFallsBackToAccumulate() {
        InvoiceRecord concurrent = InvoiceRecord.from("031002100311", "12345678", null, 9L, 88L, 77L, null, null);
        concurrent.setSeenCount(1);
        // 第一次查询未命中 → 插入撞唯一键 → 二次查询命中并发写入的行
        when(invoiceRecordMapper.selectOne(any())).thenReturn(null, concurrent);
        when(invoiceRecordMapper.insert(any(InvoiceRecord.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_invoice'"));

        service.project(77L, 88L, 9L, ocrResult("031002100311", "12345678", null, null, null));

        // 并发撞键不得向上抛错，转为累加既有行
        verify(invoiceRecordMapper, times(2)).selectOne(any());
        verify(invoiceRecordMapper).updateById(concurrent);
        assertEquals(2, concurrent.getSeenCount());
    }

    // ---------------- 唯一键取值确定性 ----------------

    @Test
    void missingInvoiceCodeNormalizedToEmptyStringNotNull() {
        when(invoiceRecordMapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<InvoiceRecord> captor = ArgumentCaptor.forClass(InvoiceRecord.class);

        service.project(1L, 2L, 3L, ocrResult(null, "12345678", null, null, null));

        verify(invoiceRecordMapper).insert(captor.capture());
        // MySQL 唯一索引不约束 NULL，落 NULL 会让同一张票重复投影 —— 必须归一为空串
        assertEquals("", captor.getValue().getInvoiceCode());
        assertEquals("12345678", captor.getValue().getInvoiceNum());
    }

    @Test
    void paddedValuesAreTrimmedForDeterministicKey() {
        when(invoiceRecordMapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<InvoiceRecord> captor = ArgumentCaptor.forClass(InvoiceRecord.class);

        service.project(1L, 2L, 3L, ocrResult("  031002100311  ", "  12345678  ", null, null, null));

        verify(invoiceRecordMapper).insert(captor.capture());
        assertEquals("031002100311", captor.getValue().getInvoiceCode());
        assertEquals("12345678", captor.getValue().getInvoiceNum());
    }

    // ---------------- 查询口径 ----------------

    @Test
    void findByInvoiceKeyReturnsNullForBlankNum() {
        // 空票号直接短路，不产生 `= ''` 这种会命中所有无票号行的查询
        assertNull(service.findByInvoiceKey("031002100311", null));
        assertNull(service.findByInvoiceKey("031002100311", "  "));
        verify(invoiceRecordMapper, never()).selectOne(any());
    }

    @Test
    void findByInvoiceNumsEmptyInputReturnsEmptyWithoutQuery() {
        // 空集合不得生成非法 IN ()
        assertTrue(service.findByInvoiceNums(List.of()).isEmpty());
        assertTrue(service.findByInvoiceNums(null).isEmpty());
        assertTrue(service.findByInvoiceNums(List.of("  ", "")).isEmpty());
        verify(invoiceRecordMapper, never()).selectList(any());
    }

    @Test
    void findByInvoiceNumsDeduplicatesAndTrims() {
        when(invoiceRecordMapper.selectList(any())).thenReturn(List.of());

        service.findByInvoiceNums(List.of(" 12345678 ", "12345678", "87654321"));

        // 去重 + 清洗后应只查一次（重复值未去重会生成非法/低效 IN，且此处可验证短路逻辑未误触发）
        verify(invoiceRecordMapper, times(1)).selectList(any());
    }

    @Test
    void listByReimbIdNullReturnsEmpty() {
        assertTrue(service.listByReimbId(null).isEmpty());
        verify(invoiceRecordMapper, never()).selectList(any());
    }

    @Test
    void entityFromFillsSeenCountOne() {
        InvoiceRecord r = InvoiceRecord.from("code", "num", "tax", 1L, 2L, 3L, null, null);
        assertEquals(1, r.getSeenCount());
        assertNotNull(r.getInvoiceNum());
        assertTrue(r.hasInvoiceIdentity());
    }
}

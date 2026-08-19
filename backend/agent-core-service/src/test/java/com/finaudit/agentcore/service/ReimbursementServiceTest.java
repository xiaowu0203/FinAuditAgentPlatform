package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.enums.SqlKeyword;
import com.finaudit.agentcore.mapper.ExpenseReimbursementMapper;
import com.finaudit.agentcore.pojo.dto.ReimbursementItemRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitRequest;
import com.finaudit.agentcore.pojo.dto.ReimbursementResubmitResult;
import com.finaudit.agentcore.pojo.entity.ExpenseAttachment;
import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import com.finaudit.starter.web.feign.FileServiceFeign;
import com.finaudit.starter.web.feign.dto.DuplicateCheckVO;
import com.finaudit.starter.web.feign.dto.FileRecordVO;
import com.finaudit.starter.web.result.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报销域单测（P3b）：resubmit 覆盖字段（title/deptName 强制保留库内旧值、总额服务端重算）、
 * buildSnapshot 快照内容（不含 OSS 路径/预签名 URL、日期转字符串）、queryDuplicates 排除已作废单据。
 */
@ExtendWith(MockitoExtension.class)
class ReimbursementServiceTest {

    @Mock
    private ExpenseReimbursementMapper reimbursementMapper;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private FileServiceFeign fileServiceFeign;
    @Mock
    private AgentTaskService taskService;

    @InjectMocks
    private ReimbursementService service;

    // ---------- resubmit：字段覆盖 + 服务端重算 ----------

    @Test
    void resubmitForcesOldTitleAndDeptAndRecomputesTotal() {
        ExpenseReimbursement reimb = new ExpenseReimbursement();
        reimb.setId(1L);
        reimb.setTenantId(1L);
        reimb.setReimbNo("R2024010100000001");
        reimb.setTitle("出差报销-原始标题");
        reimb.setExpenseType("OFFICE");
        reimb.setDeptName("技术部");
        reimb.setClaimDate(LocalDate.of(2026, 7, 1));
        reimb.setRemark("原始备注");
        reimb.setItems(ExpenseReimbursement.itemsToMaps(List.of(item("旧明细", "100.00"))));
        reimb.setTotalAmount(new BigDecimal("100.00"));
        reimb.setTaskId(100L);
        reimb.setStatus("MANUAL_REVIEW");
        when(reimbursementMapper.selectById(1L)).thenReturn(reimb);
        when(fileServiceFeign.getFiles(eq(1L), any())).thenReturn(R.success(
                List.of(new FileRecordVO(3L, "发票.png", "oss/3.png", "image/png", 1024L),
                        new FileRecordVO(4L, "行程单.png", "oss/4.png", "image/png", 2048L))));

        ReimbursementResubmitResult result = service.resubmit(1L, request(), 1L);

        // title/deptName 强制保留库内旧值（请求体无这两个字段，服务端绝不覆盖）
        assertEquals("出差报销-原始标题", reimb.getTitle());
        assertEquals("技术部", reimb.getDeptName());
        // 其余字段全量覆盖 + 状态回 RUNNING
        assertEquals("TRAVEL", reimb.getExpenseType());
        assertEquals(LocalDate.of(2026, 8, 1), reimb.getClaimDate());
        assertEquals("改低金额", reimb.getRemark());
        assertEquals("RUNNING", reimb.getStatus());
        // 明细替换 + 总额服务端重算（500，不信任客户端）
        assertEquals(2, reimb.getItems().size());
        assertEquals(0, new BigDecimal("500.00").compareTo(reimb.getTotalAmount()));
        verify(reimbursementMapper).updateById(reimb);
        verify(attachmentService).rebindForReimb(eq(List.of(3L, 4L)), eq(1L), eq(1L));

        // 结果契约：taskId + 新快照入参 + 重算总额
        assertEquals(100L, result.taskId());
        assertEquals(0, new BigDecimal("500.00").compareTo(result.totalAmount()));
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) result.inputParams().get("claimedTotal")));
        assertEquals(1L, ((Number) result.inputParams().get("reimbId")).longValue());
        assertEquals("出差报销-原始标题", result.inputParams().get("title"));
        assertEquals(2, ((List<?>) result.inputParams().get("attachments")).size());
    }

    // ---------- buildSnapshot：快照内容（无 OSS 路径、日期转字符串） ----------

    @Test
    void buildSnapshotContainsStructuredFieldsWithoutOssPaths() {
        ExpenseReimbursement reimb = new ExpenseReimbursement();
        reimb.setId(1L);
        reimb.setReimbNo("R2024010100000001");
        reimb.setTitle("出差报销");
        reimb.setExpenseType("TRAVEL");
        reimb.setDeptName("技术部");
        reimb.setTotalAmount(new BigDecimal("553.00"));
        reimb.setClaimDate(LocalDate.of(2026, 8, 1));
        reimb.setRemark("备注");
        reimb.setStatus("MANUAL_REVIEW");
        reimb.setItems(ExpenseReimbursement.itemsToMaps(List.of(item("住宿", "400.00"))));
        when(reimbursementMapper.selectById(1L)).thenReturn(reimb);

        ExpenseAttachment att = new ExpenseAttachment();
        att.setId(91L);
        att.setReimbId(1L);
        att.setFileRecordId(3L);
        att.setFileType("INVOICE");
        att.setOcrStatus("SUCCESS");
        att.setOcrResult(Map.of("merchant", "某某酒店"));
        when(attachmentService.listByReimbId(1L)).thenReturn(List.of(att));

        Map<String, Object> snap = service.buildSnapshot(1L);

        assertNotNull(snap);
        assertEquals("出差报销", snap.get("title"));
        assertEquals(0, new BigDecimal("553.00").compareTo((BigDecimal) snap.get("totalAmount")));
        // 日期转字符串（避免 LocalDate 序列化缺 JavaTimeModule）
        assertEquals("2026-08-01", snap.get("claimDate"));
        // 附件仅结构化引用：fileRecordId/fileType/ocrStatus，绝不含 OSS 路径/预签名 URL
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atts = (List<Map<String, Object>>) snap.get("attachments");
        assertEquals(1, atts.size());
        Map<String, Object> ref = atts.get(0);
        assertEquals(3L, ref.get("fileRecordId"));
        assertEquals("INVOICE", ref.get("fileType"));
        assertEquals("SUCCESS", ref.get("ocrStatus"));
        assertFalse(ref.containsKey("url"));
        assertFalse(ref.containsKey("objectName"));
        assertFalse(ref.containsKey("ossPath"));
    }

    // ---------- queryDuplicates：排除已作废单据 ----------

    @Test
    @SuppressWarnings("unchecked")
    void queryDuplicatesExcludesCancelledReimbursements() {
        ExpenseReimbursement current = new ExpenseReimbursement();
        current.setId(1L);
        current.setTenantId(1L);
        current.setReimbNo("R2024010100000001");
        current.setApplicantId(10L);
        current.setClaimDate(LocalDate.of(2026, 8, 1));
        current.setTotalAmount(new BigDecimal("553.00"));
        when(reimbursementMapper.selectById(1L)).thenReturn(current);
        // 候选：一张 CANCELLED（应被查询条件排除）、一张有效（同金额 + 同商户）
        ExpenseReimbursement cancelled = reimb(2L, "R2024010100000002", "CANCELLED", new BigDecimal("553.00"));
        ExpenseReimbursement valid = reimb(3L, "R2024010100000003", "SUCCESS", new BigDecimal("553.00"));
        when(reimbursementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(cancelled, valid));
        when(attachmentService.listByReimbId(1L)).thenReturn(List.of(attWithMerchant(7L, "某某酒店")));
        when(attachmentService.listByReimbId(2L)).thenReturn(List.of(attWithMerchant(8L, "某某酒店")));
        when(attachmentService.listByReimbId(3L)).thenReturn(List.of(attWithMerchant(9L, "某某酒店")));

        DuplicateCheckVO vo = service.queryDuplicates(1L, 1L);

        // 查询条件必须携带两个 NE 子句：id<>自身（排除自身）+ status<>CANCELLED（排除已作废，
        // 撤回/撤销后发票可复用，不得再当疑似重复候选）。若删掉状态排除，NE 计数降为 1 → 测试失败。
        ArgumentCaptor<LambdaQueryWrapper<ExpenseReimbursement>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(reimbursementMapper).selectList(wrapperCaptor.capture());
        long neCount = wrapperCaptor.getValue().getExpression().getNormal().stream()
                .filter(s -> s instanceof SqlKeyword && ((SqlKeyword) s) == SqlKeyword.NE)
                .count();
        assertEquals(2, neCount, "queryDuplicates 查询条件应排除自身与已作废（CANCELLED）单据");
        // 服务自身不做二次过滤（信任 Mapper 查询结果）：mock 返回含 CANCELLED 行时一并处理
        assertEquals(2, vo.suspected().size());
        assertTrue(vo.suspected().stream().allMatch(d -> d.merchantMatched()));
    }

    // ---------- 构造辅助 ----------

    private static ReimbursementItemRequest item(String name, String amount) {
        return new ReimbursementItemRequest(name, new BigDecimal(amount), null, null, null, null,
                null, null, null, null, null);
    }

    private static ReimbursementResubmitRequest request() {
        return new ReimbursementResubmitRequest("TRAVEL", LocalDate.of(2026, 8, 1), "改低金额",
                List.of(item("住宿", "400.00"), item("高铁", "100.00")), List.of(3L, 4L));
    }

    private static ExpenseReimbursement reimb(Long id, String no, String status, BigDecimal total) {
        ExpenseReimbursement r = new ExpenseReimbursement();
        r.setId(id);
        r.setTenantId(1L);
        r.setReimbNo(no);
        r.setApplicantId(10L);
        r.setStatus(status);
        r.setTotalAmount(total);
        r.setClaimDate(LocalDate.of(2026, 8, 1));
        r.setItems(ExpenseReimbursement.itemsToMaps(List.of(item("住宿", "553.00"))));
        return r;
    }

    private static ExpenseAttachment attWithMerchant(Long fileRecordId, String merchant) {
        ExpenseAttachment a = new ExpenseAttachment();
        a.setFileRecordId(fileRecordId);
        a.setFileType("INVOICE");
        a.setOcrStatus("SUCCESS");
        a.setOcrResult(Map.of("merchant", merchant));
        return a;
    }
}

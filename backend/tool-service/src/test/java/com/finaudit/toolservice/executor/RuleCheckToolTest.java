package com.finaudit.toolservice.executor;

import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.feign.dto.RuleCheckVO;
import com.finaudit.starter.web.feign.dto.RuleHitVO;
import com.finaudit.starter.web.result.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * {@code rule_check} 工具输出装配单测（P3.8 R4）。
 *
 * <p><b>为什么必须锁这条</b>：该工具用<b>手工装配 Map</b> 把 {@code RuleHitVO} 转成工具输出，
 * 而不是整体序列化。R4 给 {@code RuleHitVO} 加了结构化字段（itemIndex/itemName/expected/actual），
 * 却漏改了这里 —— 字段在 VO 里、经过这一层被静默丢掉，
 * 下游 {@code ReviewFlowDecider} 拿到 null，结构化 findings 定位失效。
 * 端到端脚本才暴露出来（单测当时没有覆盖本类）。本测试即为防回归。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleCheckToolTest {

    @Mock
    private AgentCoreServiceFeign agentCoreServiceFeign;

    @InjectMocks
    private RuleCheckTool tool;

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstHit(Map<String, Object> result) {
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        return hits.get(0);
    }

    private void givenHit(RuleHitVO hit) {
        when(agentCoreServiceFeign.checkRules(anyLong(), any()))
                .thenReturn(R.success(new RuleCheckVO(List.of(hit), hit.overLimit())));
    }

    @Test
    void itemLevelHitKeepsStructuredLocatorAndAmounts() {
        // 差旅住宿超标：标准 500（单日×1晚）、实际 800、定位第 1 行
        givenHit(RuleHitVO.ofItem("travel_standard", "差旅标准", "TRAVEL_STANDARD",
                "差旅标准命中：住宿超标", true, 0, "北京出差住宿",
                new BigDecimal("500"), new BigDecimal("800")));

        Map<String, Object> result = tool.execute(1L, Map.of(
                "expenseType", "TRAVEL", "claimDate", "2026-09-01", "totalAmount", 800,
                "items", List.of(Map.of("name", "北京出差住宿", "amount", 800))));

        Map<String, Object> hit = firstHit(result);
        // 这四项若丢失，前端就无法定位到明细行、也无法展示「标准/实际/差额」
        assertEquals(0, hit.get("itemIndex"), "itemIndex 必须透传（前端据此标红明细行）");
        assertEquals("北京出差住宿", hit.get("itemName"), "itemName 必须透传");
        assertEquals(0, new BigDecimal("500").compareTo((BigDecimal) hit.get("expected")), "expected 必须透传");
        assertEquals(0, new BigDecimal("800").compareTo((BigDecimal) hit.get("actual")), "actual 必须透传");
        // 既有字段不受影响
        assertEquals("TRAVEL_STANDARD", hit.get("ruleType"));
        assertEquals(true, hit.get("overLimit"));
    }

    @Test
    void documentLevelHitKeepsAmountsAndNullLocator() {
        // 大额限额：单据级，无明细行定位，但有标准值与实际值
        givenHit(RuleHitVO.ofDocument("amount_limit", "大额报销限额", "AMOUNT_LIMIT",
                "申报总额超过大额限额", true, new BigDecimal("8000"), new BigDecimal("9000")));

        Map<String, Object> result = tool.execute(1L, Map.of(
                "expenseType", "TRAVEL", "claimDate", "2026-09-01", "totalAmount", 9000,
                "items", List.of(Map.of("name", "住宿", "amount", 9000))));

        Map<String, Object> hit = firstHit(result);
        assertEquals(null, hit.get("itemIndex"), "单据级问题不应有明细行定位");
        assertEquals(0, new BigDecimal("8000").compareTo((BigDecimal) hit.get("expected")));
        assertEquals(0, new BigDecimal("9000").compareTo((BigDecimal) hit.get("actual")));
    }

    @Test
    void legacyHitWithoutStructuredFieldsStillSerializes() {
        // 兼容：仅有文本说明的命中（老路径）不得因新字段缺失而报错
        givenHit(new RuleHitVO("r1", "规则", "AMOUNT_LIMIT", "命中说明", true));

        Map<String, Object> result = tool.execute(1L, Map.of(
                "expenseType", "TRAVEL", "claimDate", "2026-09-01", "totalAmount", 100,
                "items", List.of(Map.of("name", "办公", "amount", 100))));

        Map<String, Object> hit = firstHit(result);
        assertEquals("命中说明", hit.get("message"));
        assertTrue(hit.containsKey("itemIndex"), "结构化键应始终存在（值为 null）");
    }
}

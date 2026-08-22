package com.finaudit.agentcore.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工单触发类型确定性映射（P3b 用户确认决策 3）：优先级 OVER_LIMIT &gt; RULE_FAIL &gt; RISK_HIT，
 * LLM_DECISION / 未知前缀归 RISK_HIT 兜底。
 */
class TriggerTypeResolverTest {

    @Test
    void overLimitTakesPriority() {
        String type = TriggerTypeResolver.resolve(List.of(
                "RISK_HIT:疑似重复报销", "OVER_LIMIT:大额限额 超标", "RULE_FAIL:部门预算超支"));
        assertEquals("OVER_LIMIT", type);
    }

    @Test
    void ruleFailBeatsRiskHit() {
        String type = TriggerTypeResolver.resolve(List.of(
                "RISK_HIT:风控置信度缺失", "RULE_FAIL:部门预算超支"));
        assertEquals("RULE_FAIL", type);
    }

    @Test
    void riskHitWhenOnlyRiskReasons() {
        String type = TriggerTypeResolver.resolve(List.of("RISK_HIT:明细金额与申报总额不符"));
        assertEquals("RISK_HIT", type);
    }

    @Test
    void llmDecisionFallsBackToRiskHit() {
        String type = TriggerTypeResolver.resolve(List.of("LLM_DECISION:NEED_INFO"));
        assertEquals("RISK_HIT", type);
    }

    @Test
    void unknownPrefixFallsBackToRiskHit() {
        assertEquals("RISK_HIT", TriggerTypeResolver.resolve(List.of("UNKNOWN:xxx")));
    }

    @Test
    void emptyFallsBackToRiskHit() {
        assertEquals("RISK_HIT", TriggerTypeResolver.resolve(List.of()));
        assertEquals("RISK_HIT", TriggerTypeResolver.resolve(null));
    }

    @Test
    void riskDescJoinsWithSemicolon() {
        assertEquals("A；B", TriggerTypeResolver.buildRiskDesc(List.of("A", "B")));
    }

    @Test
    void riskDescEmptyWhenNoReasons() {
        assertEquals("", TriggerTypeResolver.buildRiskDesc(List.of()));
        assertEquals("", TriggerTypeResolver.buildRiskDesc(null));
    }

    @Test
    void riskDescTruncatesTo512() {
        String joined = "风控".repeat(300); // 600 字符
        String desc = TriggerTypeResolver.buildRiskDesc(List.of(joined));
        assertEquals(512, desc.length());
        assertEquals('…', desc.charAt(511));
    }
}

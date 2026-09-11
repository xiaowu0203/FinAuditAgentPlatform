package com.finaudit.toolservice.executor;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.feign.dto.InvoiceMatchRequest;
import com.finaudit.starter.web.feign.dto.InvoiceMatchVO;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.enums.ToolCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 票据-明细交叉核验工具（P3.8 R3-3 / R3-5）。
 *
 * <p><b>补的什么缺口</b>：{@code amount_verify} 只校验「明细合计 == 申报总额」——
 * 那是同一份数据内部的自洽；{@code rule_check} 校验限额与标准。
 * 两者都<b>没有把「票面」与「明细」对上</b>，于是「明细写 800 元、实际票据只有 100 元」
 * 这类虚报走不到任何检查。本工具用 {@code invoice_record} 投影出的票面金额与申报明细交叉比对。</p>
 *
 * <p><b>为什么比对逻辑放在 agent-core</b>：发票数据（{@code invoice_record}）的访问收敛在
 * {@code InvoiceRecordService}，tool-service 只做入参装配与结果聚合，
 * 与本项目既有分工一致（规则评估在 agent-core、工具只装配）。</p>
 *
 * toolCode: {@link ToolCode#INVOICE_MATCH}
 */
@Component
public class InvoiceMatchTool implements ToolExecutor {

    private final AgentCoreServiceFeign agentCoreServiceFeign;

    public InvoiceMatchTool(AgentCoreServiceFeign agentCoreServiceFeign) {
        this.agentCoreServiceFeign = agentCoreServiceFeign;
    }

    @Override
    public ToolCode toolCode() {
        return ToolCode.INVOICE_MATCH;
    }

    /**
     * 执行票据-明细交叉核验。
     *
     * @param tenantId    租户ID
     * @param inputParams 工具入参：reimbId + items（申报明细）+ claimedTotal（申报合计，可缺省）
     * @return 结果Map：consistent 一致性标记、invoiceTotal/claimTotal/gap 金额对比、flags 异常清单
     * @throws BizException 入参缺失、远程调用失败时抛出
     */
    @Override
    public Map<String, Object> execute(Long tenantId, Map<String, Object> inputParams) {
        Long reimbId = asLong(inputParams == null ? null : inputParams.get("reimbId"));
        if (reimbId == null) {
            throw new BizException("invoice_match 入参缺少 reimbId");
        }

        List<Map<String, Object>> items = toItems(inputParams.get("items"));
        BigDecimal claimedTotal = decimal(inputParams.get("claimedTotal"));

        R<InvoiceMatchVO> resp = agentCoreServiceFeign.matchInvoices(tenantId, reimbId,
                new InvoiceMatchRequest(items, claimedTotal));
        if (resp.getCode() != 0) {
            throw new BizException("票据核验失败: " + resp.getMessage());
        }
        InvoiceMatchVO vo = resp.getData();
        if (vo == null) {
            throw new BizException("票据核验返回为空");
        }

        List<Map<String, Object>> flags = new ArrayList<>();
        for (InvoiceMatchVO.Flag f : vo.flags() == null ? List.<InvoiceMatchVO.Flag>of() : vo.flags()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("message", f.message());
            flags.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        // match：票据与明细是否一致（ReviewFlowDecider 据此判 RULE_FAIL）
        result.put("match", vo.consistent());
        result.put("invoiceCount", vo.invoiceCount());
        result.put("itemCount", vo.itemCount());
        result.put("invoiceTotal", vo.invoiceTotal());
        result.put("claimTotal", vo.claimTotal());
        result.put("gap", vo.gap());
        result.put("flags", flags);
        result.put("message", buildMessage(vo, flags.size()));
        return result;
    }

    /**
     * 组装提示文案。无发票时说明「无法核验」而非「核验通过」——
     * 避免把「没数据」误读成「没问题」。
     */
    private static String buildMessage(InvoiceMatchVO vo, int flagCount) {
        if (vo.invoiceCount() == 0 && vo.itemCount() == 0) {
            return "无票据与明细，跳过交叉核验";
        }
        if (vo.invoiceCount() == 0) {
            return "未识别到发票，无法做票据-明细交叉核验（明细 " + vo.itemCount() + " 条）";
        }
        if (flagCount == 0) {
            return "票据与明细一致：票面合计 " + vo.invoiceTotal() + "，申报合计 " + vo.claimTotal();
        }
        return "票据核验发现 " + flagCount + " 项异常，需人工复核";
    }

    /** 申报明细归一：只保留 name/amount（与 amount_verify 的投影口径一致） */
    private static List<Map<String, Object>> toItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", m.get("name"));
                item.put("amount", m.get("amount"));
                items.add(item);
            }
        }
        return items;
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString().replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

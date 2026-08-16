package com.finaudit.toolservice.executor;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.feign.dto.RuleCheckItem;
import com.finaudit.starter.web.feign.dto.RuleCheckRequest;
import com.finaudit.starter.web.feign.dto.RuleCheckVO;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.enums.ToolCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务规则校验工具实现
 * <p>
 * 财务审核Agent工具：将报销基础信息、报销明细组装为规则校验请求，调用核心服务执行企业财务规则校验；
 * 返回命中的规则列表、是否整体超标标记，供Agent做风险判断，识别报销是否违反制度、额度、差旅标准等规则。
 * toolCode: {@link ToolCode#RULE_CHECK}
 * </p>
 */
@Component
public class RuleCheckTool implements ToolExecutor {
    // Feign客户端，调用agent‑core核心服务执行财务规则校验
    private final AgentCoreServiceFeign agentCoreServiceFeign;

    public RuleCheckTool(AgentCoreServiceFeign agentCoreServiceFeign) {
        this.agentCoreServiceFeign = agentCoreServiceFeign;
    }

    @Override
    public ToolCode toolCode() {
        return ToolCode.RULE_CHECK;
    }

    /**
     * 执行财务规则校验
     * @param tenantId 租户ID，多租户数据隔离
     * @param inputParams 工具入参：expenseType费用类型、claimDate报销日期、totalAmount报销总金额、items报销明细列表
     * @return 结果Map，包含命中规则列表、是否超标、提示文案
     * @throws BizException 必填入参缺失、远程调用业务异常时抛出
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Long tenantId, Map<String, Object> inputParams) {
        // 提取顶层入参
        String expenseType = str(inputParams == null ? null : inputParams.get("expenseType"));
        String claimDate = str(inputParams == null ? null : inputParams.get("claimDate"));
        BigDecimal totalAmount = decimal(inputParams == null ? null : inputParams.get("totalAmount"));
        // 将原始明细对象转换为规则校验明细实体
        List<RuleCheckItem> items = toRuleCheckItems(inputParams == null ? null : inputParams.get("items"));

        // 必填参数校验：报销日期、总金额不能为空
        if (claimDate == null || totalAmount == null) {
            throw new BizException("rule_check 入参缺少 claimDate / totalAmount");
        }

        // 组装规则校验请求对象
        RuleCheckRequest request = new RuleCheckRequest(expenseType, claimDate, items, totalAmount);
        // 财务规则校验
        R<RuleCheckVO> resp = agentCoreServiceFeign.checkRules(tenantId, request);
        if (resp.getCode() != 0) {
            throw new BizException("规则校验失败: " + resp.getMessage());
        }

        // 将命中规则VO转换为Map结构，适配Agent输出
        RuleCheckVO vo = resp.getData();
        List<Map<String, Object>> hits = vo == null || vo.hits() == null ? List.of()
                : vo.hits().stream().map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ruleCode", h.ruleCode());
                    m.put("ruleName", h.ruleName());
                    m.put("ruleType", h.ruleType());
                    m.put("message", h.message());
                    m.put("overLimit", h.overLimit());
                    return m;
                }).toList();
        // 整体是否存在超标项标记
        boolean overLimit = vo != null && vo.overLimit();
        Map<String, Object> result = new LinkedHashMap<>();

        // 组装有序返回结果，便于大模型解析读取
        result.put("hits", hits);
        result.put("overLimit", overLimit);
        result.put("message", hits.isEmpty() ? "未命中财务规则" : "命中 " + hits.size() + " 条财务规则"
                + (overLimit ? "（含超标项，需重点关注）" : ""));
        return result;
    }

    /**
     * 将Agent传入的原始明细集合转换为RuleCheckItem实体列表
     * <p>amount统一转为BigDecimal；字段缺失允许为null，规则评估服务会对缺失字段做跳过处理</p>
     * @param raw Agent传入的原始items数组对象
     * @return 规则校验明细实体集合，非List类型返回空集合
     */
    @SuppressWarnings("unchecked")
    private static List<RuleCheckItem> toRuleCheckItems(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<RuleCheckItem> items = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                items.add(new RuleCheckItem(str(m.get("name")), decimal(m.get("amount")), str(m.get("date"))));
            }
        }
        return items;
    }

    /**
     * 通用对象转字符串工具，null安全
     * @param v 任意对象
     * @return 对象toString结果，输入null返回null
     */
    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * 对象安全转换BigDecimal，兼容Number、String原始入参，转换失败返回null
     * <p>适配Agent工具传入的动态参数，避免类型转换异常</p>
     * @param v 原始参数对象
     * @return BigDecimal，解析失败返回null
     */
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
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

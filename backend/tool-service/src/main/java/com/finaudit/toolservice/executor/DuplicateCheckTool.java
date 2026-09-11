package com.finaudit.toolservice.executor;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.feign.dto.DuplicateCheckVO;
import com.finaudit.starter.web.feign.dto.DuplicateItemVO;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.enums.ToolCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重复报销检测工具实现
 * <p>
 * 财务审核Agent工具：根据报销单ID，调用核心服务检测是否存在疑似重复报销单据，
 * 返回疑似标记、重复单据列表以及提示消息，供Agent做风险判断，提示人工复核。
 * toolCode: {@link ToolCode#DUPLICATE_CHECK}
 * </p>
 */
@Component
public class DuplicateCheckTool implements ToolExecutor {

    // Feign客户端，调用agent‑core核心服务，执行重复报销比对查询
    private final AgentCoreServiceFeign agentCoreServiceFeign;

    public DuplicateCheckTool(AgentCoreServiceFeign agentCoreServiceFeign) {
        this.agentCoreServiceFeign = agentCoreServiceFeign;
    }

    @Override
    public ToolCode toolCode() {
        return ToolCode.DUPLICATE_CHECK;
    }

    /**
     * 执行重复报销检测逻辑
     * @param tenantId 租户ID，多租户数据隔离
     * @param inputParams 工具入参：reimbId 报销单ID
     * @return 结果Map，包含是否疑似重复、疑似单据列表、提示文案
     * @throws BizException 入参缺失、远程调用业务异常时抛出
     */
    @Override
    public Map<String, Object> execute(Long tenantId, Map<String, Object> inputParams) {
        // 提取并转换报销单ID入参
        Long reimbId = asLong(inputParams == null ? null : inputParams.get("reimbId"));
        // 必填参数校验：报销单ID不能为空
        if (reimbId == null) {
            throw new BizException("duplicate_check 入参缺少 reimbId");
        }

        // 查询该报销单的疑似重复单据
        R<DuplicateCheckVO> resp = agentCoreServiceFeign.queryDuplicates(tenantId, reimbId);
        if (resp.getCode() != 0) {
            throw new BizException("重复检测失败: " + resp.getMessage());
        }
        DuplicateCheckVO vo = resp.getData();

        // 转换疑似重复单据列表，为空则返回空集合
        List<Map<String, Object>> duplicates = vo == null || vo.suspected() == null ? List.of()
                : vo.suspected().stream().map(DuplicateCheckTool::toMap).toList();

        String level = vo == null || vo.dupLevel() == null ? DuplicateCheckVO.LEVEL_NONE : vo.dupLevel();
        boolean high = DuplicateItemVO.LEVEL_HIGH.equals(level);

        // 组装有序返回结果，便于大模型解析读取
        Map<String, Object> result = new LinkedHashMap<>();
        // dupLevel 是权威字段（NONE / LEVEL_MEDIUM / LEVEL_HIGH），供风控判定分级处置
        result.put("dupLevel", level);
        // suspectedHigh：发票号硬命中（同一张票已报销），风控应据此触发
        result.put("suspectedHigh", high);
        // suspected：是否存在任何疑似（含仅作展示的中置信），保留以兼容既有调用方
        result.put("suspected", !duplicates.isEmpty());
        result.put("duplicates", duplicates);
        result.put("message", buildMessage(level, duplicates.size()));
        return result;
    }

    /**
     * 组装提示文案，按等级区分「硬命中」与「仅供参考」——避免把中置信当风险上报。
     */
    private static String buildMessage(String level, int count) {
        if (DuplicateItemVO.LEVEL_HIGH.equals(level)) {
            return "发现 " + count + " 条疑似重复报销，其中含发票号硬命中（同一张票已报销），需人工复核";
        }
        if (DuplicateItemVO.LEVEL_MEDIUM.equals(level)) {
            return "发现 " + count + " 条同金额同商户近似单据（无发票号硬命中），仅供参考、不阻断";
        }
        return "未发现疑似重复报销";
    }

    /**
     * 将重复报销项VO转换为Map结构，适配Agent工具输出格式
     * @param d 疑似重复报销单据VO
     * @return 结构化Map，包含报销ID、单号、标题、金额、日期、商户、匹配标记及重复等级
     */
    private static Map<String, Object> toMap(DuplicateItemVO d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reimbId", d.reimbId());
        m.put("reimbNo", d.reimbNo());
        m.put("title", d.title());
        m.put("totalAmount", d.totalAmount());
        m.put("claimDate", d.claimDate());
        m.put("merchant", d.merchant());
        m.put("merchantMatched", d.merchantMatched());
        // P3.8 R3：等级与票号（HIGH 级才有票号）
        m.put("dupLevel", d.dupLevel());
        m.put("invoiceCode", d.invoiceCode());
        m.put("invoiceNum", d.invoiceNum());
        return m;
    }

    /**
     * 对象安全转换Long，兼容数字、字符串入参，转换失败返回null
     * <p>适配Agent动态传入的参数，避免类型转换异常</p>
     * @param v 原始参数对象
     * @return Long值，解析失败返回null
     */
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

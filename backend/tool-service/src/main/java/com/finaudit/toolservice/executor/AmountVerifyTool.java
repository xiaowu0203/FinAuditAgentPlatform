package com.finaudit.toolservice.executor;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.toolservice.enums.ToolCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 金额核验工具（amount_verify）。
 * <p>入参：{@code items:[{name,amount}], claimedTotal}；加总明细并与申报总额比对，返回
 * {@code {total, claimedTotal, match, diff}}。金额计算全程 BigDecimal，严禁 float/double。</p>
 */
@Component
public class AmountVerifyTool implements ToolExecutor {

    /**
     * 获取工具唯一编码
     * @return 金额核验工具编码 AMOUNT_VERIFY
     */
    @Override
    public ToolCode toolCode() {
        return ToolCode.AMOUNT_VERIFY;
    }

    /**
     * 执行金额校验核心逻辑
     * @param inputParams 入参Map，包含两个key：
     *                    items：明细列表，List<Map>，单条明细必须包含amount金额字段
     *                    claimedTotal：申报总金额，支持数字/字符串/BigDecimal多种类型
     * @return 校验结果Map，包含total、claimedTotal、match、diff、message
     * @throws BizException 入参缺失、明细为空、金额为空、金额格式非法、类型不支持时抛出业务异常
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> inputParams) {
        if (inputParams == null) {
            throw new BizException("入参不能为空");
        }
        // 获取明细列表items
        List<Map<String, Object>> items = (List<Map<String, Object>>) inputParams.get("items");
        // 明细列表不能为空，无明细无法计算总额
        if (items == null || items.isEmpty()) {
            throw new BizException("items 明细不能为空");
        }
        // 获取申报总额原始对象
        Object claimedTotalObj = inputParams.get("claimedTotal");
        if (claimedTotalObj == null) {
            throw new BizException("claimedTotal 申报总额不能为空");
        }

        // 将申报总额转为BigDecimal，统一保留2位小数，四舍五入
        BigDecimal claimedTotal = toBigDecimal(claimedTotalObj).setScale(2, RoundingMode.HALF_UP);
        // 初始化明细累加总额，初始值0
        BigDecimal total = BigDecimal.ZERO;
        // 循环遍历每一条明细，累加金额
        for (Map<String, Object> item : items) {
            Object amount = item.get("amount");
            // 单条明细金额不能为空
            if (amount == null) {
                throw new BizException("明细缺少 amount: " + item);
            }
            // 转换金额并累加至总额
            total = total.add(toBigDecimal(amount));
        }
        // 明细总额统一保留2位小数，四舍五入对齐金额精度
        total = total.setScale(2, RoundingMode.HALF_UP);

        // 判断明细总额与申报总额是否完全相等
        boolean match = total.compareTo(claimedTotal) == 0;
        // 计算差额 = 明细总额 - 申报总额
        BigDecimal diff = total.subtract(claimedTotal);

        // 封装返回的结构体
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("claimedTotal", claimedTotal);
        result.put("match", match);
        result.put("diff", diff);
        result.put("message", match ? "金额一致" : "金额不一致，差异 " + diff.toPlainString());
        return result;
    }

    /**
     * 将任意类型的金额对象统一转换为BigDecimal，解决JSON反序列化多类型数值问题
     * 支持类型：BigDecimal、Integer、Long、Double、Float、String
     * 禁止直接使用float/double运算，仅做转换兼容
     * @param value 原始金额对象
     * @return 转换后的BigDecimal
     * @throws BizException 对象为空、字符串数字格式错误、不支持的类型抛出异常
     */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            throw new BizException("金额不能为空");
        }
        // 原生BigDecimal直接返回
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        // 整型数字
        if (value instanceof Integer i) {
            return BigDecimal.valueOf(i.longValue());
        }
        // 长整型数字
        if (value instanceof Long l) {
            return BigDecimal.valueOf(l.longValue());
        }
        // 双精度浮点，仅做转换，不建议业务传入浮点类型
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d.doubleValue());
        }
        // 单精度浮点，仅做转换，不建议业务传入浮点类型
        if (value instanceof Float f) {
            return BigDecimal.valueOf(f.doubleValue());
        }
        // 字符串数字，捕获数字格式异常
        if (value instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                throw new BizException("金额格式非法: " + s);
            }
        }
        // 其余未兼容类型直接抛出异常
        throw new BizException("金额类型不支持: " + value.getClass().getSimpleName());
    }
}

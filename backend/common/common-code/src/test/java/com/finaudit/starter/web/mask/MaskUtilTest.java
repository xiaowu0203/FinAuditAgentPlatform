package com.finaudit.starter.web.mask;

import com.finaudit.starter.web.mask.enums.MaskType;
import com.finaudit.starter.web.mask.util.MaskUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaskUtilTest {

    @Test
    void phoneMaskKeepsFirst3Last4() {
        assertEquals("138****5678", MaskUtil.mask(MaskType.PHONE, "13812345678"));
    }

    @Test
    void taxNoMaskKeepsFirst3Last3() {
        // 913101155XXXX5X5 共 16 位 → 保留前3后3，中段 10 位掩码；后3位为 "5X5"
        assertEquals("913**********5X5", MaskUtil.mask(MaskType.TAX_NO, "913101155XXXX5X5"));
    }

    @Test
    void idCardMaskKeepsFirst3Last4() {
        // 18 位 → 保留前3后4，中段 11 位掩码
        assertEquals("110***********1234", MaskUtil.mask(MaskType.ID_CARD, "110101199003071234"));
    }

    @Test
    void bankCardMaskKeepsFirst6Last4() {
        // 18 位 → 保留前6后4，中段 8 位掩码
        assertEquals("622202********1122", MaskUtil.mask(MaskType.BANK_CARD, "622202020017081122"));
    }

    @Test
    void shortValueNotMaskedToAvoidFalselyHiding() {
        // 总长不足 head+tail+1 → 不遮挡（避免误伤短代码/名称）
        assertEquals("ab", MaskUtil.mask(MaskType.PHONE, "ab"));
        assertEquals("12345", MaskUtil.mask(MaskType.ID_CARD, "12345"));
    }

    @Test
    void nullValueReturnsNull() {
        assertNull(MaskUtil.mask(MaskType.PHONE, null));
    }

    @Test
    void mapMaskingHidesTaxNoKeepsAmount() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "差旅费");
        item.put("amount", BigDecimal.valueOf(888.80));
        Map<String, Object> ocr = new LinkedHashMap<>();
        ocr.put("taxNo", "913101155XXXX5X5");
        ocr.put("merchant", "某公司");
        ocr.put("amount", "123.45");
        ocr.put("nested", item);

        Map<String, Object> result = MaskUtil.maskSensitiveMap(ocr, MaskType.TAX_NO);

        // 税号被掩码（16 位 → 保留前3后3，中段10位）
        assertEquals("913**********5X5", result.get("taxNo"));
        // 商户名/金额保持明文
        assertEquals("某公司", result.get("merchant"));
        assertEquals("123.45", result.get("amount"));
        // 嵌套 Map 递归：明细金额仍明文
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) result.get("nested");
        assertEquals(BigDecimal.valueOf(888.80), nested.get("amount"));
        assertEquals("差旅费", nested.get("name"));
    }
}

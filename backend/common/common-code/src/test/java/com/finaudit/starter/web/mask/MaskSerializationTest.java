package com.finaudit.starter.web.mask;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.starter.web.mask.annotation.Mask;
import com.finaudit.starter.web.mask.enums.MaskType;
import com.finaudit.starter.web.mask.jackson.MaskIntrospector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link MaskIntrospector} 挂到 ObjectMapper 后，标注 {@code @Mask} 的字段在序列化时被脱敏、
 * 金额保持明文。
 */
class MaskSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().setAnnotationIntrospector(new MaskIntrospector());

    /** 模拟用户详情 VO（phone 脱敏）。 */
    record UserVo(
            @JsonProperty("username") String username,
            @Mask(MaskType.PHONE) @JsonProperty("phone") String phone) {
    }

    /** 模拟附件 VO：ocrResult Map 脱敏。 */
    record AttachmentVo(
            @JsonProperty("fileName") String fileName,
            @Mask(MaskType.TAX_NO) @JsonProperty("ocrResult") Map<String, Object> ocrResult) {
    }

    @Test
    void phoneFieldIsMaskedOnSerialize() throws JsonProcessingException {
        String json = mapper.writeValueAsString(new UserVo("zhangsan", "13812345678"));
        assertTrue(json.contains("138****5678"));
        assertTrue(!json.contains("13812345678"));
    }

    @Test
    void ocrMapTaxNoMaskedAndAmountPlain() throws JsonProcessingException {
        Map<String, Object> ocr = new LinkedHashMap<>();
        ocr.put("taxNo", "913101155XXXX5X5");
        ocr.put("amount", BigDecimal.valueOf(123.45));
        ocr.put("merchant", "某公司");

        String json = mapper.writeValueAsString(new AttachmentVo("invoice.png", ocr));
        assertTrue(json.contains("913**********5X5"));
        assertTrue(!json.contains("913101155XXXX5X5"));
        // 金额明文保留
        assertTrue(json.contains("\"amount\":123.45"));
        assertTrue(json.contains("某公司"));
    }

    @Test
    void unannotatedFieldNotTouched() throws JsonProcessingException {
        String json = mapper.writeValueAsString(new UserVo("lisi", "13812345678"));
        assertTrue(json.contains("lisi"));
    }
}

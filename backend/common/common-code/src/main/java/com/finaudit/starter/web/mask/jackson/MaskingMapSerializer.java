package com.finaudit.starter.web.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.finaudit.starter.web.mask.enums.MaskType;
import com.finaudit.starter.web.mask.util.MaskUtil;

import java.io.IOException;
import java.util.Map;

/**
 * Map 字段脱敏序列化器：递归脱敏 Map 内部敏感值，例如 {@code AttachmentVO.ocrResult} 中的 taxNo、idCard 等隐私字段。
 * <p>金额相关键不参与脱敏，财务金额永不掩码。</p>
 * <p>本序列化器不由全局直接注册，由 {@link MaskIntrospector} 在识别到标注 {@code @Mask} 的 Map 类型字段时动态装配。</p>
 * <p>线程安全：实例仅持有 {@link MaskType} 配置，无业务运行时状态。</p>
 */
public class MaskingMapSerializer extends JsonSerializer<Object> {

    /** 脱敏策略类型 */
    private final MaskType maskType;

    /**
     * @param maskType 脱敏策略
     */
    public MaskingMapSerializer(MaskType maskType) {
        this.maskType = maskType;
    }

    /**
     * 执行Map序列化，内部调用{@link MaskUtil#maskSensitiveMap(Map, MaskType)}完成递归脱敏
     * @param value 原始map对象
     * @param gen json生成器
     * @param serializers 序列化上下文
     * @throws IOException IO异常
     */
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> safe = value instanceof Map<?, ?> m
                ? MaskUtil.maskSensitiveMap((Map<String, Object>) m, maskType)
                : Map.of();
        gen.writeObject(safe);
    }
}

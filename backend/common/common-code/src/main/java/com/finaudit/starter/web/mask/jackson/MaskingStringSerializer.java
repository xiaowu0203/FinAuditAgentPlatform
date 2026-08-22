package com.finaudit.starter.web.mask.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.finaudit.starter.web.mask.enums.MaskType;
import com.finaudit.starter.web.mask.util.MaskUtil;

import java.io.IOException;

/**
 * String 字段脱敏序列化器：按照指定 {@link MaskType} 对字符串隐私值执行掩码后输出JSON。
 * <p>不全局注册，由 {@link MaskIntrospector} 在识别标注 {@code @Mask} 的String类型字段时动态装配。</p>
 * <p>线程安全：仅持有脱敏策略配置{@link MaskType}，无运行时业务状态。</p>
 */
public class MaskingStringSerializer extends JsonSerializer<Object> {

    /** 脱敏策略类型 */
    private final MaskType maskType;

    /**
     * @param maskType 脱敏策略类型
     */
    public MaskingStringSerializer(MaskType maskType) {
        this.maskType = maskType;
    }

    /**
     * 执行字符串序列化，调用 {@link MaskUtil#mask(MaskType, String)} 完成掩码处理
     * @param value 原始字段值
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
        gen.writeString(MaskUtil.mask(maskType, value.toString()));
    }
}

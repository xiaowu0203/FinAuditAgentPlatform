package com.finaudit.starter.web.mask.jackson;

import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.finaudit.starter.web.mask.annotation.Mask;

import java.util.Map;

/**
 * 脱敏注解内省器：识别标注了 {@link com.finaudit.starter.web.mask.annotation.Mask}的字段/record component，返回对应脱敏序列化器。
 * <p>不全局强制替换序列化逻辑，仅对标记{@code @Mask}的字段生效。仅影响序列化，不影响反序列化。</p>
 * <ul>
 *   <li>Map 字段（如 {@code AttachmentVO.ocrResult}）→ {@link MaskingMapSerializer}（递归脱敏敏感键）</li>
 *   <li>String 字段（如手机号）→ {@link MaskingStringSerializer}</li>
 *   <li>其他类型：交给默认逻辑，但其内嵌 VO 字段若也标了 {@code @Mask} 会由同一内省器递归处理</li>
 * </ul>
 */
public class MaskIntrospector extends JacksonAnnotationIntrospector {

    /**
     * 查找字段对应的自定义序列化器
     * @param am Jackson注解元数据
     * @return 脱敏序列化器实例；无@Mask注解则走父类原有逻辑
     */
    @Override
    public Object findSerializer(Annotated am) {
        // 识别@Mask注解
        Mask mask = am.getAnnotation(Mask.class);
        // 若携带Mask注解
        if (mask != null) {
            // 获取Class类型
            Class<?> raw = am.getRawType();
            // Map类型走MaskingMapSerializer
            if (Map.class.isAssignableFrom(raw)) {
                return new MaskingMapSerializer(mask.value());
            }
            // 其余类型走MaskingStringSerializer
            return new MaskingStringSerializer(mask.value());
        }
        return super.findSerializer(am);
    }
}

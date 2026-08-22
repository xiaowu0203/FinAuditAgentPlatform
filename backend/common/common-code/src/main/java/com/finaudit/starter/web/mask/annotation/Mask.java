package com.finaudit.starter.web.mask.annotation;

import com.finaudit.starter.web.mask.enums.MaskType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 输出脱敏注解。
 * <p>标注在对外 VO 字段 / record component 上，Jackson 序列化阶段自动执行脱敏。</p>
 * <p>支持 {@link String} 普通字符串字段、{@link java.util.Map} 扩展字段（Map 内部递归脱敏敏感值）。</p>
 * <p>⚠️ 重要约束：
 * <ul>
 * <li>仅作用于 JSON 序列化输出（write），不影响反序列化读取；</li>
 * <li>数据库DO、Feign调用DTO、MQ消息实体<strong>禁止添加该注解</strong>，保证内部链路传递明文；</li>
 * <li>只用于 Controller 返回对外VO，避免内部调用发生数据掩码污染。</li>
 * </ul>
 * </p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD})
public @interface Mask {

    /**
     * 脱敏策略类型
     *
     * @return 脱敏类型，默认 GENERAL：通用启发式脱敏，保留首尾各1位，中间掩码
     */
    MaskType value() default MaskType.GENERAL;
}

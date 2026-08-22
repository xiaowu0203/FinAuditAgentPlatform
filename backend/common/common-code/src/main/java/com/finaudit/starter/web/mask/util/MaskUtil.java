package com.finaudit.starter.web.mask.util;

import com.finaudit.starter.web.mask.enums.MaskType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脱敏工具（纯静态无状态工具类）。
 * <p>依据 {@link MaskType} 对隐私字符串生成掩码，保留首尾部分字符，中段替换为 {@code ****}；
 * null、空字符串、过短字符串直接原样返回，避免误伤短业务码。</p>
 * <p>业务约束：金额字段不经过本工具处理，财务金额永不脱敏。</p>
 * <p>提供Map递归脱敏能力，用于处理如 {@code AttachmentVO.ocrResult} 票据识别结果这类嵌套Map，
 * 通过 {@link #isSensitiveKey(String)} 判断敏感key，递归完成内部隐私值掩码。</p>
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /** 掩码替换字符 */
    private static final char MASK_CHAR = '*';

    /**
     * 根据脱敏类型执行字符串掩码处理
     *
     * @param type  脱敏策略类型
     * @param value 原始隐私字符串，null返回null
     * @return 掩码之后的字符串
     */
    public static String mask(MaskType type, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (type) {
            case ID_CARD -> maskKeep(value, 3, 4);
            case BANK_CARD -> maskKeep(value, 6, 4);
            case TAX_NO -> maskKeep(value, 3, 3);
            case PHONE -> maskKeep(value, 3, 4);
            case GENERAL -> maskKeep(value, 1, 1);
        };
    }

    /**
     * 保留头部head位、尾部tail位，中间全部替换为掩码字符。
     * <p>字符串总长度 ≤ head+tail，说明文本过短，不做掩码直接返回原值，防止短标识被破坏。</p>
     *
     * @param value 原始字符串
     * @param head  需要保留的头部字符数量
     * @param tail  需要保留的尾部字符数量
     * @return 掩码完成后的字符串
     */
    private static String maskKeep(String value, int head, int tail) {
        int len = value.length();
        if (len <= head + tail) {
            return value;
        }
        StringBuilder sb = new StringBuilder(len);
        sb.append(value, 0, head);
        int mid = len - head - tail;
        sb.append(String.valueOf(MASK_CHAR).repeat(mid));
        sb.append(value, len - tail, len);
        return sb.toString();
    }

    /**
     * 判断当前key是否属于敏感字段（大小写忽略），用于Map递归脱敏识别隐私key。
     * <ul>
     *   <li>税号类：taxNo / sellerRegisterNum / buyerRegisterNum / taxId / 税号 / 纳税人识别号</li>
     *   <li>身份类：idCard / 身份证 / bankCard / 银行卡 / phone / mobile / 手机号</li>
     * </ul>
     *
     * @param key map的键名
     * @return true=敏感key，需要做掩码；false无需处理
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return "taxno".equals(k) || "taxid".equals(k)
                || "sellerregisternum".equals(k) || "buyerregisternum".equals(k)
                || k.contains("身份证") || k.contains("税号") || k.contains("纳税人识别号")
                || k.contains("idcard") || k.contains("bankcard")
                || k.equals("phone") || k.equals("mobile") || k.contains("手机号");
    }

    /**
     * 递归脱敏Map内部敏感值，不修改入参源对象，返回全新LinkedHashMap副本。
     * <p>不指定优先脱敏类型，会自动根据key语义推断脱敏策略；金额相关key原样保留不脱敏。</p>
     *
     * @param source 原始待处理map
     * @return 脱敏后的新Map实例
     */
    public static Map<String, Object> maskSensitiveMap(Map<String, Object> source) {
        return maskSensitiveMap(source, null);
    }

    /**
     * 递归脱敏Map内部敏感值，可传入注解指定的优先脱敏类型。
     * <p>注意：不会修改入参source，返回全新Map对象；嵌套Map会递归处理。</p>
     * @param source       原始待处理map，null返回null
     * @param preferredType 字段注解上指定的脱敏类型；传null则自动根据key语义选择策略
     * @return 脱敏完成的新Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> maskSensitiveMap(Map<String, Object> source, MaskType preferredType) {
        if (source == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> out.put(k, maskValue(k, v, preferredType)));
        return out;
    }

    /**
     * 处理Map单条键值：嵌套Map递归调用；敏感key且值为字符串执行掩码；其余直接返回原值。
     * @param key           map键名
     * @param value         map原始值
     * @param preferredType 注解优先脱敏类型，可为null
     * @return 处理之后的值
     */
    @SuppressWarnings("unchecked")
    private static Object maskValue(String key, Object value, MaskType preferredType) {
        if (value instanceof Map<?, ?> nested) {
            return maskSensitiveMap((Map<String, Object>) nested, preferredType);
        }
        if (value instanceof String s && isSensitiveKey(key)) {
            // 税号类键优先 TAX_NO 形态；否则用 PHONE 启发式；注解指定类型则优先采用
            MaskType type = preferredType;
            if (type == null) {
                type = isTaxKey(key) ? MaskType.TAX_NO : MaskType.PHONE;
            }
            return mask(type, s);
        }
        return value;
    }

    /**
     * 判断key是否属于税号类关键字，用于自动推断脱敏策略。
     * 包含税号、纳税人识别号、统一社会信用代码、购销方注册编号等。
     * @param key map键名
     * @return true代表税号类key
     */
    private static boolean isTaxKey(String key) {
        String k = key.toLowerCase();
        return "taxno".equals(k) || "taxid".equals(k) || k.contains("税号")
                || k.contains("纳税人识别号") || k.contains("registernum") || k.contains("tax")
                || k.contains("统一社会信用代码");
    }
}

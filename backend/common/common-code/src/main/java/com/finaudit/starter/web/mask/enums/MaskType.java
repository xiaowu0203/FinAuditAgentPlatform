package com.finaudit.starter.web.mask.enums;

/**
 * 脱敏类型枚举（P3c 安全风控模块）。
 * <p>配合 {@link com.finaudit.starter.web.mask.annotation.Mask} 注解使用，
 * 仅用于对外 Controller VO JSON 序列化输出层做隐私掩码处理。</p>
 * <p><b>业务约束：金额字段不使用本枚举，财务金额永不脱敏；内部DO、Feign、MQ对象不启用脱敏。</b></p>
 */
public enum MaskType {

    /** 身份证号：保留前 3 后 4，中段 **** */
    ID_CARD,

    /** 银行卡号：保留前 6 后 4，中段 **** */
    BANK_CARD,

    /** 税号 / 纳税人识别号：保留前 3 后 3，中段 **** */
    TAX_NO,

    /** 手机号：保留前 3 后 4，中段 **** */
    PHONE,

    /** 通用：无明确类型时按「保留首尾各 1，中段 ****」启发式 */
    GENERAL
}

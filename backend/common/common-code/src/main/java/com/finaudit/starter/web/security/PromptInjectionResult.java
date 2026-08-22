package com.finaudit.starter.web.security;

/**
 * Prompt注入检测结果记录对象。
 * <p>由 {@link PromptInjectionGuard} 扫描后返回，携带是否命中、命中的正则模式、日志详情；
 * 不抛出异常，交由上层业务根据hit字段做后续处置（本项目命中则流转人工复核）。</p>
 *
 * @param hit             是否命中注入风险规则，true=命中风险
 * @param matchedPattern  命中的正则表达式原始字符串；未命中为null
 * @param detail          可读描述信息，用于日志打印；未命中为null
 */
public record PromptInjectionResult(boolean hit, String matchedPattern, String detail) {

    /**
     * 构造未命中的检测结果
     * @return 通过结果对象，hit=false，其余字段为null
     */
    public static PromptInjectionResult pass() {
        return new PromptInjectionResult(false, null, null);
    }

    /**
     * 构造命中风险的检测结果
     * @param matchedPattern 命中的正则原始pattern
     * @param detail 可读命中详情，用于日志输出
     * @return 命中结果对象，hit=true
     */
    public static PromptInjectionResult hit(String matchedPattern, String detail) {
        return new PromptInjectionResult(true, matchedPattern, detail);
    }
}

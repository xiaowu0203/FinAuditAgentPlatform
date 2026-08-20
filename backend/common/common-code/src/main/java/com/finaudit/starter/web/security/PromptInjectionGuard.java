package com.finaudit.starter.web.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测工具。
 * <p>在 LLM prompt 拼接完成之后、调用大模型之前，针对<b>不可信外部输入拼装的文本</b>做提示词注入、越狱、指令覆盖攻击检测。</p>
 * <p>业务语义约定：对齐执行文档 §9；检测组件仅产出检测结果对象，<b>不主动抛出异常</b>，
 * 由上层调用方处理命中事件，本项目命中后策略为转入人工复核，不直接阻断任务。</p>
 * <p>实现：纯正则字符串扫描，无SpringAI、大模型依赖，放置于common‑code可全服务复用；
 * 支持{@link Builder}构建器，可在默认规则基础上追加自定义正则规则。</p>
 */
public final class PromptInjectionGuard {

    /**
     * 内置默认注入正则规则集合，全部忽略大小写；覆盖中英文越狱、指令覆盖、系统提示词泄露、绕过审核等场景。
     */
    private static final Pattern[] DEFAULT_PATTERNS = compile(
            "(?i)忽略(上面|以上|之前|前面)?(所有|全部)?(指令|规则|提示|要求|内容)",
            "(?i)ignore\\s+(all\\s+)?(above|previous|prior|earlier|the\\s+instructions|the\\s+prompt|system|rules)",
            "(?i)role\\s*play(\\s+as)?",
            "(?i)(你现在|你(是|将|要)(成为|扮演))\\s*(一个|一名)?(系统|客服|助手|ai|模型|银行)",
            "(?i)jailbreak",
            "(?i)\\b(leak|reveal|show|print|output)\\b.{0,15}(system|internal|developer|hidden)\\s*(prompt|instructions|rules|guidance|configuration)",
            "(?i)(请|展示|输出|告诉)?(我)?(系统|内部|开发者|隐藏).{0,6}(提示词|prompt|指令|规则|guidance).{0,8}(是什么|展示|泄露|输出|内容)",
            "(?i)展示.{0,4}(系统|内部|开发者|隐藏).{0,4}(提示词|prompt|指令|规则)",
            "(?i)绕过.{0,12}(审核|限制|规则|校验|风控)",
            "(?i)(装作|假装|假设).{0,12}(通过|approve|审核|系统)",
            "(?i)<(system|developer|human|assistant)>",
            "(?i)\\b(disregard|forget)\\s+",
            "(?i)(直接|请)?(输出|返回|判定)(\\s+结果为)?(通过|approve|pass)\\s*$"
    );

    /** 生效的正则匹配规则数组 */
    private final Pattern[] patterns;

    private PromptInjectionGuard(Pattern[] patterns) {
        this.patterns = patterns;
    }

    /** 默认全局单例，加载内置全部规则，业务优先使用该实例 */
    private static final PromptInjectionGuard DEFAULT = new PromptInjectionGuard(DEFAULT_PATTERNS);

    /**
     * 全参检测。
     *
     * @param scope 调用上下文标识（如 {@code LLM_STEP}），仅用于命中信息；可为 null
     * @param text  待检文本（不可信外部输入合并后的 prompt user 部分）
     * @return {@link PromptInjectionResult}，包含是否命中、命中详情
     */
    public static PromptInjectionResult scan(String scope, String text) {
        return DEFAULT.scanAll(scope, text);
    }

    /**
     * 注入检测静态入口，不带上下文标识
     *
     * @param text 待检测文本：外部不可信输入拼接后的user prompt片段
     * @return 检测结果对象{@link PromptInjectionResult}
     */
    public static PromptInjectionResult scan(String text) {
        return scan(null, text);
    }

    /**
     * 实例检测方法，用于Builder自定义规则生成的实例
     *
     * @param scope 调用上下文标识
     * @param text 待检测文本
     * @return 检测结果对象{@link PromptInjectionResult}
     */

    public PromptInjectionResult inspect(String scope, String text) {
        return scanAll(scope, text);
    }

    /**
     * 执行全部正则扫描匹配逻辑
     * @param scope 上下文标记
     * @param text 待检测原始文本
     * @return 命中 / 通过的检测结果
     */
    private PromptInjectionResult scanAll(String scope, String text) {
        if (text == null || text.isEmpty()) {
            return PromptInjectionResult.pass();
        }
        String src = text.toLowerCase(Locale.ROOT);
        for (Pattern p : patterns) {
            Matcher m = p.matcher(src);
            if (m.find()) {
                String matched = m.group();
                String detail = scope == null
                        ? "命中注入规则: " + matched
                        : "[" + scope + "] 命中注入规则: " + matched;
                return PromptInjectionResult.hit(p.pattern(), detail);
            }
        }
        return PromptInjectionResult.pass();
    }

    /**
     * 将一组正则字符串编译为Pattern数组
     * @param regex 正则表达式字符串数组
     * @return 编译完成的Pattern数组
     */
    private static Pattern[] compile(String... regex) {
        Pattern[] arr = new Pattern[regex.length];
        for (int i = 0; i < regex.length; i++) {
            arr[i] = Pattern.compile(regex[i]);
        }
        return arr;
    }

    /**
     * 规则构建器Builder
     * <p>默认继承内置全部规则，支持追加自定义正则；用于业务需要扩充检测规则的场景。</p>
     */
    public static final class Builder {
        private final List<String> rules = new ArrayList<>();

        /** 初始化，加载内置默认正则规则 */
        public Builder() {
            for (Pattern p : DEFAULT_PATTERNS) {
                rules.add(p.pattern());
            }
        }

        /**
         * 追加自定义注入检测正则规则
         * @param regex 正则表达式字符串
         * @return builder自身，链式调用
         */
        public Builder add(String regex) {
            rules.add(regex);
            return this;
        }

        /**
         * 构建Guard实例，编译全部正则规则
         * @return PromptInjectionGuard实例
         */
        public PromptInjectionGuard build() {
            return new PromptInjectionGuard(compile(rules.toArray(new String[0])));
        }
    }
}

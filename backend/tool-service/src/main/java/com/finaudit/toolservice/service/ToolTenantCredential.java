package com.finaudit.toolservice.service;

/**
 * 工具调用的租户凭证（P3.8 R6-2）。
 *
 * <p><b>它解决什么问题</b>：{@link ToolAccessGuard} 早先比较的「请求上下文租户」
 * （{@code TenantContextHolder}）与「声明租户」在两条链路上都<b>同源</b>——MQ 消费者用消息里的
 * 租户设置上下文、随后又用同一个值执行；HTTP 链路两者都取自网关注入的 {@code X-Tenant-Id}。
 * 于是那道「租户一致性校验」在生产路径上<b>恒等通过</b>，等于没有校验。</p>
 *
 * <p><b>修法</b>：把两个来源分离成显式入参，并各自绑定一个<b>独立</b>的事实来源：</p>
 * <ul>
 *   <li>HTTP 用户侧：{@code authTenantId} 取网关/JWT 派生上下文（{@code UserContextHolder}），
 *       与请求头声明的 {@code declaredTenantId} 比对；且该值缺失即拒绝（fail-closed，
 *       由 {@code ToolController} 强制）——绕过网关直连服务伪造租户头会被拦下。</li>
 *   <li>MQ 内部侧：无 JWT 上下文，权威租户改由<b>任务归属</b>代替——
 *       {@code taskId} 经 agent-core 反查真实归属租户（库内事实），
 *       与消息声明的租户比对；消息被篡改时查不到 → 拒绝。</li>
 * </ul>
 *
 * @param authTenantId     权威租户 ID（HTTP：网关/JWT 派生；MQ：null）
 * @param declaredTenantId 声明租户 ID（本次执行要操作的租户）
 * @param taskId           任务 ID（MQ 链路用于反查归属；HTTP 链路为 null）
 */
public record ToolTenantCredential(Long authTenantId, Long declaredTenantId, Long taskId) {

    /** HTTP 用户侧凭证：权威租户来自网关/JWT 派生上下文 */
    public static ToolTenantCredential http(Long authTenantId, Long declaredTenantId) {
        return new ToolTenantCredential(authTenantId, declaredTenantId, null);
    }

    /** MQ 内部侧凭证：无 JWT 上下文，权威租户由任务归属反查代替 */
    public static ToolTenantCredential mq(Long declaredTenantId, Long taskId) {
        return new ToolTenantCredential(null, declaredTenantId, taskId);
    }
}

package com.finaudit.agentcore.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务进度 VO（P3.8 R8-3）：进度百分比 + 预计剩余时间，供任务详情页轮询展示。
 *
 * <p><b>与 {@link TaskVO} 的分工</b>：{@code TaskVO} 是任务全貌（含 result/selfCheckResult 等重字段），
 * 轮询接口每几秒拉一次不该带这些；本 VO 只含进度所需的最小字段，且**每个字段都能自解释**，
 * 前端不必自己算（避免百分比口径在前端各自实现一遍）。</p>
 */
@Getter
@Setter
public class TaskProgressVO {

    @Schema(description = "任务ID")
    private Long id;

    @Schema(description = "任务编号（便于日志/工单对照）")
    private String taskNo;

    @Schema(description = "任务状态（PENDING/RUNNING/SUCCESS/APPROVAL_PENDING/FAILED/REJECTED/CANCELLED）")
    private String status;

    @Schema(description = "状态中文说明（前端可直接展示）")
    private String statusText;

    @Schema(description = "总步骤数")
    private Integer totalSteps;

    @Schema(description = "已完成步骤数")
    private Integer finishedSteps;

    @Schema(description = "进度百分比（0~100，保留一位小数）")
    private Double progressPct;

    @Schema(description = "当前进行中的步骤名（无则空）")
    private String currentStepName;

    @Schema(description = "已耗时（毫秒）")
    private Long elapsedMs;

    @Schema(description = "预计剩余耗时（毫秒；已终态为 0）")
    private Long estimatedRemainingMs;

    @Schema(description = "预计总耗时（毫秒）= 已耗时 + 预计剩余")
    private Long estimatedTotalMs;

    /**
     * 估算依据（诚实标注口径，前端可提示"粗略估算"）：
     * <ul>
     *   <li>{@code HISTORY} —— 用本租户近期同类步骤的真实平均耗时推算（样本数见 samples）</li>
     *   <li>{@code DEFAULT} —— 无历史样本，用缺省单步耗时推算（首次上线/新工具时）</li>
     *   <li>{@code FIXED}  —— 任务已终态，耗时即实际值</li>
     * </ul>
     */
    @Schema(description = "估算依据：HISTORY / DEFAULT / FIXED")
    private String estimateSource;

    @Schema(description = "参与估算的历史样本数（estimateSource=HISTORY 时有效）")
    private Integer samples;

    /**
     * 自主纠错重跑次数（来自 {@code agent_task.correction_count}）。
     *
     * <p><b>为什么进度接口必须带这个字段</b>：R8-3 运行时实测发现，R5 的自校验闸口判定不一致时
     * 会**重跑风险相关步骤**，这些步骤的状态从 SUCCESS 复位 → {@code finishedSteps} 真的减少 →
     * {@code progressPct} 会**回退**（实测 87.5% → 62.5% 再爬回去）。这是"工作被重做"的真实状态，
     * 端点必须如实反映（不能为了让进度条好看而谎报单调递增）；前端据此可显示
     * 「正在重新核验（第 N 次纠错）」而不是让用户以为进度条出了 bug。</p>
     */
    @Schema(description = "自主纠错重跑次数（>0 表示曾重跑风险步骤，进度可能因此回退）")
    private Integer correctionCount;

    @Schema(description = "可读提示（如「第 5/8 步：票据核验」/「已完成」）")
    private String message;
}

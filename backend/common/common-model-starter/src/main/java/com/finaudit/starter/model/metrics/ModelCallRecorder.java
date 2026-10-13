package com.finaudit.starter.model.metrics;

/**
 * 模型调用台账落库 SPI（P3.8 R9-1）。
 *
 * <p><b>为什么是 SPI 而不是直接落库</b>：{@code common-model-starter} 是纯能力 Starter（无 MyBatis、
 * 无数据源依赖），让它在调用模型时顺手写库既污染依赖方向、也会让"模型能力"和"业务台账"耦合。
 * 因此 Starter 只定义契约：谁需要台账谁实现——当前由 agent-core 实现（{@code ModelCallLogRecorder}），
 * 其他服务若不关心成本可完全不实现（{@code ObjectProvider} 缺失即跳过）。</p>
 *
 * <p><b>实现方必须保证不抛异常</b>：台账是旁路，落库失败绝不能影响模型调用本身
 * （失败的调用往往正是最需要记录的那条，所以更要在实现里 try/catch 兜底并告警）。</p>
 */
public interface ModelCallRecorder {

    /** 记录一次模型调用（同步调用；实现方需自行兜底异常） */
    void record(ModelCallRecord call);
}

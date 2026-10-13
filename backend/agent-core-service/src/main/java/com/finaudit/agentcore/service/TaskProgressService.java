package com.finaudit.agentcore.service;

import com.finaudit.agentcore.enums.StepStatus;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.AgentTaskStep;
import com.finaudit.agentcore.pojo.vo.TaskProgressVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务进度与等待时间估算（P3.8 R8-3）。
 *
 * <p><b>解决什么问题</b>：报销审核是"提交后要等"的流程（实测一条 8 步流水线约 10~15 秒，
 * 命中自纠错或人工复核更久），而前端此前只能显示一个转圈——用户不知道"还要等多久、卡在哪一步"。
 * 本服务给出**进度百分比 + 当前步骤 + 预计剩余时间**，让等待可预期。</p>
 *
 * <p><b>估算口径（刻意不做花哨模型）</b>：预计剩余时间 = 未完成步骤逐个累加其**同类步骤的历史平均耗时**：
 * LLM 步骤按 (LLM, 风控/汇总) 分桶，TOOL 步骤按工具编码分桶——数据源就是 R9-2 落库的
 * {@code agent_task_step.duration_ms}（真实墙钟，含 MQ 往返）。没有历史样本时退化为缺省单步耗时，
 * 并在 {@code estimateSource} 里如实标注 {@code DEFAULT}，前端可提示"粗略估算"。</p>
 *
 * <p><b>为什么不把 ETA 做得更"聪明"</b>：流水线耗时主要由模型调用决定（抖动大），
 * 复杂模型（如按 token 数回归）在样本量不足时反而更不准。用统计均值 + 明示依据，
 * 是这个阶段性价比最高、也最容易解释的做法。</p>
 */
@Service
public class TaskProgressService {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressService.class);

    /** 历史样本统计窗口（天）：太短样本不足、太长会被规则/模型变更前的旧数据污染 */
    private static final int BASELINE_WINDOW_DAYS = 30;

    /** 缺省单步耗时（毫秒）：无历史样本时的兜底，取值参考 R9-2 实测（TOOL 均值约 1.3s、LLM 均值约 1.9s） */
    private static final long DEFAULT_TOOL_MS = 1_500L;
    private static final long DEFAULT_LLM_MS = 2_500L;

    /** 步骤类型标识（与 FlowDefinition/编排器同一口径） */
    private static final String STEP_TYPE_LLM = "LLM";

    private final AgentTaskStepService stepService;

    public TaskProgressService(AgentTaskStepService stepService) {
        this.stepService = stepService;
    }

    /**
     * 计算任务进度。
     *
     * @param task 任务实体（调用方已完成存在性与可见性校验）
     * @return 进度 VO
     */
    public TaskProgressVO progress(AgentTask task) {
        List<AgentTaskStep> steps = stepService.listByTask(task.getId());

        TaskProgressVO vo = new TaskProgressVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setStatus(task.getStatus());
        vo.setStatusText(statusText(task.getStatus()));
        // 纠错次数如实透出：进度回退的唯一合法解释（见 TaskProgressVO#correctionCount 注释）
        vo.setCorrectionCount(task.getCorrectionCount() == null ? 0 : task.getCorrectionCount());

        int total = steps.isEmpty()
                ? (task.getTotalSteps() == null ? 0 : task.getTotalSteps())
                : steps.size();
        long finished = steps.stream()
                .filter(s -> StepStatus.SUCCESS.name().equals(s.getStatus()))
                .count();
        vo.setTotalSteps(total);
        vo.setFinishedSteps((int) finished);
        vo.setProgressPct(percent(finished, total));

        // 当前进行中的步骤（RUNNING 优先，否则取第一个未完成步骤）
        AgentTaskStep current = steps.stream()
                .filter(s -> StepStatus.RUNNING.name().equals(s.getStatus()))
                .findFirst()
                .orElseGet(() -> steps.stream()
                        .filter(s -> !StepStatus.SUCCESS.name().equals(s.getStatus()))
                        .findFirst().orElse(null));
        vo.setCurrentStepName(current == null ? null : current.getStepName());

        long elapsed = elapsedMs(task);
        vo.setElapsedMs(elapsed);

        boolean terminal = isTerminal(task.getStatus());
        if (terminal) {
            // 终态：不再估算，耗时就是实际值（进入待审后的人工等待不计入）
            vo.setEstimatedRemainingMs(0L);
            vo.setEstimatedTotalMs(elapsed);
            vo.setEstimateSource("FIXED");
            vo.setSamples(0);
            vo.setMessage("已完成（" + vo.getStatusText() + "）");
            return vo;
        }

        List<AgentTaskStep> pending = steps.stream()
                .filter(s -> !StepStatus.SUCCESS.name().equals(s.getStatus()))
                .toList();
        Map<String, Baseline> baselines = baselines();
        long remaining = 0L;
        int samples = 0;
        boolean allFromHistory = true;
        for (AgentTaskStep s : pending) {
            Baseline b = baselines.get(key(s));
            if (b != null) {
                remaining += b.avgMs();
                samples += b.samples();
            } else {
                remaining += defaultMs(s);
                allFromHistory = false;
            }
        }

        vo.setEstimatedRemainingMs(remaining);
        vo.setEstimatedTotalMs(elapsed + remaining);
        vo.setEstimateSource(allFromHistory && !pending.isEmpty() ? "HISTORY" : "DEFAULT");
        vo.setSamples(samples);
        vo.setMessage(buildMessage(vo, current));
        return vo;
    }

    /** 可读提示：优先「第 N/M 步：步骤名」 */
    private static String buildMessage(TaskProgressVO vo, AgentTaskStep current) {
        if (current != null && current.getStepNo() != null) {
            return "第 " + current.getStepNo() + "/" + vo.getTotalSteps() + " 步：" + current.getStepName();
        }
        if (vo.getTotalSteps() != null && vo.getTotalSteps() > 0 && vo.getFinishedSteps() >= vo.getTotalSteps()) {
            return "步骤已全部执行，正在收尾判定";
        }
        return vo.getStatusText();
    }

    private static double percent(long finished, int total) {
        if (total <= 0) {
            return 0d;
        }
        double pct = finished * 100d / total;
        return Math.round(pct * 10d) / 10d;
    }

    /** 已耗时：终态取落库的实际耗时，运行中按 started_at（缺则 created_at）实时计算 */
    private static long elapsedMs(AgentTask task) {
        if (isTerminal(task.getStatus()) && task.getDurationMs() != null) {
            return task.getDurationMs();
        }
        LocalDateTime start = task.getStartedAt() != null ? task.getStartedAt() : task.getCreatedAt();
        return start == null ? 0L : Math.max(0L, Duration.between(start, LocalDateTime.now()).toMillis());
    }

    /** 任务是否已到终态（终态含人工介入后的状态；进入待审即视为流水线本次执行结束） */
    private static boolean isTerminal(String status) {
        return TaskStatus.SUCCESS.name().equals(status)
                || TaskStatus.FAILED.name().equals(status)
                || TaskStatus.APPROVAL_PENDING.name().equals(status)
                || TaskStatus.REJECTED.name().equals(status)
                || TaskStatus.CANCELLED.name().equals(status);
    }

    private static String statusText(String status) {
        if (status == null) {
            return "未知";
        }
        // 展示文案就地维护（TaskStatus 是状态机语义，不掺展示层文案；前端也不需要为枚举再维护一份中文表）
        return STATUS_TEXT.getOrDefault(status, status);
    }

    private static final Map<String, String> STATUS_TEXT = Map.of(
            TaskStatus.PENDING.name(), "已提交，等待调度",
            TaskStatus.RUNNING.name(), "审核中",
            TaskStatus.SUCCESS.name(), "已通过",
            TaskStatus.FAILED.name(), "审核失败",
            TaskStatus.APPROVAL_PENDING.name(), "待人工复核",
            TaskStatus.REJECTED.name(), "已驳回",
            TaskStatus.CANCELLED.name(), "已作废");

    /** 步骤分桶键：类型 + 工具编码 + 角色（同属 LLM 的风控/汇总耗时差约 30%，必须分开统计） */
    private static String key(AgentTaskStep step) {
        return key(step.getStepType(), step.getToolName(), step.getAgentRole());
    }

    private static String key(Object stepType, Object toolName, Object agentRole) {
        return str(stepType) + "|" + str(toolName) + "|" + str(agentRole);
    }

    private static long defaultMs(AgentTaskStep step) {
        return STEP_TYPE_LLM.equalsIgnoreCase(step.getStepType()) ? DEFAULT_LLM_MS : DEFAULT_TOOL_MS;
    }

    /**
     * 取历史基线：把 XML 查询结果按「类型|工具|角色」索引。
     * <p>分桶键与 {@link #key(AgentTaskStep)} 完全一致（同一函数生成），避免"统计口径"与"取用口径"漂移——
     * 这类键不一致的 bug 表现为"永远命中不到基线、静默退化为缺省值"，很难被发现。</p>
     */
    private Map<String, Baseline> baselines() {
        Map<String, Baseline> map = new HashMap<>();
        try {
            List<Map<String, Object>> rows = stepService.avgDurationByStepKey(
                    LocalDateTime.now().minusDays(BASELINE_WINDOW_DAYS));
            for (Map<String, Object> row : rows) {
                Double avg = dec(row.get("avgMs"));
                if (avg == null || avg <= 0) {
                    continue;
                }
                Integer samples = intOf(row.get("samples"));
                map.put(key(row.get("stepType"), row.get("toolName"), row.get("agentRole")),
                        new Baseline(avg.longValue(), samples == null ? 0 : samples));
            }
        } catch (Exception e) {
            // 基线只是估算依据：查不到就用缺省值，绝不能因为统计查询失败让进度接口报错
            log.warn("步骤耗时基线查询失败，本次预估退化为缺省值: {}", e.getMessage());
        }
        return map;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static Double dec(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单步历史基线 */
    private record Baseline(long avgMs, int samples) {
    }
}

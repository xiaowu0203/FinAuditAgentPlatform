package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.finaudit.agentcore.enums.TaskStatus;
import com.finaudit.agentcore.enums.TaskType;
import com.finaudit.agentcore.pojo.dto.TaskSubmitRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Agent 任务（agent_task）。
 * <p>状态机见 {@code TaskStatus}；JSON 列用 {@link JacksonTypeHandler}。</p>
 */
@Getter
@Setter
@TableName(value = "agent_task", autoResultMap = true)
public class AgentTask {

    @TableId(type = IdType.AUTO)
    @Schema(description = "任务ID")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "任务编号")
    private String taskNo;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "业务类型（REIMBURSEMENT 报销审核 / GENERIC 通用分析；P3 角色化分派依据）")
    private String taskType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "任务入参（JSON）")
    private Map<String, Object> inputParams;

    @Schema(description = "任务状态（PENDING / RUNNING / SUCCESS / FAILED）")
    private String status;

    @Schema(description = "本次执行开始时间（启动/修改重跑时刷新；任务级超时预算的计时起点）")
    private LocalDateTime startedAt;

    @Schema(description = "总步骤数")
    private Integer totalSteps;

    @Schema(description = "已完成步骤数")
    private Integer finishedSteps;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "任务结果（JSON）")
    private Map<String, Object> result;

    @Schema(description = "错误信息")
    private String errorMsg;

    /**
     * 任务总耗时（毫秒，P3.8 R9-2）：从 {@code started_at}（本次执行起点，续跑/重跑会刷新）到终态的墙钟耗时。
     * <p>取"本次执行"而非"创建至今"：重跑任务的创建时间可能早已过去，混在一起会让效率指标失真。</p>
     */
    @Schema(description = "任务耗时（毫秒，本次执行）")
    private Long durationMs;

    /**
     * 自校验纠错次数（P3.8 R5-4）。语义自校验命中矛盾后重跑风控语义步骤时累加，上限见
     * {@code AgentOrchestrator}（默认 1 次）。同时是 P4「工具纠错次数」指标的来源。
     */
    @Schema(description = "自校验纠错次数（命中矛盾重跑风控步骤累加）")
    private Integer correctionCount;

    /**
     * 自校验结果（P3.8 R5-4）：{@code SelfCheckResult} 的 JSON 快照，
     * 含是否通过、命中的矛盾/幻觉清单与断言条数。供任务详情展示「自校验明细」。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    @Schema(description = "语义自校验结果（JSON：是否通过 + 矛盾/幻觉清单）")
    private Map<String, Object> selfCheckResult;

    @Schema(description = "创建人ID")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    /** 更新时间（P3.8 R9-2 修复 R2-10 遗留）：标 fill=INSERT_UPDATE，使 updateById/实体更新能刷新该列，
     * 否则实体里读出的旧值会被写回 SET、抑制 MySQL 的 ON UPDATE CURRENT_TIMESTAMP。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /** 任务号时间格式 */
    private static final DateTimeFormatter TASK_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 由提交请求构造新任务（初始状态 PENDING，步骤数 0，自动生成任务号）。
     *
     * @param createdBy 创建人用户 ID（网关注入的 X-User-Id；报销单提交为申请人 applicantId）
     */
    public static AgentTask from(TaskSubmitRequest request, Long tenantId, Long createdBy) {
        AgentTask task = new AgentTask();
        task.setTenantId(tenantId);
        task.setCreatedBy(createdBy);
        task.setTaskNo(generateTaskNo());
        task.setTitle(request.title());
        task.setTaskType(request.taskType() == null ? TaskType.GENERIC.name() : request.taskType().name());
        task.setInputParams(request.inputParams());
        task.setStatus(TaskStatus.PENDING.name());
        task.setTotalSteps(0);
        task.setFinishedSteps(0);
        return task;
    }

    /**
     * 自校验结果补丁（P3.8 R5-9）：只带主键 + {@code self_check_result}，供
     * {@code mapper.update(patch, wrapper)} 精确更新单个 JSON 列。
     *
     * <p><b>⚠️ 为什么不能用 wrapper 的 {@code set(col, value)} 写 JSON 列</b>：
     * {@code LambdaUpdateWrapper.set} 生成的参数<b>不携带 typeHandler</b>，MyBatis 只能把
     * {@code Map} 当未知对象交给 JDBC 驱动，驱动按 binary 字符集发送字符串，MySQL 5.7 直接拒绝：
     * {@code Data truncation: Cannot create a JSON value from a string with CHARACTER SET 'binary'}。
     * 实体字段带 {@link JacksonTypeHandler}，经实体更新时 MP 渲染
     * {@code #{et.xxx,typeHandler=JacksonTypeHandler}}，序列化为 utf8 字符串后正常入库。</p>
     *
     * <p>本机 MySQL 5.7 + mysql-connector-j 实测：wrapper.set 写 {@code self_check_result}
     * 抛 {@code MysqlDataTruncation}，换成本补丁更新即成功。补丁只带 JSON 列，
     * 避免把整个实体写回造成脏写。</p>
     */
    public static AgentTask selfCheckResultPatch(Long id, Map<String, Object> selfCheckResult) {
        AgentTask patch = new AgentTask();
        patch.setId(id);
        patch.setSelfCheckResult(selfCheckResult);
        return patch;
    }

    /**
     * 任务入参补丁（P3.8 R5-9）：只带主键 + {@code input_params}。
     * <p>同 {@link #selfCheckResultPatch}：JSON 列必须走实体（带 typeHandler）更新。</p>
     */
    public static AgentTask inputParamsPatch(Long id, Map<String, Object> inputParams) {
        AgentTask patch = new AgentTask();
        patch.setId(id);
        patch.setInputParams(inputParams);
        return patch;
    }

    /**
     * 任务号：T + yyyyMMddHHmmss + 4 位随机数。
     * <p>随机段存在同秒碰撞概率，调用方须经
     * {@link com.finaudit.agentcore.support.BizNoInserter#insertWithRetry} 落库以获得换号重试。</p>
     */
    public static String generateTaskNo() {
        return "T" + LocalDateTime.now().format(TASK_NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /**
     * 作废回写（提交人撤回 / 财务同意撤销）：置 CANCELLED 并记录原因，供前端展示与 resume 拒绝。
     */
    public void applyCancelled(String reason) {
        this.status = TaskStatus.CANCELLED.name();
        this.errorMsg = reason;
    }
}

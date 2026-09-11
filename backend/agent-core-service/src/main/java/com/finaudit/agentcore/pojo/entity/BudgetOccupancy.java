package com.finaudit.agentcore.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.finaudit.agentcore.enums.OccupancyStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预算占用记账（budget_occupancy，P3.8 R1）。
 *
 * <p>一张报销单对应一条记录（`uk(tenant_id, reimb_id)`），记录其预算占用/释放的**当前状态**与发生次数：
 * <ul>
 *   <li>{@code status=OCCUPIED}：该单金额计入对应部门+周期的 {@code budget.used_amount}</li>
 *   <li>{@code status=RELEASED}：占用已回退（撤回/撤销/驳回/终止/重跑失败）</li>
 * </ul>
 * 配平公式（对账用）：{@code SUM(amount WHERE status='OCCUPIED') == budget.used_amount}。
 * <b>注意只算当前占用态</b>：行转 RELEASED 时 {@code used_amount} 已减去该行金额，
 * 再按「占用 − 释放」计算会把释放额扣减两次。</p>
 *
 * <p>为什么需要这张表：{@code budget.used_amount} 此前全仓无写入点（只做只读预检），
 * 且审批链路存在「通过 → 撤销 → 作废」等回退路径，必须有独立记账才能既防重复占用又可对账核验。</p>
 */
@Getter
@Setter
@TableName("budget_occupancy")
public class BudgetOccupancy {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "报销单ID（业务幂等键，一单一记录）")
    private Long reimbId;

    @Schema(description = "关联审核任务ID（追溯用）")
    private Long taskId;

    @Schema(description = "部门ID（budget 权威关联键）")
    private Long deptId;

    @Schema(description = "预算周期 YYYY-MM")
    private String period;

    @Schema(description = "占用金额")
    private BigDecimal amount;

    @Schema(description = "占用状态: OCCUPIED 已占用 / RELEASED 已释放")
    private String status;

    @Schema(description = "累计占用次数")
    private Integer occupyCount;

    @Schema(description = "累计释放次数")
    private Integer releaseCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @TableLogic
    @Schema(description = "逻辑删除标记（0 未删 / 1 已删）")
    private Integer deleted;

    /**
     * 首次占用：新建一条 OCCUPIED 记账。
     * <p>tenant_id 列不在此显式赋值——由多租户拦截器自动填充（同 {@code AgentTaskStepMapper.insertBatch}）。</p>
     *
     * @param reimbId  报销单ID
     * @param taskId   审核任务ID
     * @param deptId   部门ID
     * @param period   预算周期 YYYY-MM
     * @param amount   占用金额
     */
    public static BudgetOccupancy occupied(Long reimbId, Long taskId, Long deptId, String period, BigDecimal amount) {
        BudgetOccupancy o = new BudgetOccupancy();
        o.setReimbId(reimbId);
        o.setTaskId(taskId);
        o.setDeptId(deptId);
        o.setPeriod(period);
        o.setAmount(amount);
        o.setStatus(OccupancyStatus.OCCUPIED.name());
        o.setOccupyCount(1);
        o.setReleaseCount(0);
        return o;
    }

    /**
     * 标记为已释放：状态转 RELEASED 并累加释放次数。
     * <p>仅状态流转，不回写金额（金额即该单申报总额，释放的是同一笔）。幂等：已 RELEASED 时不做任何变更，
     * 由调用方（Service）据此跳过对 {@code budget.used_amount} 的二次扣减。</p>
     *
     * @return true=本次发生了状态迁移（应扣减 used_amount）；false=已是释放态（应跳过）
     */
    public boolean applyRelease() {
        if (OccupancyStatus.RELEASED.name().equals(this.status)) {
            return false;
        }
        this.status = OccupancyStatus.RELEASED.name();
        this.releaseCount = (this.releaseCount == null ? 0 : this.releaseCount) + 1;
        return true;
    }

    /**
     * 重新占用（提交人 resubmit 重跑再次进入审批/自动通过）：状态转回 OCCUPIED 并累加占用次数。
     * <p>只有 RELEASED 态才需要重新占用；已 OCCUPIED 时返回 false（幂等，避免重复累加 used_amount）。</p>
     *
     * @return true=本次发生了状态迁移（应增加 used_amount）；false=已是占用态（应跳过）
     */
    public boolean applyReoccupy() {
        if (OccupancyStatus.OCCUPIED.name().equals(this.status)) {
            return false;
        }
        this.status = OccupancyStatus.OCCUPIED.name();
        this.occupyCount = (this.occupyCount == null ? 0 : this.occupyCount) + 1;
        return true;
    }

    /** 当前是否处于占用态。 */
    public boolean isOccupied() {
        return OccupancyStatus.OCCUPIED.name().equals(this.status);
    }
}

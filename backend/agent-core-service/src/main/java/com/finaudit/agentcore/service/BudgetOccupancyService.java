package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.enums.OccupancyStatus;
import com.finaudit.agentcore.mapper.BudgetMapper;
import com.finaudit.agentcore.mapper.BudgetOccupancyMapper;
import com.finaudit.agentcore.pojo.entity.AgentTask;
import com.finaudit.agentcore.pojo.entity.Budget;
import com.finaudit.agentcore.pojo.entity.BudgetOccupancy;
import com.finaudit.agentcore.pojo.entity.ExpenseReimbursement;
import com.finaudit.starter.web.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 预算占用/释放服务（P3.8 R1）——{@code budget} 与 {@code budget_occupancy} 的**唯一写入点**。
 *
 * <h3>为什么需要它（业务走查 B-1 / B-2）</h3>
 * {@code budget.used_amount} 此前全仓无写入点：注释写着"审核通过后累加（P3 审批流）"但从未实现，
 * 系统只做只读预检、从不扣减。后果是同一部门同月多笔报销全部报"预算充足"，系统一次都不拦。
 *
 * <h3>并发正确性（本类的核心约束）</h3>
 * 占用必须走 {@link BudgetMapper#occupy} 的**单条原子 UPDATE**（超支条件写在 WHERE 里，由数据库行锁串行化），
 * 禁止"先查再算再写"——那样并发下两笔单据会读到同一个 {@code used_amount}、各自判定充足，最终共同超支。
 * 前置的 {@link BudgetMapper#countBudgetRow} 只用于区分"预算未配置"与"预算不足"，不参与超支判定。
 *
 * <h3>幂等</h3>
 * 以 {@code budget_occupancy} 的 {@code uk(tenant_id, reimb_id)} + 状态机保证：
 * 同一报销单重复占用/重复释放都不会二次改动 {@code used_amount}。
 *
 * <h3>职责边界</h3>
 * 本类不感知任务/工单状态机，只回答"这笔钱占/放了没有"；调用时机由 {@code AgentOrchestrator}
 * （AUTO_PASS 收尾）与 {@code AuditTicketService}（审批通过 / 各终态释放）决定。
 */
@Service
public class BudgetOccupancyService {

    private static final Logger log = LoggerFactory.getLogger(BudgetOccupancyService.class);

    /** 预算周期格式 YYYY-MM（与 budget.period 一致） */
    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final BudgetMapper budgetMapper;
    private final BudgetOccupancyMapper occupancyMapper;
    private final ReimbursementService reimbursementService;

    public BudgetOccupancyService(BudgetMapper budgetMapper, BudgetOccupancyMapper occupancyMapper,
                                  ReimbursementService reimbursementService) {
        this.budgetMapper = budgetMapper;
        this.occupancyMapper = occupancyMapper;
        this.reimbursementService = reimbursementService;
    }

    /** 占用结果：区分"已占用/本次占用"与"未配置预算（跳过）"。 */
    public enum OccupyResult {
        /** 本次真正发生了占用（used_amount 已增加） */
        OCCUPIED,
        /** 此前已处于占用态，本次为幂等空操作（used_amount 未变） */
        ALREADY_OCCUPIED,
        /** 该部门+周期未配置预算：不阻断（保持历史宽松语义），仅告警 */
        NOT_CONFIGURED
    }

    /**
     * 占用某报销单的部门预算（按报销日期推导预算周期）。
     *
     * @param reimbId 报销单ID（幂等键）
     * @param taskId  关联任务ID（追溯用，可为 null）
     * @return 占用结果
     * @throws BizException 预算不足（原子 UPDATE 影响行数=0 且预算行存在）
     */
    @Transactional
    public OccupyResult occupy(Long reimbId, Long taskId) {
        ExpenseReimbursement reimb = reimbursementService.getByReimbId(reimbId);
        if (reimb == null) {
            log.warn("预算占用跳过：报销单不存在 reimbId={}", reimbId);
            return OccupyResult.NOT_CONFIGURED;
        }
        return occupy(reimb.getTenantId(), reimbId, taskId, reimb.getDeptId(),
                periodOf(reimb.getClaimDate()), reimb.getTotalAmount());
    }

    /**
     * 按任务占用预算（编排器 AUTO_PASS 收尾用）：从 {@code task.inputParams.reimbId} 取报销单。
     *
     * @param task 待收尾的报销审核任务
     * @return 占用结果（无 reimbId / 缺部门时返回 NOT_CONFIGURED，不阻断）
     */
    @Transactional
    public OccupyResult occupyByTask(AgentTask task) {
        Long reimbId = reimbIdOf(task);
        if (reimbId == null) {
            log.info("预算占用跳过：任务 {} 入参无 reimbId", task.getId());
            return OccupyResult.NOT_CONFIGURED;
        }
        return occupy(reimbId, task.getId());
    }

    /**
     * 预算是否可占用（**只读试算，不写库、不抛异常**）——供 AUTO_PASS 前判定是否转人工复核。
     *
     * <p>为什么不直接占用再捕获异常：占用发生在事务内，捕获异常后若还要继续写库
     * （如 {@code enterApproval} 建工单），PostgreSQL 下事务已被标记 aborted（25P02）导致后续语句全失败，
     * 且依赖 Spring 对回滚异常的判定过于脆弱。故把"预算不足"变成一次纯读预检。</p>
     *
     * <p>语义与 {@link #occupy} 一致：
     * <ul>
     *   <li>已占用 / 未配置预算 / 缺参数 → true（放行；占用侧同样是幂等或跳过）</li>
     *   <li>预算行存在但剩余额度不足 → false（转人工复核）</li>
     * </ul>
     * 注意这里存在 TOCTOU 窗口：预检通过后并发可能抢走额度，届时 {@link #occupy} 会抛错并由外层事务回滚。
     * 这是有意的取舍——预检只用于减少"自动放行后又失败"的概率，最终正确性仍由原子 UPDATE 保证。</p>
     *
     * @param task  待收尾任务
     * @param result 任务结果（预留：可在其中补充预算相关信息）
     * @return true=可以占用
     */
    public boolean canOccupy(AgentTask task, Map<String, Object> result) {
        Long reimbId = reimbIdOf(task);
        if (reimbId == null) {
            return true;
        }
        ExpenseReimbursement reimb = reimbursementService.getByReimbId(reimbId);
        if (reimb == null || reimb.getDeptId() == null || reimb.getTotalAmount() == null) {
            return true; // 占用侧同样会跳过
        }
        BudgetOccupancy existing = findByReimbId(reimbId);
        if (existing != null && existing.isOccupied()) {
            return true; // 已占用，幂等
        }
        String period = periodOf(reimb.getClaimDate());
        Budget budget = budgetMapper.findBudgetRow(reimb.getTenantId(), reimb.getDeptId(), period);
        if (budget == null) {
            return true; // 未配置预算：不阻断（占用侧同样跳过并告警）
        }
        BigDecimal remaining = budget.getTotalBudget().subtract(
                budget.getUsedAmount() == null ? BigDecimal.ZERO : budget.getUsedAmount());
        return remaining.compareTo(reimb.getTotalAmount()) >= 0;
    }

    /** 从任务入参取报销单ID（不存在返回 null）。 */
    private static Long reimbIdOf(AgentTask task) {
        if (task == null || task.getInputParams() == null) {
            return null;
        }
        Object v = task.getInputParams().get("reimbId");
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 占用指定部门+周期的预算（显式参数版本，便于单测与重跑场景）。
     *
     * @param tenantId 租户ID
     * @param reimbId  报销单ID（幂等键）
     * @param taskId   关联任务ID
     * @param deptId   部门ID（为 null 视为未配置，跳过）
     * @param period   预算周期 YYYY-MM
     * @param amount   占用金额
     * @return 占用结果
     */
    @Transactional
    public OccupyResult occupy(Long tenantId, Long reimbId, Long taskId, Long deptId,
                               String period, BigDecimal amount) {
        if (deptId == null || period == null || amount == null) {
            log.info("预算占用跳过（缺部门/周期/金额）：reimbId={}, deptId={}, period={}", reimbId, deptId, period);
            return OccupyResult.NOT_CONFIGURED;
        }

        BudgetOccupancy existing = findByReimbId(reimbId);
        if (existing != null && existing.isOccupied()) {
            // 已占用：不重复累加。补写 taskId（首占用时可能还没有任务ID关联）
            if (taskId != null && !Objects.equals(existing.getTaskId(), taskId)) {
                existing.setTaskId(taskId);
                occupancyMapper.updateById(existing);
            }
            return OccupyResult.ALREADY_OCCUPIED;
        }

        // 预算行是否存在：决定"未配置（跳过）"还是"不足（拦死）"
        if (budgetMapper.findBudgetRow(tenantId, deptId, period) == null) {
            log.warn("预算占用跳过：部门 {} 周期 {} 未配置预算（不阻断，仅告警）", deptId, period);
            return OccupyResult.NOT_CONFIGURED;
        }

        // 重跑场景：该单此前释放过 → 先用新金额重新占用，成功后再落状态与金额
        boolean reoccupy = existing != null;

        if (budgetMapper.occupy(tenantId, deptId, period, amount) == 0) {
            // 行存在但加总超总额 → 预算不足，拦截。
            // 说明：即使金额不足也**不在此处转 NEED_REVIEW**——「提交时预算不足」应由
            // budget_query 工具在流水线内命中 exceedsBudget 并触发复核，此处只做最终兜底拦截
            // （正常流程走不到；走到则抛错让事务回滚，避免把预算"占一半"）。
            throw new BizException(String.format(
                    "部门预算不足，无法占用：reimbId=%d, deptId=%d, period=%s, 本次申请=%s",
                    reimbId, deptId, period, amount));
        }

        if (reoccupy) {
            // 金额可能因 resubmit 改动，需与本次实际占用金额对齐
            existing.setAmount(amount);
            existing.setDeptId(deptId);
            existing.setPeriod(period);
            existing.setTaskId(taskId);
            existing.applyReoccupy();
            occupancyMapper.updateById(existing);
        } else {
            occupancyMapper.insert(BudgetOccupancy.occupied(reimbId, taskId, deptId, period, amount));
        }
        log.info("预算占用成功：reimbId={}, deptId={}, period={}, amount={}", reimbId, deptId, period, amount);
        return OccupyResult.OCCUPIED;
    }

    /**
     * 释放某报销单占用的预算（幂等）。
     * <p>未占用/无记账/已释放 → 空操作返回 false，不会二次扣减 {@code used_amount}。</p>
     *
     * @param reimbId 报销单ID
     * @return true=本次真正发生了释放
     */
    @Transactional
    public boolean release(Long reimbId) {
        BudgetOccupancy record = findByReimbId(reimbId);
        if (record == null) {
            log.info("预算释放跳过：无占用记账 reimbId={}", reimbId);
            return false;
        }
        if (!record.applyRelease()) {
            log.info("预算释放跳过：已是释放态 reimbId={}", reimbId);
            return false;
        }
        budgetMapper.release(record.getTenantId(), record.getDeptId(), record.getPeriod(), record.getAmount());
        occupancyMapper.updateById(record);
        log.info("预算释放成功：reimbId={}, deptId={}, period={}, amount={}",
                reimbId, record.getDeptId(), record.getPeriod(), record.getAmount());
        return true;
    }

    /**
     * 占用-释放配平对账：{@code SUM(amount WHERE status='OCCUPIED')} 应等于 {@code budget.used_amount}。
     * <p>供运维/演练核验（预算被永久吃掉是 R1 的主要风险），故提供可独立调用的核对口径。</p>
     *
     * <p>⚠️ 只累加<b>当前占用态</b>的行，RELEASED 行必须<b>整条跳过、不能再减</b>：
     * 记账表是「一行一单 + 状态流转」（OCCUPIED ⇄ RELEASED，见 {@link OccupancyStatus}），
     * 一行转 RELEASED 时 {@code used_amount} 已减去该行金额，该行随即表示「不再计入」。
     * 若再按 {@code Σ(OCCUPIED) - Σ(RELEASED)} 计算，等于把已释放金额<b>扣减两次</b>：
     * 实测「占 142 → 占 7005 → 撤销释放 7005」一轮，DB 的 used_amount=142 正确，
     * 而该式算出 142-7005=-6863。</p>
     *
     * @param deptId 部门ID
     * @param period 预算周期
     * @return 当前占用净额（仅统计 OCCUPIED 行）
     */
    public BigDecimal reconciledNet(Long deptId, String period) {
        List<BudgetOccupancy> rows = occupancyMapper.selectList(new LambdaQueryWrapper<BudgetOccupancy>()
                .eq(BudgetOccupancy::getDeptId, deptId)
                .eq(BudgetOccupancy::getPeriod, period));
        BigDecimal occupied = BigDecimal.ZERO;
        for (BudgetOccupancy r : rows) {
            if (r.isOccupied()) {
                occupied = occupied.add(r.getAmount());
            }
        }
        return occupied;
    }

    /** 按报销单查占用记账（租户条件由多租户拦截器附加）。 */
    private BudgetOccupancy findByReimbId(Long reimbId) {
        return occupancyMapper.selectOne(new LambdaQueryWrapper<BudgetOccupancy>()
                .eq(BudgetOccupancy::getReimbId, reimbId)
                .last("limit 1"));
    }

    /** 报销日期 → 预算周期 YYYY-MM；日期缺失回退当前月。 */
    public static String periodOf(LocalDate claimDate) {
        return (claimDate == null ? LocalDate.now() : claimDate).format(PERIOD_FMT);
    }
}

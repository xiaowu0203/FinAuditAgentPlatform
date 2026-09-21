package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.mapper.ModelCallLogMapper;
import com.finaudit.agentcore.pojo.entity.ModelCallLog;
import com.finaudit.starter.model.metrics.ModelCallRecord;
import com.finaudit.starter.model.metrics.ModelCallRecorder;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型调用台账服务（P3.8 R9-1）：实现 Starter 的 {@link ModelCallRecorder} SPI，把每次模型调用落一行。
 *
 * <p><b>为什么落库必须自己兜底</b>：台账是**旁路**。落库失败（DB 抖动、列超长）绝不能把异常抛回
 * 模型工厂——那会让一次成功的模型调用被记账失败连累成调用失败，属于"用错误的方式失败"。
 * 故本类全程 try/catch，失败只告警。</p>
 *
 * <p><b>租户列的处理</b>：多租户拦截器会在 INSERT 时自动补 {@code tenant_id}（取线程上下文）。
 * 若上下文缺失（直调/测试），拦截器会跳过，此时用记录里带的 tenantId 兜底，
 * 保证台账始终有租户归属（否则统计口径会漏掉这批调用）。</p>
 */
@Service
public class ModelCallLogService implements ModelCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLogService.class);

    /** 兜底租户：无上下文且记录也未带租户时使用（与 TenantContextHolder.DEFAULT_TENANT_ID 同口径） */
    private static final Long FALLBACK_TENANT_ID = TenantContextHolder.DEFAULT_TENANT_ID;

    private final ModelCallLogMapper modelCallLogMapper;

    public ModelCallLogService(ModelCallLogMapper modelCallLogMapper) {
        this.modelCallLogMapper = modelCallLogMapper;
    }

    @Override
    public void record(ModelCallRecord call) {
        if (call == null) {
            return;
        }
        try {
            ModelCallLog entity = ModelCallLog.from(call);
            if (entity.getTenantId() == null) {
                Long ctxTenant = TenantContextHolder.getTenantId();
                entity.setTenantId(ctxTenant != null ? ctxTenant : FALLBACK_TENANT_ID);
            }
            modelCallLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[model] 调用台账落库失败（不影响模型调用）: modelType={}, taskId={}, stepId={}, err={}",
                    call.modelType(), call.taskId(), call.stepId(), e.getMessage());
        }
    }

    /** 按任务查台账（任务详情/排查用；多租户拦截器自动限租户） */
    public List<ModelCallLog> listByTask(Long taskId) {
        return modelCallLogMapper.selectList(new LambdaQueryWrapper<ModelCallLog>()
                .eq(ModelCallLog::getTaskId, taskId)
                .orderByAsc(ModelCallLog::getId));
    }

    /** 按步骤查台账（一次步骤可能多行：结构化解析重试、故障切换） */
    public List<ModelCallLog> listByStep(Long stepId) {
        return modelCallLogMapper.selectList(new LambdaQueryWrapper<ModelCallLog>()
                .eq(ModelCallLog::getStepId, stepId)
                .orderByAsc(ModelCallLog::getId));
    }

    /**
     * 按时间窗口聚合 Token 用量（P4 成本指标数据源；返回单行：calls/failed/totalTokens/promptTokens/completionTokens/fallbackCalls）。
     * <p>用 {@code selectMaps} 直接取聚合结果，避免为一个统计接口引入自定义 XML。</p>
     */
    public java.util.Map<String, Object> summarize(LocalDateTime from, LocalDateTime to) {
        List<java.util.Map<String, Object>> rows = modelCallLogMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ModelCallLog>()
                        .select("COUNT(*) AS calls",
                                "SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed",
                                "IFNULL(SUM(total_tokens), 0) AS totalTokens",
                                "IFNULL(SUM(prompt_tokens), 0) AS promptTokens",
                                "IFNULL(SUM(completion_tokens), 0) AS completionTokens",
                                "IFNULL(SUM(CASE WHEN fallback_used = 1 THEN 1 ELSE 0 END), 0) AS fallbackCalls")
                        .ge(from != null, "created_at", from)
                        .le(to != null, "created_at", to));
        return rows.isEmpty() ? java.util.Map.of() : rows.get(0);
    }
}

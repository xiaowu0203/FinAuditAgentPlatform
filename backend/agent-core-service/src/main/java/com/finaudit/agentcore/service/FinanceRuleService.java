package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.finaudit.agentcore.mapper.FinanceRuleMapper;
import com.finaudit.agentcore.pojo.entity.FinanceRule;
import com.finaudit.starter.web.feign.dto.RuleCheckItem;
import com.finaudit.starter.web.feign.dto.RuleCheckRequest;
import com.finaudit.starter.web.feign.dto.RuleCheckVO;
import com.finaudit.starter.web.feign.dto.RuleHitVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 财务规则校验服务
 * 承载P2/P3财务制度规则读取、校验核心逻辑（ P2b 直查 DB（规则量小）；P2c 落地 Nacos 动态刷新 + 本地缓存 TTL 后迁缓存。）；
 * 支持4类规则：大额限额、报销时效、差旅标准、补贴上限；
 * 自动按租户隔离规则数据，统一解析JSON规则配置，批量校验报销单据并输出超标命中明细；
 * 差旅/补贴规则当前阶段缺少明细字段暂跳过，预留后续扩展逻辑。
 */
@Service
public class FinanceRuleService {

    private static final Logger log = LoggerFactory.getLogger(FinanceRuleService.class);

    /** 规则类型 */
    // 报销总额大额限额
    private static final String TYPE_AMOUNT_LIMIT = "AMOUNT_LIMIT";
    // 报销时效期限
    private static final String TYPE_REIMBURSE_EXPIRE = "REIMBURSE_EXPIRE";
    // 差旅住宿/交通标准
    private static final String TYPE_TRAVEL_STANDARD = "TRAVEL_STANDARD";
    // 各类补贴单日上限
    private static final String TYPE_SUBSIDY_LIMIT = "SUBSIDY_LIMIT";

    private final FinanceRuleMapper ruleMapper;

    public FinanceRuleService(FinanceRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /**
     * 查询当前租户所有已启用的财务规则（租户隔离由多租户拦截器注入；P2b 直查）
     * 多租户拦截器自动拼接tenant_id条件，仅返回启用状态（enabled=1）规则
     * @param tenantId 租户ID
     * @return 租户生效规则集合
     */
    public List<FinanceRule> listEnabled(Long tenantId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<FinanceRule>()
                .eq(FinanceRule::getTenantId, tenantId)
                .eq(FinanceRule::getEnabled, 1));
    }

    /**
     * 报销单全规则校验入口
     * 遍历租户全部生效规则，逐条评估是否命中超标，汇总所有命中项并标记整体是否需要人工复核
     * 规则说明：
     * <ul>
     * <li>AMOUNT_LIMIT：申报总额 > threshold → 命中（超标）</li>
     * <li>REIMBURSE_EXPIRE：明细发生日期早于 claimDate - maxDays → 命中（超标）</li>
     * <li>TRAVEL_STANDARD / SUBSIDY_LIMIT：入参缺少城市/住宿天数等字段，本阶段记日志跳过（数据不足以评估）</li>
     * </ul>
     * @param tenantId 租户ID
     * @param request  校验入参
     * @return 命中规则列表 + 超标标记
     */
    public RuleCheckVO check(Long tenantId, RuleCheckRequest request) {
        // 根据租户ID查询开启的规则列表
        List<FinanceRule> rules = listEnabled(tenantId);
        // 无生效规则直接返回空结果
        if (rules.isEmpty()) {
            return RuleCheckVO.empty();
        }
        List<RuleHitVO> hits = new ArrayList<>();
        // 逐条执行规则评估，命中则加入集合
        for (FinanceRule rule : rules) {
            evaluate(rule, request).ifPresent(hits::add);
        }
        // 任意一条规则超标，则整体标记为需人工复核
        boolean overLimit = hits.stream().anyMatch(RuleHitVO::overLimit);
        return new RuleCheckVO(hits, overLimit);
    }

    /**
     * 单条规则评估分发器
     * 根据规则类型分发至对应校验逻辑；不支持/数据不足的规则返回空Optional（未命中）
     * @param rule 单条财务规则实体
     * @param request 报销校验入参
     * @return 命中信息Optional，空代表未命中/无法校验
     */
    private Optional<RuleHitVO> evaluate(FinanceRule rule, RuleCheckRequest request) {
        return switch (rule.getRuleType()) {
            case TYPE_AMOUNT_LIMIT -> evalAmountLimit(rule, request);
            case TYPE_REIMBURSE_EXPIRE -> evalReimburseExpire(rule, request);
            case TYPE_TRAVEL_STANDARD, TYPE_SUBSIDY_LIMIT -> {
                log.info("规则[{}]类型[{}]当前入参不足以评估（缺城市/标准字段），跳过", rule.getRuleCode(), rule.getRuleType());
                yield Optional.empty();
            }
            default -> {
                log.warn("未知规则类型[{}]，跳过", rule.getRuleType());
                yield Optional.empty();
            }
        };
    }

    /**
     * 大额限额规则校验
     * 对比报销总金额与配置阈值，总额更大则判定超标并生成命中说明
     * @param rule 大额限额规则
     * @param request 报销校验入参
     * @return 命中VO / 空（未超标/配置缺失）
     */
    private Optional<RuleHitVO> evalAmountLimit(FinanceRule rule, RuleCheckRequest request) {
        BigDecimal threshold = configDecimal(rule, "threshold");
        // 阈值或总额为空，无法校验
        if (threshold == null || request.totalAmount() == null) {
            return Optional.empty();
        }
        // 申报总额 > 阈值，判定大额超标
        if (request.totalAmount().compareTo(threshold) > 0) {
            return Optional.of(new RuleHitVO(rule.getRuleCode(), rule.getRuleName(), rule.getRuleType(),
                    "申报总额 " + request.totalAmount().stripTrailingZeros().toPlainString()
                            + " 超过大额限额 " + threshold.stripTrailingZeros().toPlainString() + "，需人工复核",
                    true));
        }
        return Optional.empty();
    }

    /**
     * 报销时效规则校验
     * 取报销日期往前maxDays天作为截止线，任意明细日期早于截止线即判定超时效
     * @param rule 报销时效规则
     * @param request 报销校验入参
     * @return 命中VO / 空（无明细/日期缺失/全部明细在时效内）
     */
    private Optional<RuleHitVO> evalReimburseExpire(FinanceRule rule, RuleCheckRequest request) {
        BigDecimal maxDays = configDecimal(rule, "maxDays");
        LocalDate base = parseDate(request.claimDate());
        // 时效天数、报销日期、明细任一缺失，无法校验
        if (maxDays == null || base == null || request.items() == null || request.items().isEmpty()) {
            return Optional.empty();
        }
        // 计算报销最晚有效日期
        LocalDate cutoff = base.minusDays(maxDays.longValue());
        // 遍历明细，只要一条超期直接返回命中
        for (RuleCheckItem item : request.items()) {
            LocalDate itemDate = parseDate(item.date());
            if (itemDate != null && itemDate.isBefore(cutoff)) {
                return Optional.of(new RuleHitVO(rule.getRuleCode(), rule.getRuleName(), rule.getRuleType(),
                        "明细[" + (item.name() == null ? item.date() : item.name()) + "]发生日期 " + itemDate
                                + " 早于报销日 " + base + " 前 " + maxDays.stripTrailingZeros().toPlainString() + " 天，疑似超时效",
                        true));
            }
        }
        return Optional.empty();
    }

    /**
     * 从规则JSON配置中读取数值，统一转为BigDecimal
     * 兼容Integer/Double/字符串多种存储类型，解析失败返回null并打印警告日志
     * @param rule 规则实体（内含rule_config JSON Map）
     * @param key 配置字段key（threshold/maxDays）
     * @return 标准化BigDecimal，配置缺失/解析失败返回null
     */
    private static BigDecimal configDecimal(FinanceRule rule, String key) {
        Map<String, Object> config = rule.getRuleConfig();
        if (config == null || config.get(key) == null) {
            return null;
        }
        Object v = config.get(key);
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            log.warn("规则[{}]配置字段[{}]解析失败：{}", rule.getRuleCode(), key, v);
            return null;
        }
    }

    /**
     * 解析YYYY-MM-DD格式日期字符串
     * 空文本、格式非法均返回null，不抛出异常阻断校验流程
     * @param text 日期字符串
     * @return LocalDate / null
     */
    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

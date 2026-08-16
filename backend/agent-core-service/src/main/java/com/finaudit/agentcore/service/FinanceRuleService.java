package com.finaudit.agentcore.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.finaudit.agentcore.config.NacosConfigProperties;
import com.finaudit.agentcore.mapper.FinanceRuleMapper;
import com.finaudit.agentcore.pojo.dto.RuleSaveRequest;
import com.finaudit.agentcore.pojo.entity.FinanceRule;
import com.finaudit.agentcore.support.TenantNacosConfigHelper;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.dto.RuleCheckItem;
import com.finaudit.starter.web.feign.dto.RuleCheckRequest;
import com.finaudit.starter.web.feign.dto.RuleCheckVO;
import com.finaudit.starter.web.feign.dto.RuleHitVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 财务规则校验服务
 * 承载P2/P3财务制度规则读取、校验核心逻辑；P2c 起评估规则源为「Nacos 已发布快照」，
 * 经 {@link TenantNacosConfigHelper} 监听 + 本地缓存 TTL 即时生效，Nacos 无配置时降级 DB 直查；
 * 支持4类规则：大额限额、报销时效、差旅标准、补贴上限；
 * 自动按租户隔离规则数据，统一解析JSON规则配置，批量校验报销单据并输出超标命中明细。
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
    private final TenantNacosConfigHelper tenantNacosConfigHelper;
    private final NacosConfigProperties nacosConfigProperties;

    public FinanceRuleService(FinanceRuleMapper ruleMapper,
                              TenantNacosConfigHelper tenantNacosConfigHelper,
                              NacosConfigProperties nacosConfigProperties) {
        this.ruleMapper = ruleMapper;
        this.tenantNacosConfigHelper = tenantNacosConfigHelper;
        this.nacosConfigProperties = nacosConfigProperties;
    }

    // 配置管理 CRUD

    /**
     * 当前租户全部规则（含草稿与已发布），供配置管理列表。租户隔离由多租户拦截器自动拼接。
     */
    public List<FinanceRule> listAll(Long tenantId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<FinanceRule>()
                .eq(FinanceRule::getTenantId, tenantId)
                .orderByAsc(FinanceRule::getId));
    }

    /**
     * 新增规则（初始草稿 published=0，需发布才生效）。
     */
    public FinanceRule save(RuleSaveRequest request, Long tenantId) {
        // 同租户同类型唯一校验（业务层拦截给友好提示；SQL 唯一索引 uk_rule_type 兜底并发/绕过）
        ensureRuleTypeUnique(tenantId, request.ruleType(), null);
        // 类型转换
        FinanceRule rule = FinanceRule.from(request, tenantId);
        ruleMapper.insert(rule);
        return rule;
    }

    /**
     * 修改规则：变更即草稿（published=0，脱离生效集，需重新发布才生效）。
     */
    public FinanceRule update(Long id, RuleSaveRequest request, Long tenantId) {
        // 根据id查询是否存在记录
        FinanceRule rule = mustLoad(id);
        // 同租户同类型唯一校验（按改后 ruleType 校验并排除自身；update 允许改类型，改后仍需唯一）
        ensureRuleTypeUnique(tenantId, request.ruleType(), id);
        // 类型转换
        rule.apply(request);
        // 设置为未发布状态（需重新进行状态更新）
        rule.setPublished(0);
        ruleMapper.updateById(rule);
        return rule;
    }

    /**
     * 启停规则：翻转 enabled，变更同样视为草稿需重新发布。
     */
    public FinanceRule toggle(Long id, Long tenantId) {
        // 根据id查询是否存在记录
        FinanceRule rule = mustLoad(id);
        rule.setEnabled(rule.getEnabled() != null && rule.getEnabled() == 1 ? 0 : 1);
        // 启用/禁用之后均需要重新发布
        rule.setPublished(0);
        ruleMapper.updateById(rule);
        return rule;
    }

    /**
     * 发布指定财务规则，生成租户全量生效快照推送至Nacos
     * 核心设计：DB为权威数据源，Nacos仅存放已发布规则只读快照，单向同步不回写DB
     * 业务语义：发布操作仅将当前规则标记为已发布，同时聚合租户所有已发布规则生成完整快照推送配置中心
     * 事务/异常说明：
     * 1. 先推送Nacos，推送失败直接抛业务异常，DB数据不做任何变更，避免库与配置中心数据不一致
     * 2. Nacos推送成功后，再更新数据库本条规则的发布状态与版本号
     * 3. 版本一致性：快照内目标规则版本即自增后版本（与落库同值），避免「Nacos 快照版本落后 DB 1」漂移
     * @param id 待发布规则主键ID
     * @param tenantId 操作所属租户ID，用于租户配置隔离
     * @return 更新后的已发布规则实体
     * @throws BizException Nacos配置推送失败、规则不存在时抛出
     */
    public FinanceRule publish(Long id, Long tenantId) {
        // 1. 根据ID加载待发布规则，不存在则抛出异常
        FinanceRule rule = mustLoad(id);
        // 计算本次发布的版本号
        String newVersion = nextVersion(rule.getVersion());

        // 2. 查询当前租户全部财务规则（草稿+已发布全量）
        List<FinanceRule> all = listAll(tenantId);

        /**
         * 3. 构建租户生效快照集合：
         *      过滤条件：原有已发布规则 || 当前待发布规则
         *      对本次待发布规则强制标记published=1，统一纳入生效快照
         */
        List<FinanceRule> snapshot = all.stream()
                .filter(r -> r.getPublished() != null && r.getPublished() == 1 || r.getId().equals(id))
                .map(r -> {
                    if (r.getId().equals(id)) {
                        r.setPublished(1);
                        r.setVersion(newVersion);
                    }
                    return r;
                })
                .toList();

        /**
         * 4. 推送租户全量生效快照至Nacos配置中心
         *      若推送过程发生网络/鉴权/序列化异常，直接抛出BizException，库数据不更新，防止数据割裂
         */
        tenantNacosConfigHelper.publishTenantConfig(tenantId, nacosConfigProperties.getRuleDataId(), snapshot);

        // 5. Nacos推送成功后，更新数据库本条规则发布状态与版本号（与快照同值）
        rule.setPublished(1);
        rule.setVersion(newVersion);
        ruleMapper.updateById(rule);
        return rule;
    }

    // 评估数据源（Nacos 快照 + 降级 DB）

    /**
     * 查询租户当前所有已发布且启用的财务规则
     * 读取链路: 本地缓存TTL -> Nacos租户快照配置 → 降级查询数据库生效规则
     * 设计说明：
     * 1. Nacos内存储租户全量已发布快照（published=1），快照里包含启用/停用两类规则；
     * 2. 上层rule_check校验只允许使用enabled=1的规则，因此内存过滤禁用项；
     * 3. 降级兜底：Nacos无配置/解析失败/拉取异常时自动走原始DB查询逻辑，避免业务空窗
     * @param tenantId 租户唯一标识
     * @return 可用于报销校验的已发布+启用财务规则列表，无数据返回空集合
     */
    public List<FinanceRule> listEnabled(Long tenantId) {
        // 从统一Nacos工具类获取租户规则快照，传入DB降级回调与缓存TTL时长
        List<FinanceRule> rules = tenantNacosConfigHelper.getTenantConfig(
                tenantId, nacosConfigProperties.getRuleDataId(),
                new TypeReference<List<FinanceRule>>() {},
                nacosConfigProperties.getCacheTtlSeconds(),
                () -> dbEnabledRules(tenantId));

        // 兜底空值保护
        if (rules == null) {
            return List.of();
        }

        // Nacos快照仅保证published=1，需二次过滤：只保留启用状态enabled=1的规则供校验使用
        return rules.stream().filter(r -> r.getEnabled() != null && r.getEnabled() == 1).toList();
    }

    /** 降级回调：DB 直查启用规则（P2b 原逻辑） */

    /**
     * Nacos读取失败时的降级回调逻辑
     * 兼容P2b原有数据库查询逻辑，直接从DB查询租户启用中的规则
     * @param tenantId 租户ID
     * @return 数据库中启用状态的财务规则集合
     */
    private List<FinanceRule> dbEnabledRules(Long tenantId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<FinanceRule>()
                .eq(FinanceRule::getTenantId, tenantId)
                .eq(FinanceRule::getEnabled, 1));
    }

    /**
     * 同租户同 rule_type 唯一校验（P2c 防止同类型规则并存，业务层 + SQL 双层兜底）
     * 配合 uk_rule_type (tenant_id, rule_type, deleted) 唯一索引：业务层拦截给友好提示，
     * 索引兜底并发/绕过校验的场景。@TableLogic 自动过滤 deleted=0，仅统计存活行；
     * 已删行 deleted=主键id 不占唯一名额（见 migration-P2c §4）。
     * @param tenantId  租户 ID
     * @param ruleType  规则类型（update 时为改后类型）
     * @param excludeId 需排除的规则 ID（update 传自身；save 传 null）
     */
    private void ensureRuleTypeUnique(Long tenantId, String ruleType, Long excludeId) {
        Long count = ruleMapper.selectCount(new LambdaQueryWrapper<FinanceRule>()
                .eq(FinanceRule::getTenantId, tenantId)
                .eq(FinanceRule::getRuleType, ruleType)
                .ne(excludeId != null, FinanceRule::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException("同租户同类型规则已存在，不允许重复创建");
        }
    }

    /**
     * 根据主键加载规则，不存在则抛出业务异常
     * @param id 财务规则主键ID
     * @return 数据库完整规则实体
     * @throws BizException 当ID对应规则记录不存在时抛出
     */
    private FinanceRule mustLoad(Long id) {
        FinanceRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException("规则不存在: " + id);
        }
        return rule;
    }

    /**
     * 规则版本号自增生成器
     * 版本格式固定为 {主版本}.{次版本}，仅递增主版本号，次版本保持不变
     * 示例："1.0" → "2.0"、"5.3" → "6.3"、空/非法字符兜底为 "1.0"
     * @param version 当前旧版本字符串
     * @return 自增后的新版本号
     */
    private static String nextVersion(String version) {
        if (version == null || version.isBlank()) {
            return "1.0";
        }
        String[] parts = version.split("\\.");
        int major = 1;
        try {
            // 解析主版本数字
            major = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            // 非数字前缀，按 1.0 起步
        }
        // 保留原有次版本，无次版本则补 .0
        return (major + 1) + (parts.length > 1 ? "." + parts[1] : ".0");
    }

    // 规则评估

    /**
     * 报销单全规则校验入口
     * 遍历租户全部生效规则，逐条评估是否命中超标，汇总所有命中项并标记整体是否需要人工复核
     * 规则说明：
     * <ul>
     * <li>AMOUNT_LIMIT：申报总额 &gt; threshold → 命中（超标）</li>
     * <li>REIMBURSE_EXPIRE：明细发生日期早于 claimDate - maxDays → 命中（超标）</li>
     * <li>TRAVEL_STANDARD：明细带 city → 匹配城市标准 → 住宿/交通超标即命中；无标准/字段缺失跳过</li>
     * <li>SUBSIDY_LIMIT：明细带 subsidyAmount → 单日补贴超上限即命中</li>
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
            case TYPE_TRAVEL_STANDARD -> evalTravelStandard(rule, request);
            case TYPE_SUBSIDY_LIMIT -> evalSubsidyLimit(rule, request);
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
     * 差旅标准规则校验逻辑 TRAVEL_STANDARD
     * 规则配置结构：{"standards": [{"city":"北京","hotelDaily":500,"transportTotal":3000},...]}
     * 校验逻辑：
     * 1. 按明细城市精确匹配对应城市差旅标准；无城市标准则跳过当前明细
     * 2. 住宿校验：住宿总金额 > 单日住宿标准 × 住宿天数，判定住宿超标
     * 3. 交通校验：单条明细交通总金额 > 城市交通总额标准，判定交通超标
     * @param rule 当前待执行的差旅标准规则实体
     * @param request 报销单据校验请求，含报销明细列表
     * @return Optional<RuleHitVO> 存在超标明细返回命中记录；无明细/无对应城市标准/全部合规返回空
     */
    private Optional<RuleHitVO> evalTravelStandard(FinanceRule rule, RuleCheckRequest request) {
        // 无报销明细直接跳过校验
        if (request.items() == null || request.items().isEmpty()) {
            return Optional.empty();
        }

        // 读取规则JSON配置
        Map<String, Object> config = rule.getRuleConfig();
        Object rawStandards = config == null ? null : config.get("standards");

        // 标准数组不存在或为空，无需校验
        if (!(rawStandards instanceof List<?> list) || list.isEmpty()) {
            return Optional.empty();
        }

        // 存储所有超标违规描述
        List<String> violations = new ArrayList<>();

        // 遍历每一条报销明细
        for (RuleCheckItem item : request.items()) {
            String city = item.city();
            // 明细未填写城市，跳过本条明细校验
            if (city == null || city.isBlank()) {
                continue;
            }

            // 根据城市匹配对应的差旅标准配置
            Map<String, Object> standard = findStandard(list, city);
            if (standard == null) {
                log.info("规则[{}]无城市[{}]差旅标准，跳过该明细", rule.getRuleCode(), city);
                continue;
            }

            // 提取单日住宿标准、交通总额上限，统一转为BigDecimal
            BigDecimal hotelDaily = decimal(standard.get("hotelDaily"));
            BigDecimal transportTotal = decimal(standard.get("transportTotal"));
            String itemName = item.name() == null ? city : item.name();

            // ========== 住宿超标校验 ==========
            // 住宿总金额 > 单日标准 × 住宿天数 → 超标
            if (hotelDaily != null && item.hotelAmount() != null && item.hotelDays() != null && item.hotelDays() > 0
                    && item.hotelAmount().compareTo(hotelDaily.multiply(BigDecimal.valueOf(item.hotelDays()))) > 0) {
                violations.add("[" + itemName + "]城市" + city + "住宿 "
                        + item.hotelAmount().stripTrailingZeros().toPlainString()
                        + "元/" + item.hotelDays() + "晚，超过标准 " + hotelDaily.stripTrailingZeros().toPlainString() + "元/晚");
            }

            // ========== 交通总额超标校验 ==========
            // 单明细交通总金额 > 城市交通限额 → 超标
            if (transportTotal != null && item.transportAmount() != null
                    && item.transportAmount().compareTo(transportTotal) > 0) {
                violations.add("[" + itemName + "]城市" + city + "交通 "
                        + item.transportAmount().stripTrailingZeros().toPlainString()
                        + "元，超过标准 " + transportTotal.stripTrailingZeros().toPlainString() + "元");
            }
        }
        // 无任何违规，返回空
        if (violations.isEmpty()) {
            return Optional.empty();
        }
        // 拼接全部违规信息，返回规则命中结果
        return Optional.of(new RuleHitVO(rule.getRuleCode(), rule.getRuleName(), rule.getRuleType(),
                "差旅标准命中：" + String.join("；", violations), true));
    }

    /**
     * 补贴限额规则校验 SUBSIDY_LIMIT
     * 规则配置JSON：{"dailyAmount":200} 单日补贴上限
     * 校验规则：
     * 1. 明细存在补贴金额才参与校验
     * 2. 有住宿天数：单日补贴 = 补贴总额 ÷ 住宿天数，保留2位小数四舍五入
     * 3. 无住宿天数：直接使用补贴总额作为单日补贴
     * 4. 单日补贴 > 配置上限则判定违规，直接返回命中结果
     * @param rule 补贴限额规则实体
     * @param request 报销校验请求体
     * @return 存在补贴超标返回RuleHitVO；无明细/无补贴/未超标返回空Optional
     */
    private Optional<RuleHitVO> evalSubsidyLimit(FinanceRule rule, RuleCheckRequest request) {
        // 读取配置中的单日补贴上限
        BigDecimal dailyAmount = configDecimal(rule, "dailyAmount");
        // 无配置上限或无报销明细，直接跳过
        if (dailyAmount == null || request.items() == null || request.items().isEmpty()) {
            return Optional.empty();
        }
        // 遍历明细校验补贴
        for (RuleCheckItem item : request.items()) {
            // 明细无补贴金额，跳过
            if (item.subsidyAmount() == null) {
                continue;
            }
            // 计算日均补贴
            BigDecimal perDay = item.hotelDays() != null && item.hotelDays() > 0
                    ? item.subsidyAmount().divide(BigDecimal.valueOf(item.hotelDays()), 2, RoundingMode.HALF_UP)
                    : item.subsidyAmount();
            // 日均补贴超出上限，直接返回违规结果
            if (perDay.compareTo(dailyAmount) > 0) {
                String itemName = item.name() == null ? item.subsidyAmount().toPlainString() : item.name();
                return Optional.of(new RuleHitVO(rule.getRuleCode(), rule.getRuleName(), rule.getRuleType(),
                        "明细[" + itemName + "]补贴 " + perDay.stripTrailingZeros().toPlainString()
                                + "元/日，超过上限 " + dailyAmount.stripTrailingZeros().toPlainString() + "元/日",
                        true));
            }
        }
        // 所有明细补贴均合规
        return Optional.empty();
    }

    /**
     * 从城市标准列表精确匹配指定城市的标准配置
     * 匹配规则：city字段完全相等（不做模糊匹配、不忽略大小写）
     * @param list 规则内standards城市标准数组
     * @param city 报销明细所属城市
     * @return 匹配成功返回城市标准Map；无匹配项返回null
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findStandard(List<?> list, String city) {
        for (Object o : list) {
            // 非Map结构跳过
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            // 精确匹配城市名称
            if (city.equals(str(m.get("city")))) {
                return (Map<String, Object>) m;
            }
        }
        return null;
    }

    /**
     * 从规则JSON配置中读取数值，统一转为BigDecimal
     * 兼容Integer/Double/字符串多种存储类型，解析失败返回null并打印警告日志
     * @param rule 规则实体（内含rule_config JSON Map）
     * @param key 配置字段key（threshold/maxDays/dailyAmount）
     * @return 标准化BigDecimal，配置缺失/解析失败返回null
     */
    private static BigDecimal configDecimal(FinanceRule rule, String key) {
        Map<String, Object> config = rule.getRuleConfig();
        if (config == null || config.get(key) == null) {
            return null;
        }
        return decimal(config.get(key));
    }

    /** 对象安全转 BigDecimal，兼容 Number / 字符串，解析失败返回 null */
    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** null 安全转字符串 */
    private static String str(Object v) {
        return v == null ? null : v.toString();
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

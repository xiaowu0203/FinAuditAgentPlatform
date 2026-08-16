package com.finaudit.agentcore.support;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.agentcore.config.NacosConfigProperties;
import com.finaudit.starter.web.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Nacos租户配置统一工具类 TenantNacosConfigHelper
 * 核心定位：收敛Nacos配置「监听、本地TTL缓存、DB降级、发布推送」全套逻辑，Service层无Nacos底层侵入
 * 架构设计：
 * 1. 租户隔离：dataId模板 {tenantId} 动态生成租户独立配置标识，租户间完全隔离
 * 2. 双链路：
 *    读链路：本地内存TTL缓存优先 → Nacos远程拉取 → 自定义DB降级回调兜底
 *    写链路：后台publish接口调用 → Nacos v3控制台API推送全租户快照 → 主动刷新本地缓存
 * 3. 事件驱动：Nacos长轮询Listener监听配置变更，实时刷新内存缓存，服务无需重启
 * 4. 容错兜底：Nacos服务不可达/配置缺失/JSON解析异常自动降级数据库，无业务空窗
 * 5. 单向同步约束：仅业务后台发布接口可写Nacos；控制台手动修改配置不会回写DB，DB为唯一权威数据源
 */
@Component
public class TenantNacosConfigHelper {

    private static final Logger log = LoggerFactory.getLogger(TenantNacosConfigHelper.class);

    /** data-id模板租户占位符，替换为真实租户ID生成隔离dataId */
    private static final String TENANT_PLACEHOLDER = "{tenantId}";

    /** SpringCloudAlibaba自动装配Nacos客户端管理器，获取ConfigService做配置订阅/读取 */
    private final NacosConfigManager nacosConfigManager;
    /** Nacos环境配置：控制台地址、账号、namespace、分组、缓存TTL、规则dataId模板等 */
    private final NacosConfigProperties props;
    /** JSON序列化/反序列化工具，用于配置对象与Nacos文本互转 */
    private final ObjectMapper objectMapper;
    /** 控制台 API 客户端 */
    private final RestClient httpClient;

    // ===================== 本地内存缓存容器（线程安全） =====================
    /**
     * 一级缓存：dataId -> 缓存实体CachedValue
     * 存储：解析后的Java对象、原始JSON字符串、缓存加载时间戳，用于TTL过期判断
     */
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();
    /** 已完成监听注册的dataId集合，用于去重，避免重复addListener */
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    /** dataId -> 对应泛型解析类型，监听回调收到配置文本时据此反序列化 */
    private final Map<String, TypeReference<?>> dataIdTypes = new ConcurrentHashMap<>();

    public TenantNacosConfigHelper(NacosConfigManager nacosConfigManager,
                                   NacosConfigProperties props,
                                   ObjectMapper objectMapper) {
        this.nacosConfigManager = nacosConfigManager;
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = RestClient.builder().build();
    }

    // ==================================================================================
    // 读取配置对外API：监听 + 内存TTL缓存 + Nacos远程拉取 + DB降级兜底
    // ==================================================================================

    /**
     * 获取并自动解析租户配置（泛型封装）
     * 读取优先级：内存未过期缓存 > 实时拉取Nacos > 执行降级回调(DB查询)
     * 订阅逻辑：首次访问自动注册Nacos长轮询监听器，后续配置变更自动刷新缓存
     * @param tenantId 操作租户ID，用于生成隔离dataId
     * @param dataIdTemplate dataId模板，携带{tenantId}占位符，例：finaudit-rules-{tenantId}
     * @param type 目标解析泛型类型，如TypeReference<List<FinanceRule>>
     * @param ttlSeconds 内存缓存有效期（秒），过期强制重新拉取Nacos
     * @param fallback Nacos异常/无配置时降级回调，传入DB查询逻辑
     * @return 解析完成的配置对象，降级时返回DB查询结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getTenantConfig(Long tenantId, String dataIdTemplate, TypeReference<T> type,
                                 long ttlSeconds, Supplier<T> fallback) {
        // 1. 替换占位符生成当前租户唯一dataId
        String dataId = resolveDataId(tenantId, dataIdTemplate);
        // 2. 不存在监听则注册监听，完成配置种子预加载
        ensureSubscribed(dataId, type);
        // 3. 命中未过期内存缓存直接返回
        CachedValue cv = cache.get(dataId);
        if (cv != null && !cv.expired(ttlSeconds) && cv.value != null) {
            return (T) cv.value;
        }
        // 4. 缓存过期/冷启动，远程一次性拉取Nacos配置
        String content = getConfigOnce(dataId);
        if (content != null && !content.isBlank()) {
            // 将Nacos配置写入缓存
            return (T) parseAndCache(dataId, content, type);
        }
        // 5. Nacos无有效配置，执行DB降级逻辑
        T value = fallback.get();
        // 将降级结果写入缓存，减少短时间重复穿透DB
        cache.put(dataId, new CachedValue(value, null, Instant.now()));
        return value;
    }

    /**
     * 仅读取Nacos配置原始JSON字符串，不做对象解析
     * 复用同一套内存TTL缓存，无配置返回null
     * @param tenantId 租户ID
     * @param dataIdTemplate dataId模板
     * @param ttlSeconds 缓存过期时长
     * @return Nacos原始JSON文本
     */
    public String getTenantConfigRaw(Long tenantId, String dataIdTemplate, long ttlSeconds) {
        // 替换占位符生成当前租户唯一dataId
        String dataId = resolveDataId(tenantId, dataIdTemplate);
        // 获取本地缓存
        CachedValue cv = cache.get(dataId);
        // 存在则返回
        if (cv != null && !cv.expired(ttlSeconds) && cv.content != null) {
            return cv.content;
        }
        // 远程拉取并缓存原始文本
        String content = getConfigOnce(dataId);
        // 存在则写入缓存
        if (content != null && !content.isBlank()) {
            cache.put(dataId, new CachedValue(null, content, Instant.now()));
        }
        return content;
    }

    // ==================================================================================
    // 发布配置对外API：管理端发布入口，调用Nacos3 v3控制台API推送快照
    // ==================================================================================

    /**
     * 发布租户全量配置快照至Nacos（单向写，不回写DB）
     * 执行流程：序列化对象→登录Nacos获取token→调用v3控制台接口推送JSON→主动刷新本地缓存
     * @param tenantId 租户ID
     * @param dataIdTemplate dataId模板
     * @param content 需要发布的完整租户规则快照对象
     */
    public void publishTenantConfig(Long tenantId, String dataIdTemplate, Object content) {
        // 替换占位符生成当前租户唯一dataId
        String dataId = resolveDataId(tenantId, dataIdTemplate);
        // 序列化为JSON
        String json = serialize(content);
        // 获取Nacos登录凭证
        String token = loginToken();
        // 调用Nacos3控制台API推送配置
        postConfig(token, dataId, json);
        log.info("Nacos 配置发布成功: dataId={}", dataId);
        // 主动刷新当前实例缓存，无需等待异步监听回调，实现发布后即时生效
        handleConfigChange(dataId, json);
    }

    // ==================================================================================
    // 内部私有工具方法
    // ==================================================================================

    /**
     * 替换dataId模板中的{tenantId}占位符，生成租户隔离的唯一dataId
     * @param tenantId 租户ID
     * @param dataIdTemplate 带占位符模板
     * @return 完整dataId字符串
     */
    private String resolveDataId(Long tenantId, String dataIdTemplate) {
        return dataIdTemplate.replace(TENANT_PLACEHOLDER, String.valueOf(tenantId));
    }

    /**
     * 为dataId注册Nacos长轮询监听器，做去重控制；订阅时预加载配置作为缓存种子
     * 解决「服务先启动、后发布配置」导致首次缓存空的问题
     * @param dataId 完整租户dataId
     * @param type 当前dataId对应的泛型解析类型
     */
    private void ensureSubscribed(String dataId, TypeReference<?> type) {
        // 保存解析类型，监听回调时使用
        dataIdTypes.put(dataId, type);
        // 已订阅直接返回，避免重复注册Listener
        if (!subscribed.add(dataId)) {
            return;
        }
        try {
            ConfigService configService = nacosConfigManager.getConfigService();
            // 注册配置变更监听器
            configService.addListener(dataId, props.getGroup(), new Listener() {
                /** Nacos配置变更回调，刷新本地缓存 */
                @Override
                public void receiveConfigInfo(String configInfo) {
                    handleConfigChange(dataId, configInfo);
                }

                /** 使用Nacos客户端共享线程池 */
                @Override
                public Executor getExecutor() {
                    return null;
                }
            });
            // 订阅时预拉取一次配置，初始化缓存种子
            String content = configService.getConfig(dataId, props.getGroup(), 3000);
            if (content != null && !content.isBlank()) {
                handleConfigChange(dataId, content);
            }
            log.info("Nacos 租户配置监听已注册: dataId={}", dataId);
        } catch (NacosException e) {
            // 订阅失败不阻断业务，降级为「定时TTL拉取」模式兜底
            log.warn("Nacos 订阅失败，将退化为一次拉取 + TTL 兜底: dataId={}, err={}", dataId, e.getMessage());
        }
    }

    /**
     * 统一处理配置变更事件（监听回调 / 发布后主动刷新共用）
     * 配置为空/空白代表配置被删除，直接清空本地缓存；解析失败保留旧缓存不覆盖
     * @param dataId 配置标识
     * @param configInfo Nacos最新配置JSON文本
     */
    private void handleConfigChange(String dataId, String configInfo) {
        // 若配置为空，则移除缓存
        if (configInfo == null || configInfo.isBlank()) {
            // 移除该租户的缓存
            cache.remove(dataId);
            log.info("Nacos 配置已删除，清缓存: dataId={}", dataId);
            return;
        }
        TypeReference<?> type = dataIdTypes.get(dataId);
        try {
            Object value = type == null ? null : objectMapper.readValue(configInfo, type);
            // 更新缓存实体（解析对象+原始文本+当前时间戳）
            cache.put(dataId, new CachedValue(value, configInfo, Instant.now()));
            log.info("Nacos 配置已刷新: dataId={}", dataId);
        } catch (JsonProcessingException e) {
            // 解析异常不清除旧缓存，保证业务不中断
            log.warn("Nacos 配置解析失败，保留旧缓存: dataId={}, err={}", dataId, e.getMessage());
        }
    }

    /**
     * 单次同步拉取Nacos配置原文，超时3秒，异常返回null不抛出
     * @param dataId 租户配置标识
     * @return 原始JSON字符串 / null（异常/无配置）
     */
    private String getConfigOnce(String dataId) {
        try {
            return nacosConfigManager.getConfigService().getConfig(dataId, props.getGroup(), 3000);
        } catch (NacosException e) {
            log.warn("Nacos 读取配置失败: dataId={}, err={}", dataId, e.getMessage());
            return null;
        }
    }

    /**
     * 将Nacos原始JSON解析为泛型对象并写入内存缓存
     * 解析失败抛出业务异常，上层触发DB降级
     * @param dataId 租户dataId
     * @param content JSON原文
     * @param type 目标泛型类型
     * @return 解析完成的业务对象
     */
    private <T> T parseAndCache(String dataId, String content, TypeReference<T> type) {
        try {
            T value = objectMapper.readValue(content, type);
            cache.put(dataId, new CachedValue(value, content, Instant.now()));
            return value;
        } catch (JsonProcessingException e) {
            log.warn("Nacos 配置解析失败，降级: dataId={}, err={}", dataId, e.getMessage());
            throw new BizException("规则配置解析失败: " + e.getMessage());
        }
    }

    /**
     * 调用Nacos v1登录接口，获取访问控制台v3 API的accessToken
     * @return 登录凭证token
     */
    private String loginToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", props.getUsername());
        form.add("password", props.getPassword());
        String body = httpClient.post()
                .uri(props.getCoreAddr() + "/nacos/v1/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(body == null ? "" : body).path("accessToken").asText("");
        } catch (JsonProcessingException e) {
            throw new BizException("Nacos 登录响应解析失败: " + e.getMessage());
        }
    }

    /**
     * Nacos3 分离版控制台v3配置推送接口
     * 接口约束：请求头携带accessToken，表单传dataId/groupName/namespaceId/content/type
     * @param token 登录凭证
     * @param dataId 租户配置标识
     * @param content 待发布JSON字符串
     */
    private void postConfig(String token, String dataId, String content) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("dataId", dataId);
        form.add("groupName", props.getGroup());
        form.add("namespaceId", props.getNamespace());
        form.add("type", "json");
        form.add("content", content);
        try {
            httpClient.post()
                    .uri(props.getConsoleAddr() + "/v3/console/cs/config")
                    .header("accessToken", token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new BizException("Nacos 配置发布失败(" + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            throw new BizException("Nacos 配置发布失败: " + e.getMessage());
        }
    }

    /**
     * 将业务规则对象序列化为JSON字符串，用于推送Nacos
     * @param content 待发布快照对象
     * @return JSON文本
     */
    private String serialize(Object content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new BizException("规则配置序列化失败: " + e.getMessage());
        }
    }

    // ==================================================================================
    // 内存缓存内部实体：存储解析对象、原始文本、加载时间戳，用于TTL过期判断
    // ==================================================================================
    /**
     * 内存缓存存储单元
     * 字段说明：
     * value：JSON反序列化后的业务对象（读取业务直接使用）
     * content：Nacos原始JSON字符串（只读原始配置场景使用）
     * loadedAt：缓存写入时间，用于计算是否过期
     */
    private static final class CachedValue {
        final Object value;
        final String content;
        final Instant loadedAt;

        CachedValue(Object value, String content, Instant loadedAt) {
            this.value = value;
            this.content = content;
            this.loadedAt = loadedAt;
        }

        /**
         * 判断当前缓存是否过期
         * @param ttlSeconds 配置的缓存有效期（秒）
         * @return true=过期，false=未过期可复用
         */
        boolean expired(long ttlSeconds) {
            return loadedAt == null || Duration.between(loadedAt, Instant.now()).getSeconds() >= ttlSeconds;
        }
    }
}

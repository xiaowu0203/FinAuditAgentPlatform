package com.finaudit.starter.mybatisplus.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finaudit.starter.web.tenant.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis‑Plus 公共自动配置类
 * 装配多租户插件 + 分页插件，统一管理SQL拦截逻辑
 */
@AutoConfiguration
@ConditionalOnClass(MybatisPlusInterceptor.class)
public class CommonMybatisPlusAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CommonMybatisPlusAutoConfiguration.class);

    /**
     * 多租户拦截器忽略表名单（小写）：无 tenant_id 列的平台级全局表。
     * <ul>
     *   <li>sys_tenant —— 租户主表，本身不存在 tenant_id 字段</li>
     *   <li>sys_permission —— P3.5a 权限目录（平台级，所有租户共用同一套权限标识符）</li>
     * </ul>
     * 新增全局表必须登记于此，否则拦截器会向 SQL 拼 tenant_id 导致报错/查空。
     */
    private static final java.util.Set<String> IGNORE_TENANT_TABLES =
            java.util.Set.of("sys_tenant", "sys_permission");

    /**
     * 注册 jsr310 时间序列化到 MyBatis-Plus JSON 类型处理器。
     * <p>默认 {@link JacksonTypeHandler} 内部 ObjectMapper 未注册 JavaTimeModule，
     * JSON 列携带 {@link java.time.LocalDate}（如 agent_task.input_params 报销单快照）
     * 会抛 {@code Java 8 date/time type not supported}。此处统一注册，全服务 JSON 列生效。</p>
     */
    @PostConstruct
    public void registerJacksonJsr310() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        JacksonTypeHandler.setObjectMapper(mapper);
    }

    /**
     * MyBatis‑Plus 核心拦截器Bean
     * <p>
     * ⚠️插件添加顺序非常重要：
     * TenantLineInnerInterceptor(多租户) 必须放在 PaginationInnerInterceptor(分页) 前面
     * 原因：分页会自动生成count统计SQL；先由租户插件改写SQL追加租户条件，
     * 分页插件再基于改造后的SQL生成count语句，保证count计数携带租户过滤，防止跨租户数据泄露。
     * 如果顺序颠倒，count会统计全部租户数据，分页总条数错误。
     * </p>
     * @return MybatisPlusInterceptor
     */
    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // ---------------------- 多租户行级插件 ----------------------
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

            /**
             * 获取当前租户ID表达式，用于自动拼接SQL条件 / 自动填充insert租户字段
             * @return 租户ID表达式
             */
            @Override
            public Expression getTenantId() {
                // 获取租户ID
                Long tenantId = TenantContextHolder.getTenantId();
                // 上下文租户ID为空，打印警告日志，回退到系统默认租户ID
                if (tenantId == null) {
                    log.warn("租户上下文缺失，回退默认租户 {}", TenantContextHolder.DEFAULT_TENANT_ID);
                    return new LongValue(TenantContextHolder.DEFAULT_TENANT_ID);
                }
                return new LongValue(tenantId);
            }

            /**
             * 指定数据库租户字段列名
             * @return 租户数据库列名 tenant_id
             */
            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            /**
             * 判断是否忽略指定表的租户拦截
             * 返回true：该表不追加租户条件、不自动填充租户字段
             * @param tableName 数据库表名
             * @return true=忽略租户插件，false=启用租户过滤
             */
            @Override
            public boolean ignoreTable(String tableName) {
                return IGNORE_TENANT_TABLES.contains(tableName.toLowerCase());
            }
        }));

        // ---------------------- MySQL分页插件 ----------------------
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}

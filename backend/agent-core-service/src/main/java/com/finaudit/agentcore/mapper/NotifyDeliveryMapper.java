package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.NotifyDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Webhook 投递台账 Mapper（P3.8 R8-2；仅声明签名，自定义 SQL 见 mapper XML）。
 *
 * <p>自定义语句构成 outbox 的取件-认领-回写闭环：
 * {@link #selectDue} 取到期行 → {@link #claimAttempt} 乐观认领（多实例安全）→
 * {@link #updateResult} 回写结果。</p>
 */
@Mapper
public interface NotifyDeliveryMapper extends BaseMapper<NotifyDelivery> {

    /**
     * 枚举「当前有到期投递记录」的租户 id（定时任务的入口）。
     *
     * <p><b>⚠️ 本方法是本模块唯一跨租户的读取，也是唯一带 {@code @InterceptorIgnore} 的语句</b>：
     * 定时任务没有 HTTP 请求上下文，而其余所有投递读写都被拦截器加上 {@code tenant_id} 条件——
     * 若不先枚举租户，任务只会在默认租户（1）里空转，其它租户的 Webhook 永远发不出去。
     * 返回的只是租户 id，不含任何业务数据；后续处理逐个租户在
     * {@code TenantContextHolder.runWith} 内进行，隔离性不受影响。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    List<Long> selectPendingTenantIds();

    /**
     * 取到期待投递的记录（status=PENDING 且 next_retry_at &lt;= now），按 id 升序保证公平。
     *
     * <p>⚠️ 不带 {@code @InterceptorIgnore}：多租户拦截器会追加 tenant_id 条件，
     * 故调用方<b>必须</b>在目标租户上下文内执行（见 {@link #selectPendingTenantIds()} 与
     * {@code WebhookDeliveryService} 的逐租户处理）。</p>
     */
    List<NotifyDelivery> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 批量登记投递记录（单条多行 INSERT，AGENTS.md §5.10 禁止 for 循环逐行插入）。
     *
     * @param list 待插入记录（非空）
     * @return 插入行数
     */
    int insertBatch(@Param("list") List<NotifyDelivery> list);

    /**
     * 乐观认领一次投递额度：{@code attempt_count} 作为版本号，只有仍是取件时读到的值才认领成功。
     *
     * <p>为什么需要它：定时任务可能多实例并发（或上一轮尚未跑完下一轮已经开始），
     * 若直接"查到就发"，同一条记录会被重复投递。用条件更新把"谁拿到这条"变成数据库层的原子判定，
     * 比应用层加锁简单且天然支持多实例。</p>
     *
     * @return 1=认领成功（本次由我投递）；0=已被其他实例/轮次认领，跳过
     */
    int claimAttempt(@Param("id") Long id, @Param("expectedAttempt") int expectedAttempt);

    /**
     * 回写投递结果（成功/待重试/放弃三态由实体方法算好后传入）。
     *
     * @return 影响行数
     */
    int updateResult(NotifyDelivery delivery);
}

package com.finaudit.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.tenant.pojo.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户 Mapper（仅声明签名，自定义 SQL 见 mapper XML）。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按权限码列出「本租户 + 启用 + 未删除」的用户 id（P3.8 R8-2：通知收件人解析）。
     *
     * <p><b>为什么必须查库，不能从请求上下文取</b>：通知由后台线程触发（MQ 消费、定时投递、任务收尾），
     * 那些线程没有登录用户上下文，无从知道"谁有审批权限"——而"有新工单待审批"恰恰是最该主动提醒的场景。</p>
     *
     * <p>多租户：不加 {@code @InterceptorIgnore}，由拦截器自动为 {@code sys_user / sys_user_role /
     * sys_role_permission} 追加 {@code tenant_id} 条件；{@code sys_permission} 是平台级全局表，
     * 已在 {@code CommonMybatisPlusAutoConfiguration.IGNORE_TENANT_TABLES} 登记，不会被追加。</p>
     *
     * @param permCode 权限码（如 {@code audit:approve}）
     * @return 用户 id 列表（去重、升序；无命中返回空列表）
     */
    List<Long> listEnabledUserIdsByPermCode(@Param("permCode") String permCode);
}

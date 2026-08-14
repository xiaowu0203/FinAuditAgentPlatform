package com.finaudit.tenant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.tenant.pojo.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户-角色关联 Mapper（仅声明签名，自定义 SQL 见 mapper XML）。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 批量绑定角色（单条多行 INSERT，见 SysUserRoleMapper.xml）。
     * <p>{@link InterceptorIgnore#tenantLine}：多租户插件不支持多行 VALUES INSERT（jsqlparser 解析失败），
     * 该 SQL 已在列清单中显式写入 tenant_id，跳过拦截器安全。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    int insertBatch(@Param("list") List<SysUserRole> list);
}

package com.finaudit.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.tenant.pojo.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限目录 Mapper（sys_permission 平台级全局表，多租户拦截器已注册忽略；查询走 BaseMapper）。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
}

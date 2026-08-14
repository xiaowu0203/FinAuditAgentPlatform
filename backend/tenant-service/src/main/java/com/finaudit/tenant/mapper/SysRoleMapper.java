package com.finaudit.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.tenant.pojo.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色 Mapper（仅声明签名，自定义 SQL 见 mapper XML）。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
}

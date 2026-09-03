package com.finaudit.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.tenant.pojo.entity.SysDept;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门 Mapper（仅声明签名；自定义 SQL 见 mapper XML）。
 */
@Mapper
public interface SysDeptMapper extends BaseMapper<SysDept> {
}
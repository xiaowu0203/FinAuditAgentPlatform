package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.NotifyWebhook;
import org.apache.ibatis.annotations.Mapper;

/**
 * Webhook 配置 Mapper（P3.8 R8-2）。
 *
 * <p>CRUD 全部由 MyBatis-Plus 的 {@code BaseMapper} 提供，无自定义 SQL——
 * 本接口存在是为遵循「每个实体的 Mapper 只被其专属 Service 持有」（AGENTS.md §5.9）。</p>
 *
 * <p>⚠️ {@code event_types} 是 JSON 列：凡涉及它的写入必须走实体更新（{@code updateById}），
 * 禁止用 wrapper 的 {@code set(列, 值)}（§5.13，参数缺 typeHandler 会被 MySQL 拒绝）。</p>
 */
@Mapper
public interface NotifyWebhookMapper extends BaseMapper<NotifyWebhook> {
}

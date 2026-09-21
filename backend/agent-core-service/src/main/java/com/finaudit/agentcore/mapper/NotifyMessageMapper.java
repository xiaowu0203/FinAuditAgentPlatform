package com.finaudit.agentcore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.finaudit.agentcore.pojo.entity.NotifyMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 站内信 Mapper（P3.8 R8-2；仅声明签名，自定义 SQL 见 mapper XML）。
 */
@Mapper
public interface NotifyMessageMapper extends BaseMapper<NotifyMessage> {

    /**
     * 批量新增站内信（群发时按收件人展开，一次 INSERT 多行）。
     *
     * <p>幂等：带 {@code dedupe_key} 的行若已存在会触发 {@code uk_notify_dedupe} 唯一冲突，
     * 由调用方（{@code NotifyMessageService}）捕获 {@code DuplicateKeyException} 并按"已提醒过"处理。
     * **刻意不用 {@code INSERT IGNORE}**：它会连"数据过长/取值非法"这类真错误一起吞掉。</p>
     *
     * @param list 待插入消息（非空）
     * @return 实际插入行数
     */
    int insertBatch(@Param("list") List<NotifyMessage> list);
}

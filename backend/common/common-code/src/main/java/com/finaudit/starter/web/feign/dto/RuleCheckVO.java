package com.finaudit.starter.web.feign.dto;

import java.util.List;

/**
 * 财务规则校验结果（跨服务契约，agent-core 评估返回）。
 *
 * @param hits     命中的规则（未命中则为空列表）
 * @param overLimit 是否存在「超标」命中（任一命中项 overLimit=true 即 true）
 */
public record RuleCheckVO(List<RuleHitVO> hits, boolean overLimit) {

    public static RuleCheckVO empty() {
        return new RuleCheckVO(List.of(), false);
    }
}

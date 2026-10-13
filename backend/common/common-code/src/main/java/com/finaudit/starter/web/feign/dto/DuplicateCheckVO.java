package com.finaudit.starter.web.feign.dto;

import java.util.List;

/**
 * 重复报销检测结果（跨服务契约，agent-core 按 reimbId 读当前+历史 OCR 商户返回）。
 *
 * <p><b>P3.8 R3 引入分级</b>：结果按置信度分两级（见 {@link DuplicateItemVO}），
 * 并由本 VO 汇总最高等级 {@link #dupLevel}。调用方（重复报销检测工具 →
 * {@code ReviewFlowDecider}）应<b>仅对 {@link DuplicateItemVO#LEVEL_HIGH} 触发风控命中</b>，
 * {@code LEVEL_MEDIUM} 只作展示供人工参考——这是 B-4 误报的根治手段。</p>
 *
 * @param suspected 疑似重复的历史报销单（未发现则为空列表）
 * @param dupLevel  最高重复等级：LEVEL_HIGH / LEVEL_MEDIUM / NONE（无命中）
 */
public record DuplicateCheckVO(List<DuplicateItemVO> suspected, String dupLevel) {

    /** 无任何命中 */
    public static final String LEVEL_NONE = "NONE";

    public static DuplicateCheckVO empty() {
        return new DuplicateCheckVO(List.of(), LEVEL_NONE);
    }

    /**
     * 由命中列表推导最高等级并构造结果。
     */
    public static DuplicateCheckVO of(List<DuplicateItemVO> suspected) {
        if (suspected == null || suspected.isEmpty()) {
            return empty();
        }
        boolean hasHigh = suspected.stream().anyMatch(DuplicateItemVO::isHigh);
        return new DuplicateCheckVO(suspected, hasHigh ? DuplicateItemVO.LEVEL_HIGH : DuplicateItemVO.LEVEL_MEDIUM);
    }

    /** 是否存在发票号硬命中（调用方决定是否据此触发风控） */
    public boolean hasHighLevelHit() {
        return DuplicateItemVO.LEVEL_HIGH.equals(dupLevel);
    }
}

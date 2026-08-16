package com.finaudit.starter.web.feign.dto;

import java.util.List;

/**
 * 重复报销检测结果（跨服务契约，agent-core 按 reimbId 读当前+历史 OCR 商户返回）。
 *
 * @param suspected 疑似重复的历史报销单（未发现则为空列表）
 */
public record DuplicateCheckVO(List<DuplicateItemVO> suspected) {

    public static DuplicateCheckVO empty() {
        return new DuplicateCheckVO(List.of());
    }
}

package com.finaudit.agentcore.enums;

import com.finaudit.starter.web.exception.BizException;

/**
 * 预算占用状态（budget_occupancy.status，P3.8 R1）。
 *
 * <p>一张报销单在生命周期内可能多次占用（resubmit 重跑会先释放再重新占用），
 * 故用 OCCUPIED / RELEASED 表示**当前是否占用**，并配 occupy_count / release_count 记录发生次数。</p>
 */
public enum OccupancyStatus {

    /** 已占用：该报销单的金额当前计入对应部门周期的 used_amount */
    OCCUPIED,
    /** 已释放：占用已回退（撤回/撤销/驳回/终止/重跑失败），used_amount 已扣减 */
    RELEASED;

    /** 解析，非法值抛业务异常（对齐 OcrStatus.of 语义）。 */
    public static OccupancyStatus of(String v) {
        for (OccupancyStatus s : values()) {
            if (s.name().equalsIgnoreCase(v)) {
                return s;
            }
        }
        throw new BizException("预算占用状态不合法: " + v);
    }
}

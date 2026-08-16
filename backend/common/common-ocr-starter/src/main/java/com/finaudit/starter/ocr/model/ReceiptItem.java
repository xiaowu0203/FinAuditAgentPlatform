package com.finaudit.starter.ocr.model;

import lombok.Data;

import java.util.Map;

/**
 * 智能财务票据识别返回的「一张票据」。
 * <p>百度 iocr finance 的 {@code words_result[]} 每项含票据分类 {@code type}（17 种，如
 * vat_invoice / train_ticket / taxi_receipt / air_ticket / roll_normal_invoice /
 * toll_invoice / quota_invoice / printed_invoice / bus_ticket / ferry_ticket /
 * taxi_online_ticket / motor_vehicle_invoice / used_vehicle_invoice / limit_invoice /
 * shopping_receipt / pos_invoice / others）+ 结构化字段 + 位置。</p>
 * <p>字段不做跨厂商归一化：{@link #fields()} 直接透出百度原始字段（名→值，值取 value/word 兜底），
 * 由 tool-service 的 OcrExtractTool 按 {@code type} 映射 amount/date/merchant/taxNo。</p>
 */
@Data
public class ReceiptItem {

    /** 票据类型编码（百度 17 种之一，见类注释） */
    private String type;
    /** 票据在图片中的位置（左/上/宽/高） */
    private int left;
    private int top;
    private int width;
    private int height;
    /** 结构化字段：百度字段名 → 文本值（值优先取 value，缺省取 word） */
    private Map<String, String> fields;

}

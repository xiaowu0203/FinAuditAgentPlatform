package com.finaudit.toolservice.executor;

import com.finaudit.starter.ocr.OcrService;
import com.finaudit.starter.ocr.exception.OcrException;
import com.finaudit.starter.ocr.model.FinanceReceiptOcr;
import com.finaudit.starter.ocr.model.ReceiptItem;
import com.finaudit.starter.ocr.model.VatInvoiceOcr;
import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.feign.AgentCoreServiceFeign;
import com.finaudit.starter.web.feign.FileServiceFeign;
import com.finaudit.starter.web.feign.dto.OcrResultWritebackRequest;
import com.finaudit.starter.web.result.R;
import com.finaudit.toolservice.enums.ToolCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OCR票据识别工具实现
 * <p>
 * 财务审核Agent工具：批量处理报销单附件，下载图片文件，调用OCR服务识别票据信息；
 * 支持失败重试，增值税发票做二次细节增强识别，对识别结果做字段归一化处理；
 * 将识别结果回写核心服务，返回每张附件识别状态、提取字段、成功失败统计，
 * 识别失败标记需要人工录入，不阻断整体流程。
 * toolCode: {@link ToolCode#OCR_EXTRACT}
 * </p>
 */
@Component
public class OcrExtractTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(OcrExtractTool.class);

    /** 单附件识别最大重试次数 */
    private static final int MAX_RETRY = 3;

    /** OCR识别成功状态码 */
    private static final String OCR_SUCCESS = "SUCCESS";
    /** OCR识别失败状态码 */
    private static final String OCR_FAILED = "FAILED";

    /** 增值税发票类型标识，该类型需要做二次细节识别兜底 */
    private static final String TYPE_VAT_INVOICE = "vat_invoice";

    /** 归类为普通发票的票据类型集合 */
    private static final Set<String> INVOICE_TYPES = Set.of(
            "vat_invoice", "quota_invoice", "roll_normal_invoice", "printed_invoice", "toll_invoice");
    /** 归类为行程类票据的票据类型集合（火车票、打车票、机票行程单等） */
    private static final Set<String> ITINERARY_TYPES = Set.of(
            "train_ticket", "taxi_receipt", "taxi_online_ticket", "air_ticket", "bus_ticket", "ferry_ticket");

    private final OcrService ocrService;
    private final FileServiceFeign fileServiceFeign;
    private final AgentCoreServiceFeign agentCoreServiceFeign;
    /** HTTP客户端，下载附件图片二进制字节流（P3.5d 接线连接/读取超时，防慢附件占死 TOOL 消费线程） */
    private final RestClient httpClient = RestClient.builder()
            .requestFactory(httpRequestFactory())
            .build();

    /**
     * 附件下载 HTTP 工厂：连接 10s / 读取 60s。此前无超时，一次网络挂起即无限期占住消费者。
     */
    private static SimpleClientHttpRequestFactory httpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return factory;
    }

    /**
     * 构造器注入各类依赖服务
     * @param ocrService OCR识别服务
     * @param fileServiceFeign 文件服务远程调用代理
     * @param agentCoreServiceFeign agent核心服务远程调用代理
     */
    public OcrExtractTool(OcrService ocrService, FileServiceFeign fileServiceFeign,
                          AgentCoreServiceFeign agentCoreServiceFeign) {
        this.ocrService = ocrService;
        this.fileServiceFeign = fileServiceFeign;
        this.agentCoreServiceFeign = agentCoreServiceFeign;
    }

    @Override
    public ToolCode toolCode() {
        return ToolCode.OCR_EXTRACT;
    }

    /**
     * 批量执行附件OCR票据识别
     * @param tenantId 租户ID，多租户数据隔离
     * @param inputParams 工具入参：reimbId报销单ID、attachmentIds附件ID列表
     * @return 返回结果Map，包含报销ID、每张附件识别详情、成功/失败计数、提示消息
     * @throws BizException 入参缺失、附件ID非法时抛出业务异常
     */
    @Override
    public Map<String, Object> execute(Long tenantId, Map<String, Object> inputParams) {
        // 提取报销单ID
        Long reimbId = asLong(inputParams == null ? null : inputParams.get("reimbId"));
        // 提取附件ID集合
        @SuppressWarnings("unchecked")
        List<Object> attachmentIds = (List<Object>) (inputParams == null ? null : inputParams.get("attachmentIds"));
        // 必填参数校验：报销单ID、附件列表不能为空
        if (reimbId == null || attachmentIds == null || attachmentIds.isEmpty()) {
            throw new BizException("ocr_extract 入参缺少 reimbId / attachmentIds");
        }

        List<Map<String, Object>> receipts = new ArrayList<>(attachmentIds.size());
        int success = 0;
        int failed = 0;

        // 循环逐个处理每一张附件
        for (Object idObj : attachmentIds) {
            Long fileRecordId = asLong(idObj);
            if (fileRecordId == null) {
                throw new BizException("ocr_extract attachmentIds 含非法 id: " + idObj);
            }
            // 单张附件识别处理
            Map<String, Object> one = extractOne(tenantId, reimbId, fileRecordId);
            receipts.add(one);
            // 统计成功失败数量
            if (OCR_SUCCESS.equals(one.get("status"))) {
                success++;
            } else {
                failed++;
            }
        }

        // 组装有序返回结果，方便大模型解析
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reimbId", reimbId);
        result.put("receipts", receipts);
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("message", failed == 0
                ? "票据识别完成，成功 " + success + " 张"
                : "票据识别完成，成功 " + success + " 张，失败 " + failed + " 张（需人工录入）");
        return result;
    }

    /**
     * 单张附件完整识别流程：下载图片 → 财务票据OCR识别（异常自动重试）→ 增值税发票二次增强识别 → 字段归一化 → 结果回写
     * @param tenantId 租户ID
     * @param reimbId 报销单ID
     * @param fileRecordId 附件文件记录ID
     * @return 单张附件识别结果Map，包含状态、票据类型、提取字段等
     */
    private Map<String, Object> extractOne(Long tenantId, Long reimbId, Long fileRecordId) {
        OcrException lastError = null;

        // 循环重试，最多MAX_RETRY次
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                // 1.下载附件图片字节数组
                byte[] image = download(tenantId, fileRecordId);
                // 2.通用财务票据OCR识别
                FinanceReceiptOcr receipt = ocrService.recognizeFinance(image);
                // 返回结果为空/无票据内容，直接标记识别失败，不继续重试，交由人工录入
                if (receipt == null || receipt.isEmpty()) {
                    return failResult(tenantId, fileRecordId, "未识别到票据，需人工录入");
                }
                ReceiptItem first = receipt.getItems().get(0);
                String type = first.getType();

                // 3.增值税发票专项二次识别，补充税号、价税合计等细节；识别失败仅打警告，不阻断主流程
                VatInvoiceOcr vat = null;
                if (TYPE_VAT_INVOICE.equals(type)) {
                    try {
                        vat = ocrService.recognizeVat(image);
                    } catch (OcrException e) {
                        log.warn("附件 {} vat_invoice 细节识别失败（不阻断）: {}", fileRecordId, e.getMessage());
                    }
                }

                // 4.票据字段归一化，统一输出字段模型
                Map<String, Object> normalized = normalize(type, first.getFields(), vat);
                // 映射业务附件大类：INVOICE / ITINERARY / OTHER
                String fileType = mapFileType(type);

                // 5.将OCR识别结果回写到核心服务附件记录
                agentCoreServiceFeign.writebackOcrResult(tenantId, fileRecordId,
                        new OcrResultWritebackRequest(OCR_SUCCESS, fileType, normalized));
                log.info("附件 {} OCR 成功 type={} fileType={} merchant={}", fileRecordId, type, fileType,
                        normalized.get("merchant"));

                // 组装成功返回对象
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("fileRecordId", fileRecordId);
                one.put("status", OCR_SUCCESS);
                one.put("receiptType", type);
                one.put("fileType", fileType);
                one.put("fields", normalized);
                return one;
            } catch (OcrException | RestClientException | BizException e) {
                // OCR异常、网络请求异常、业务异常，捕获进行重试
                lastError = e instanceof OcrException ocrE ? ocrE : new OcrException(e.getMessage(), e);
                log.warn("附件 {} OCR 第 {}/{} 次失败: {}", fileRecordId, attempt, MAX_RETRY, e.getMessage());
            }
        }

        // 达到最大重试次数仍然失败，返回失败结果
        log.warn("附件 {} OCR 重试 {} 次仍失败，转人工录入: {}", fileRecordId, MAX_RETRY,
                lastError == null ? "" : lastError.getMessage());
        return failResult(tenantId, fileRecordId,
                "OCR 连续失败，需人工录入" + (lastError == null ? "" : ": " + lastError.getMessage()));
    }

    /**
     * 构建识别【失败结果】对象，同时回写失败状态至核心服务；回写发生异常仅打日志，不阻断流程
     * @param tenantId 租户ID
     * @param fileRecordId 附件记录ID
     * @param reason 失败原因描述
     * @return 失败结果Map，标记需要人工录入
     */
    private Map<String, Object> failResult(Long tenantId, Long fileRecordId, String reason) {
        Map<String, Object> ocrResult = new LinkedHashMap<>();
        ocrResult.put("error", reason);
        ocrResult.put("needManualEntry", true);
        try {
            agentCoreServiceFeign.writebackOcrResult(tenantId, fileRecordId,
                    new OcrResultWritebackRequest(OCR_FAILED, null, ocrResult));
        } catch (Exception e) {
            log.warn("附件 {} FAILED 回写失败（不阻断）: {}", fileRecordId, e.getMessage());
        }
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("fileRecordId", fileRecordId);
        one.put("status", OCR_FAILED);
        one.put("message", reason);
        return one;
    }

    /**
     * 获取附件预签名下载链接，下载图片二进制字节流
     * @param tenantId 租户ID
     * @param fileRecordId 附件记录ID
     * @return 图片byte字节数组
     * @throws BizException 获取下载链接失败抛出业务异常
     */
    private byte[] download(Long tenantId, Long fileRecordId) {
        // 获取下载链接
        R<String> resp = fileServiceFeign.presignDownload(tenantId, fileRecordId);
        if (resp.getCode() != 0 || resp.getData() == null) {
            throw new BizException("获取附件下载链接失败: " + resp.getMessage());
        }
        // 预签名 URL 已含 URL 编码（如 %2F），必须用 URI 原样透传：
        // 若走 uri(String) 会当 URI 模板二次编码（%→%25），MinIO 报 AuthorizationQueryParametersError
        log.info("附件 {} 下载预签名URL: {}", fileRecordId, resp.getData());
        return httpClient.get().uri(URI.create(resp.getData())).retrieve().body(byte[].class);
    }

    /**
     * OCR原始输出字段归一化处理
     * <p>
     * 兼容OCR接口中英文混合key，优先使用增值税专项识别结果；
     * 提取金额、开票日期、商户名称、税号，金额统一转为BigDecimal类型
     * </p>
     * @param type 原始票据类型
     * @param fields OCR原始返回字段Map
     * @param vat 增值税发票专项识别结果，可以为null
     * @return 归一化之后结构化字段Map
     */
    private Map<String, Object> normalize(String type, Map<String, String> fields, VatInvoiceOcr vat) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("receiptType", type);

        // 金额：优先取增值税识别价税合计，否则按多key优先级从原始字段匹配
        // 财会版 classifierId=10001 返回英文键（AmountInFiguers=价税合计），中文键为旧混贴模板兜底
        BigDecimal amount = vat != null ? vat.getAmountInFiguers() : null;
        if (amount == null) {
            amount = firstAmount(fields, "AmountInFiguers", "TotalAmount",
                    "价税合计", "合计金额", "实付金额", "发票金额", "票价", "金额", "amount");
        }
        normalized.put("amount", amount);

        // 开票日期：优先增值税识别结果，多key兜底，保留原始中文格式字符串（财会版 InvoiceDate 为"2025年04月02日"中文格式，原样存储供展示/人工复核）
        String date = vat != null && vat.getInvoiceDate() != null ? vat.getInvoiceDate().toString() : null;
        if (date == null) {
            date = firstField(fields, "InvoiceDate", "开票日期", "乘车日期", "日期", "时间", "date");
        }
        normalized.put("date", date);

        // 商户名称：优先增值税销售方名称，多key兜底匹配
        String merchant = vat != null ? vat.getSellerName() : null;
        if (merchant == null) {
            merchant = firstField(fields, "SellerName",
                    "销售方", "销售方名称", "商户名称", "商户", "收款方", "名称", "merchant");
        }
        normalized.put("merchant", merchant);

        // 纳税人识别号：优先增值税销售方税号，多key兜底匹配
        String taxNo = vat != null ? vat.getSellerRegisterNum() : null;
        if (taxNo == null) {
            taxNo = firstField(fields, "SellerRegisterNum", "税号", "纳税人识别号",
                    "sellerRegisterNum", "sellerTaxID");
        }
        normalized.put("taxNo", taxNo);
        return normalized;
    }

    /**
     * 将原始OCR票据类型映射业务大类：INVOICE发票 / ITINERARY行程票据 / OTHER其他
     * @param type ocr原始票据类型
     * @return 业务分类字符串
     */
    private String mapFileType(String type) {
        if (INVOICE_TYPES.contains(type)) {
            return "INVOICE";
        }
        if (ITINERARY_TYPES.contains(type)) {
            return "ITINERARY";
        }
        return "OTHER";
    }

    /**
     * 按key优先级读取金额字段，去除千分位逗号，转换BigDecimal；解析失败返回null
     * @param fields ocr原始字段map
     * @param keys 候选字段key，按优先级顺序
     * @return 解析后BigDecimal金额，解析失败返回null
     */
    private static BigDecimal firstAmount(Map<String, String> fields, String... keys) {
        String text = firstField(fields, keys);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", "").trim());
        } catch (NumberFormatException e) {
            log.debug("OCR 金额字段解析失败: {}", text);
            return null;
        }
    }

    /**
     * 多候选key顺序读取字段，返回第一个非空字符串；兼容中英文OCR字段key
     * @param fields ocr原始字段map
     * @param keys 候选字段key，按优先级顺序
     * @return 第一个非空字段值，全部为空返回null
     */
    private static String firstField(Map<String, String> fields, String... keys) {
        if (fields == null) {
            return null;
        }
        for (String key : keys) {
            String v = fields.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 对象安全转换Long，兼容数字、字符串入参，转换失败返回null
     * <p>适配Agent动态传入的参数，避免类型转换异常</p>
     * @param v 原始参数对象
     * @return Long值，解析失败返回null
     */
    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

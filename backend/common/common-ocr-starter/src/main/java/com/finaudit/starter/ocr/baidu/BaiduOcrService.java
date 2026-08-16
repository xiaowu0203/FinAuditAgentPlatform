package com.finaudit.starter.ocr.baidu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finaudit.starter.ocr.OcrService;
import com.finaudit.starter.ocr.config.OcrProperties;
import com.finaudit.starter.ocr.exception.OcrException;
import com.finaudit.starter.ocr.model.FinanceReceiptOcr;
import com.finaudit.starter.ocr.model.ReceiptItem;
import com.finaudit.starter.ocr.model.VatInvoiceOcr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百度OCR接口统一实现类，实现通用OcrService顶层接口
 * 支持两类票据识别：通用财会混贴票据（POST /rest/2.0/solution/v1/iocr/recognise/finance）、增值税专用/普通发票（POST /rest/2.0/ocr/v1/vat_invoice）
 * 内置能力：token自动缓存预刷新（POST /oauth/2.0/token）、图片大小校验、多格式日期兼容、金额千分位清洗、多返回结构兼容解析、统一异常抛出
 * 适配百度AI开放平台财务专用OCR接口，做业务层封装，屏蔽三方API细节差异
 */
public class BaiduOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(BaiduOcrService.class);

    // 百度OCR基础域名
    private static final String BASE_URL = "https://aip.baidubce.com";
    // 获取access_token接口路径
    private static final String TOKEN_PATH = "/oauth/2.0/token";
    // 智能财会票据识别接口（支持火车票、行程单、定额发票等混合票据）
    private static final String IOCR_FINANCE_PATH = "/rest/2.0/solution/v1/iocr/recognise/finance";
    // 增值税发票专用识别接口
    private static final String VAT_INVOICE_PATH = "/rest/2.0/ocr/v1/vat_invoice";

    /**
     * 财会票据接口固定分类器ID，百度接口强制必填
     * 缺失该参数会返回 216100 invalid_param 参数非法错误
     */
    private static final String FINANCE_CLASSIFIER_ID = "10001";

    /** 百度OCR单张图片最大限制 4MB */
    private static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;

    /**
     * Token安全刷新提前量：提前10分钟刷新token
     * 避免请求时token刚好过期导致鉴权失败
     */
    private static final long TOKEN_SAFETY_MILLIS = 600_000L;

    // 纯数字日期格式 yyyyMMdd
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 增值税发票返回中文日期格式 例：2023年06月19日
     * 官方文档标注格式与实际返回不一致，额外增加兼容解析器
     */
    private static final DateTimeFormatter CN_DATE_FMT = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    // 配置参数
    private final String apiKey;
    private final String secretKey;
    private final int timeoutMs;

    // Spring RestClient 统一HTTP客户端
    private final RestClient restClient;
    // JSON序列化工具，全局复用
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Token缓存变量
     * volatile 保证多线程内存可见性；刷新逻辑加同步锁，防止并发大量重复请求token接口
     */
    private volatile String accessToken;
    private volatile long tokenExpireAtMillis;

    public BaiduOcrService(OcrProperties.Baidu baidu) {
        this.apiKey = baidu.getApiKey();
        this.secretKey = baidu.getSecretKey();
        this.timeoutMs = baidu.getTimeoutMs();
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 通用财会票据识别（火车票、行程单、定额发票、混贴票据）
     * @param image 票据图片二进制字节数组
     * @return 结构化多票据识别结果，支持一张图片多张票据场景
     * @throws OcrException 图片非法、网络异常、百度业务错误、JSON解析异常均抛出统一OCR业务异常
     */
    @Override
    public FinanceReceiptOcr recognizeFinance(byte[] image) {
        // 前置校验图片大小、非空
        validateImage(image);
        // 表单请求：必须携带预置财会分类器ID
        String body = postForm(IOCR_FINANCE_PATH,
                Map.of("classifierId", FINANCE_CLASSIFIER_ID, "image", base64(image)));
        // 解析JSON返回
        JsonNode root = parseRoot(body);
        // 解析兼容单票据/混贴多票据两种返回结构
        return toFinanceResult(root);
    }

    /**
     * 增值税发票专用识别
     * @param image 发票图片二进制字节数组
     * @return 增值税发票结构化实体（发票代码、号码、金额、开票日期等）
     */
    @Override
    public VatInvoiceOcr recognizeVat(byte[] image) {
        // 前置校验图片大小、非空
        validateImage(image);
        // 表单请求
        String body = postForm(VAT_INVOICE_PATH, Map.of("image", base64(image)));
        // 解析JSON返回
        JsonNode root = parseRoot(body);
        // 解析返回结构
        return toVatResult(root);
    }


    // ===================== 内部工具：图片校验、编码、Token =====================


    /**
     * 校验图片基础规则：非空、不超过4M上限（官方限制：≤4M、jpg/jpeg/png/bmp、最短边 ≥15px、最长边 ≤4096px；尺寸校验交图片源）
     * 图片长宽、分辨率校验交由上游文件上传层控制
     * @param image 图片字节数组
     * @throws OcrException 为空/超限抛出异常
     */
    private void validateImage(byte[] image) {
        if (image == null || image.length == 0) {
            throw new OcrException("OCR 图片为空");
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new OcrException("OCR 图片超过 4M 限制，实际 " + image.length + " 字节");
        }
    }

    /**
     * 图片字节数组转Base64字符串，百度接口入参要求
     */
    private String base64(byte[] image) {
        return Base64.getEncoder().encodeToString(image);
    }

    /**
     * 表单POST请求统一封装（image 字段先 base64 再由表单编码器 urlencode）
     * 自动获取有效token拼接URL，表单参数URL编码后提交
     * @param path 接口相对路径
     * @param form 表单键值对
     * @return 接口原始JSON响应字符串
     */
    private String postForm(String path, Map<String, String> form) {
        // 拼接请求路径
        String url = path + "?access_token=" + urlEncode(obtainToken());
        try {
            // 发起请求
            return restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(buildFormBody(form))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // HTTP状态码异常，封装状态码与响应体
            throw new OcrException(e.getStatusCode().value(),
                    "百度 OCR HTTP " + e.getStatusCode() + "：" + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            // 网络超时、连接失败等通用异常
            throw new OcrException("百度 OCR 请求失败：" + e.getMessage(), e);
        }
    }

    /**
     * 拼接application/x-www-form-urlencoded表单字符串
     */
    private String buildFormBody(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(urlEncode(v));
        });
        return sb.toString();
    }

    /**
     * UTF-8 URL编码，防止中文/特殊符号传参报错
     */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 获取可用access_token
     * 缓存未过期直接返回；过期/未初始化同步刷新token，避免并发击穿token接口
     */
    private String obtainToken() {
        String cached = accessToken;
        // 先无锁快速判断
        if (cached != null && System.currentTimeMillis() < tokenExpireAtMillis) {
            return cached;
        }
        // 同步锁防止多线程同时刷新token
        synchronized (this) {
            cached = accessToken;
            if (cached != null && System.currentTimeMillis() < tokenExpireAtMillis) {
                return cached;
            }
            fetchToken();
            return accessToken;
        }
    }

    /**
     * 远程请求token接口，更新内存缓存token与过期时间戳
     */
    private void fetchToken() {
        // 拼接请求URL
        String url = TOKEN_PATH
                + "?grant_type=client_credentials"
                + "&client_id=" + urlEncode(apiKey)
                + "&client_secret=" + urlEncode(secretKey);
        String body;
        try {
            // 发起请求
            body = restClient.post().uri(url).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new OcrException(e.getStatusCode().value(),
                    "百度 OCR token 获取失败 HTTP " + e.getStatusCode() + "：" + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new OcrException("百度 OCR token 获取失败：" + e.getMessage(), e);
        }
        // 解析JSON响应
        JsonNode root = parseRoot(body);
        // 解析token
        String token = root.path("access_token").asText();
        if (token.isBlank()) {
            throw new OcrException("百度 OCR token 响应缺少 access_token：" + body);
        }
        long expiresIn = root.path("expires_in").asLong(0);
        this.accessToken = token;
        // 过期前 10 分钟即刷新；未给 expires_in 时按 24h 兜底
        this.tokenExpireAtMillis = System.currentTimeMillis()
                + (expiresIn > 0 ? Math.max(expiresIn * 1000L - TOKEN_SAFETY_MILLIS, 60_000L)
                : 24L * 3600_000L);
        log.debug("百度 OCR token 已获取，有效期 {}s", expiresIn);
    }

    /**
     * 统一JSON解析入口
     * 自动捕获百度返回error_code业务错误码并抛出OcrException
     * @param body 原始接口返回JSON字符串
     * @return 根JsonNode
     */
    private JsonNode parseRoot(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("error_code") && root.get("error_code").asLong() != 0) {
                throw new OcrException(root.get("error_code").asLong(),
                        "百度 OCR 业务错误：" + root.path("error_msg").asText("") + "（body=" + body + "）");
            }
            return root;
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("百度 OCR 响应解析失败：" + e.getMessage(), e);
        }
    }

    // ===================== 内部解析转换：财会票据、增值税发票 =====================
    /**
     * 解析财会混贴票据接口返回JSON
     * 兼容两种返回结构：
     * 1. 单张标准票据：data.ret 数组，字段word_name/word平铺
     * 2. 多张混贴票据：words_result 数组，每条独立票据type+result字段
     * @param root 接口根JSON节点
     * @return 统一封装的多票据识别实体
     */
    private FinanceReceiptOcr toFinanceResult(JsonNode root) {
        FinanceReceiptOcr result = new FinanceReceiptOcr();
        List<ReceiptItem> items = new ArrayList<>();
        // 形态一：财会版 data.ret[]（单票据）
        JsonNode data = root.path("data");
        JsonNode ret = data.path("ret");
        if (ret.isArray() && ret.size() > 0) {
            ReceiptItem item = new ReceiptItem();
            // type = 分类器模板签名（vat_invoice / train_ticket / …）；缺省用中文模板名占位
            item.setType(data.path("templateSign").asText(data.path("templateName").asText("")));
            Map<String, String> fields = new LinkedHashMap<>();
            int idx = 0;
            for (JsonNode f : ret) {
                String name = fieldName(f, idx);
                fields.put(name, fieldValue(f));
                idx++;
            }
            item.setFields(fields);
            items.add(item);
            result.setItems(items);
            return result;
        }
        // 形态二：混贴票据 words_result[]（多票据）
        JsonNode words = root.path("words_result");
        if (words.isArray()) {
            for (JsonNode node : words) {
                ReceiptItem item = new ReceiptItem();
                item.setType(node.path("type").asText(""));
                JsonNode pos = node.path("position");
                if (pos.isObject()) {
                    item.setLeft(pos.path("left").asInt(0));
                    item.setTop(pos.path("top").asInt(0));
                    item.setWidth(pos.path("width").asInt(0));
                    item.setHeight(pos.path("height").asInt(0));
                }
                Map<String, String> fields = new LinkedHashMap<>();
                JsonNode fieldArr = node.path("result");
                if (fieldArr.isArray()) {
                    int idx = 0;
                    for (JsonNode f : fieldArr) {
                        fields.put(fieldName(f, idx), fieldValue(f));
                        idx++;
                    }
                }
                item.setFields(fields);
                items.add(item);
            }
        }
        result.setItems(items);
        return result;
    }

    /**
     * 获取字段标识名称，多字段key兼容降级策略
     * 优先级 word_name > key > name > 字段序号兜底
     * @param f 单字段JSON节点
     * @param idx 字段数组下标，兜底用
     * @return 标准化字段key
     */
    private static String fieldName(JsonNode f, int idx) {
        String name = firstText(f, "word_name", "key", "name");
        return name == null ? "field_" + idx : name;
    }

    /**
     * 获取字段文本值
     * 优先级 word > value，无值返回空字符串
     */
    private static String fieldValue(JsonNode f) {
        String value = firstText(f, "word", "value");
        return value == null ? "" : value;
    }

    /**
     * 解析增值税专用/普通发票接口返回JSON
     * 抽取核心财税字段，统一清洗金额、日期格式
     * @param root 接口根节点
     * @return 增值税发票结构化实体
     */
    private VatInvoiceOcr toVatResult(JsonNode root) {
        JsonNode wr = root.path("words_result");
        VatInvoiceOcr vat = new VatInvoiceOcr();
        vat.setAmountInFiguers(decimal(wr, "AmountInFiguers"));
        vat.setInvoiceDate(date(wr, "InvoiceDate"));
        vat.setSellerName(wr.path("SellerName").asText(null));
        vat.setSellerRegisterNum(wr.path("SellerRegisterNum").asText(null));
        vat.setInvoiceCode(wr.path("InvoiceCode").asText(null));
        vat.setInvoiceNum(wr.path("InvoiceNum").asText(null));
        vat.setInvoiceType(wr.path("InvoiceType").asText(null));
        vat.setTotalAmount(decimal(wr, "TotalAmount"));
        vat.setTotalTax(decimal(wr, "TotalTax"));
        return vat;
    }

    /**
     * 多key依次读取文本值，任一存在非空则返回
     * @param node 目标JSON节点
     * @param names 优先字段名数组
     * @return 匹配到的文本，无匹配返回null
     */
    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode n = node.get(name);
            if (n != null && n.isValueNode()) {
                String text = n.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /**
     * 金额字段统一处理工具
     * 去除千分位逗号，转换BigDecimal；解析失败打印警告返回null
     * @param root 父JSON节点
     * @param name 金额字段名
     * @return 标准化BigDecimal金额
     */
    private static BigDecimal decimal(JsonNode root, String name) {
        String text = root.path(name).asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", "").trim());
        } catch (NumberFormatException e) {
            log.warn("百度 OCR 金额字段 {} 解析失败：{}", name, text);
            return null;
        }
    }

    /**
     * 日期兼容解析工具
     * 优先纯数字yyyyMMdd，失败自动兼容中文年月日格式；解析失败打印警告返回null
     * @param node 父JSON节点
     * @param name 日期字段名
     * @return LocalDate日期对象
     */
    private static LocalDate date(JsonNode node, String name) {
        String text = node.path(name).asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return LocalDate.parse(trimmed, DATE_FMT);
        } catch (DateTimeParseException ignored) {
            // 兼容中文日期格式
        }
        try {
            return LocalDate.parse(trimmed, CN_DATE_FMT);
        } catch (DateTimeParseException ignored) {
            // 均不匹配
        }
        log.warn("百度 OCR 日期字段 {} 解析失败：{}", name, text);
        return null;
    }

    /**
     * 对外暴露超时配置，供上层工具服务读取熔断/重试策略
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }
}

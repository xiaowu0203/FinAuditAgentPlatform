package com.finaudit.starter.ocr;

import com.finaudit.starter.ocr.baidu.BaiduOcrService;
import com.finaudit.starter.ocr.config.OcrProperties;
import com.finaudit.starter.ocr.exception.OcrException;
import com.finaudit.starter.ocr.model.FinanceReceiptOcr;
import com.finaudit.starter.ocr.model.ReceiptItem;
import com.finaudit.starter.ocr.model.VatInvoiceOcr;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 百度 OCR 冒烟测试：对 {@code docs/ocr-samples} 下的样例票据（AI 生成的测试图）做真实识别，
 * 打印每张图的识别结果（type + 关键字段），验证 common-ocr-starter 能真实打通百度、字段解析可用。
 * <p>与 {@code S3ObjectStorageServiceSmokeTest} / {@code DeepSeekAiClientTest} 同约定：
 * 未设置环境变量密钥时经 {@code Assumptions.assumeTrue} 跳过，不阻塞 CI。</p>
 * <p>密钥：{@code FINAUDIT_OCR_BAIDU_API_KEY} / {@code FINAUDIT_OCR_BAIDU_SECRET_KEY}
 * （CLAUDE.md §6，禁止硬编码）。读取顺序：系统环境变量 → 仓库根 {@code .env}（gitignored，
 * 项目约定密钥存于此，测试兜底解析，无需在终端导出）。样例目录可用 {@code -Docr.samples.dir=...} 覆盖。</p>
 * <p>判定：只要 4 张样本中 ≥1 张被识别（items 非空）即通过；全部失败则红，提示换真实发票。
 * 单张识别失败（如百度 282002 识别错误）记入报告，不使整体失败——这正是「人工录入兜底」的触发面。</p>
 */
class BaiduOcrSamplesSmokeTest {

    /** 增值税发票类型（识别到该类型时补调 vat_invoice 细节） */
    private static final String TYPE_VAT_INVOICE = "vat_invoice";

    @Test
    void recognize_allFinanceSamples_againstBaidu() throws IOException {
        String apiKey = System.getenv("FINAUDIT_OCR_BAIDU_API_KEY");
        String secretKey = System.getenv("FINAUDIT_OCR_BAIDU_SECRET_KEY");
        if ((apiKey == null || apiKey.isBlank()) || (secretKey == null || secretKey.isBlank())) {
            // 环境变量缺省时兜底读仓库根 .env（项目约定存密钥处）
            Map<String, String> fromDotEnv = resolveKeysFromDotEnv();
            apiKey = firstNonBlank(apiKey, fromDotEnv.get("FINAUDIT_OCR_BAIDU_API_KEY"));
            secretKey = firstNonBlank(secretKey, fromDotEnv.get("FINAUDIT_OCR_BAIDU_SECRET_KEY"));
        }
        assumeTrue(apiKey != null && !apiKey.isBlank() && secretKey != null && !secretKey.isBlank(),
                "未设置 FINAUDIT_OCR_BAIDU_API_KEY / FINAUDIT_OCR_BAIDU_SECRET_KEY（可写入仓库根 .env），跳过百度 OCR 真实调用");

        File samplesDir = resolveSamplesDir();
        assumeTrue(samplesDir.isDirectory(), "未找到 docs/ocr-samples（可用 -Docr.samples.dir=... 指定）：" + samplesDir);

        OcrProperties.Baidu cfg = new OcrProperties.Baidu();
        cfg.setApiKey(apiKey);
        cfg.setSecretKey(secretKey);
        OcrService ocr = new BaiduOcrService(cfg);

        // 样本：优先扫 local/（gitignored，放真实发票），再扫根目录（仅非敏感样本）
        List<File> images = new ArrayList<>();
        collectImages(new File(samplesDir, "local"), images);
        collectImages(samplesDir, images);
        assumeTrue(!images.isEmpty(), "docs/ocr-samples 下没有图片样本（png/jpg/jpeg）");

        StringBuilder report = new StringBuilder();
        report.append("\n==== 百度 OCR 样本识别报告（共 ").append(images.size()).append(" 张）====\n");
        int recognized = 0;
        int empty = 0;
        int errors = 0;
        for (File image : images) {
            report.append("【").append(image.getName()).append("】\n");
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(image.toPath());
            } catch (IOException e) {
                errors++;
                report.append("  读取失败: ").append(e.getMessage()).append('\n');
                continue;
            }
            try {
                FinanceReceiptOcr receipt = ocr.recognizeFinance(bytes);
                if (receipt.isEmpty()) {
                    empty++;
                    report.append("  未识别到票据（走 FAILED→人工录入兜底路径）\n");
                    continue;
                }
                recognized++;
                ReceiptItem first = receipt.getItems().get(0);
                report.append("  type=").append(first.getType())
                        .append("（共识别 ").append(receipt.getItems().size()).append(" 张票据）\n");
                appendNormalized(report, first.getType(), first.getFields());
                if (TYPE_VAT_INVOICE.equals(first.getType())) {
                    try {
                        VatInvoiceOcr vat = ocr.recognizeVat(bytes);
                        report.append("  [vat_invoice 细节] 价税合计=").append(vat.getAmountInFiguers())
                                .append(" 开票日期=").append(vat.getInvoiceDate())
                                .append(" 销售方=").append(vat.getSellerName())
                                .append(" 税号=").append(vat.getSellerRegisterNum())
                                .append(" 发票号=").append(vat.getInvoiceNum()).append('\n');
                    } catch (OcrException e) {
                        report.append("  [vat_invoice 细节失败] err=").append(e.getErrorCode())
                                .append(' ').append(truncate(e.getMessage())).append('\n');
                    }
                }
            } catch (OcrException e) {
                errors++;
                report.append("  识别异常 err=").append(e.getErrorCode())
                        .append(' ').append(truncate(e.getMessage())).append('\n');
            }
        }
        report.append("==== 汇总：成功 ").append(recognized).append(" 张，未识别 ").append(empty)
                .append(" 张，异常 ").append(errors).append(" 张 ====\n");
        System.out.println(report);

        assertTrue(recognized > 0,
                "全部 " + images.size() + " 张样本均未被百度识别——建议检查密钥权限/图片质量，或换真实发票复测。\n" + report);
    }

    /** 按 OcrExtractTool.normalize 同一套字段映射打印关键字段（对齐工具层取值逻辑） */
    private static void appendNormalized(StringBuilder sb, String type, Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            sb.append("  fields=空\n");
            return;
        }
        List<String> printed = new ArrayList<>();
        printed.add("amount=" + firstField(fields, "AmountInFiguers", "TotalAmount",
                "价税合计", "合计金额", "实付金额", "发票金额", "票价", "金额", "amount"));
        printed.add("date=" + firstField(fields, "InvoiceDate", "开票日期", "乘车日期", "日期", "时间", "date"));
        printed.add("merchant=" + firstField(fields, "SellerName",
                "销售方", "销售方名称", "商户名称", "商户", "收款方", "名称", "merchant"));
        printed.add("taxNo=" + firstField(fields, "SellerRegisterNum", "税号", "纳税人识别号",
                "sellerRegisterNum", "sellerTaxID"));
        sb.append("  归一化: ").append(String.join(" | ", printed)).append('\n');
        sb.append("  全字段: ").append(fields).append('\n');
    }

    /** 按优先级取第一个非空字段（与工具层 firstField 一致） */
    private static String firstField(Map<String, String> fields, String... keys) {
        for (String key : keys) {
            String v = fields.get(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 收集 dir 下的 png/jpg/jpeg 图片到 out；同文件名去重（local 优先已由调用顺序保证） */
    private static void collectImages(File dir, List<File> out) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        if (files == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (File f : out) {
            seen.add(f.getName());
        }
        for (File f : files) {
            if (seen.add(f.getName())) {
                out.add(f);
            }
        }
    }

    /** 定位 docs/ocr-samples：先看 -Docr.samples.dir，否则从模块目录逐级向上找仓库根 */
    private static File resolveSamplesDir() {
        String override = System.getProperty("ocr.samples.dir");
        if (override != null && !override.isBlank()) {
            return new File(override);
        }
        return walkUp(System.getProperty("user.dir"), "docs/ocr-samples");
    }

    /** 兜底读仓库根 .env（gitignored）：解析 KEY=VALUE，去空白/引号/注释 */
    private static Map<String, String> resolveKeysFromDotEnv() {
        Map<String, String> keys = new java.util.HashMap<>();
        File dotEnv = walkUp(System.getProperty("user.dir"), ".env");
        if (!dotEnv.isFile()) {
            return keys;
        }
        try {
            for (String line : Files.readAllLines(dotEnv.toPath())) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))
                        && value.endsWith(String.valueOf(value.charAt(0)))) {
                    value = value.substring(1, value.length() - 1);
                }
                keys.put(name, value);
            }
        } catch (IOException e) {
            // .env 读不了就回退环境变量，不阻塞
        }
        return keys;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** 从 dir 逐级向上找 target 相对路径的目录/文件 */
    private static File walkUp(String start, String relative) {
        File dir = new File(start);
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.exists()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        return new File(start, relative);
    }
}

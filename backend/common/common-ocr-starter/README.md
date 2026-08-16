# common-ocr-starter

OCR 识别统一抽象。当前实现 **百度智能云票据识别**（P2b D6 已定），面向 `OcrService` 接口编码，留多厂商扩展位。

## 能力
- `OcrService.recognizeFinance(byte[])` **智能财务票据识别（主线）**：返回票据**分类**（`type`，17 种：vat_invoice / train_ticket / taxi_receipt / air_ticket / roll_normal_invoice / toll_invoice / quota_invoice / printed_invoice / bus_ticket / ferry_ticket / taxi_online_ticket / motor_vehicle_invoice / used_vehicle_invoice / limit_invoice / shopping_receipt / pos_invoice / others）+ 结构化字段；财会版默认单票据、字段平铺于 `data.ret[]`，混贴模板才含多票据+位置（结构见「备注」）
- `OcrService.recognizeVat(byte[])` **增值税发票识别（细节兜底）**：补税号/金额细节（`SellerRegisterNum` / `SellerName` / `AmountInFiguers` / `InvoiceDate` 等）
- access_token 内存缓存（过期前自动刷新），不引官方 SDK，`RestClient` 直连
- 非 200 / 业务 `error_code` 非 0 → 抛 `OcrException`（unchecked，由工具层重试兜底）
- 金额字段一律 **BigDecimal**（千分位逗号已清洗，CLAUDE.md §5.3）

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-ocr-starter</artifactId>
  <version>${project.version}</version>
</dependency>
```

```yaml
finaudit:
  ocr:
    baidu:
      api-key: ${FINAUDIT_OCR_BAIDU_API_KEY}        # 百度智能云 AK
      secret-key: ${FINAUDIT_OCR_BAIDU_SECRET_KEY}  # SK
      # timeout-ms: 10000                           # 单次识别超时，缺省 10s
```

> 凭据一律经环境变量注入（CLAUDE.md §6），禁止硬编码。

**启动自检**：引入本 starter 即代表工程需要使用 OCR，`CommonOcrAutoConfiguration` 启动时校验
`api-key / secret-key`，缺失直接启动失败并给出明确提示（配置位置 + 环境变量名），不静默跳过——业务工程无需自行校验。
若业务工程自研 `OcrService` Bean，凭据由其自管，本 starter 自动让位。

## 完整配置项
| 前缀 `finaudit.ocr` | 默认值 | 说明 |
|---|---|---|
| `baidu.api-key` | - | 百度 API Key（未配置启动即失败并提示，见「启动自检」） |
| `baidu.secret-key` | - | 百度 Secret Key |
| `baidu.timeout-ms` | `10000` | 单次识别超时（毫秒） |

## 备注
- **finance 接口必传 `classifierId=10001`**（财会票据预置分类器，百度官方固定值）。缺失/非法时百度报 `216100 invalid param, classifierId is not number`——已踩坑，客户端已内置，勿在业务侧重复传。
- **返回形态（实测 2026-08，与百度文档描述有出入，以实测为准）**：
  - 财会版 `classifierId=10001`（默认）：单票据。`data.ret[]` 为 `{word_name, word}` 平铺字段对，**票据类型在 `data.templateSign`**（如 vat_invoice/train_ticket）；无 `words_result`、无 position。
  - 混贴票据模板（`templateSign=mixed_receipt`）：`words_result[]`，每项 `{type, result[], position}`。
  - `vat_invoice` 细节接口：字段**包在 `words_result.<字段名>` 下**（非 root 层），`InvoiceDate` 为中文格式 `yyyy年MM月dd日`（非文档所述 YYYYMMDD）。
- 识别失败形态：`272001 classify failed`（分类失败）/ `282103 failed to match the template`（分类成功但版式不匹配）→ 工具层「FAILED→人工录入」兜底。
- 字段归一化：amount/date/merchant/taxNo 映射在 tool-service 的 `OcrExtractTool`（财会版英文键 `AmountInFiguers`/`InvoiceDate`/`SellerName`/`SellerRegisterNum` 优先，中文键兜底）。`ReceiptItem.fields` 透出百度原始字段名→值。
- 图片限制（百度官方）：`image` 参数为 base64 后的图片字节，≤4M，jpg/jpeg/png/bmp，最短边 ≥15px，最长边 ≤4096px；超 4M 在客户端直接抛 `OcrException`，尺寸校验交图片源。
- 接入新厂商：实现 `OcrService` 接口 + 在 `CommonOcrAutoConfiguration` 增加实现 Bean 即可，业务侧无感。

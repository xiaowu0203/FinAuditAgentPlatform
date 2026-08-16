# ocr-samples

OCR 冒烟测试样本目录（`common-ocr-starter` 的 `BaiduOcrSamplesSmokeTest` 默认扫描本目录）。

## 目录约定（重要）

- **本目录只提交非敏感样本**（AI 生成的示意票据、脱敏样例等）。
- **真实发票一律放 `local/` 子目录**，该目录已在 `.gitignore` 中排除，**禁止入库**
  （真实发票含税号、商户、购买方等敏感信息，遵循 CLAUDE.md §6 密钥/敏感数据不入库的精神）。
- 冒烟测试扫描顺序：`local/`（本地真实发票）→ 本目录（非敏感样本）；无样本时测试跳过。

## 用法

- 密钥：仓库根 `.env` 写入 `FINAUDIT_OCR_BAIDU_API_KEY` / `FINAUDIT_OCR_BAIDU_SECRET_KEY`
- 跑测试：
  ```
  mvn -f backend/pom.xml -pl common/common-ocr-starter -am test -Dtest=BaiduOcrSamplesSmokeTest
  ```
- 自定义样本目录：`-Docr.samples.dir=<路径>`

# common-model-starter

多模型统一封装（抽象骨架，P0）。

## 能力（P0）
- `ModelType` 枚举：DEEPSEEK / QWEN / CLAUDE
- `ModelProperties`：`finaudit.model.*` 配置项（type / modelName / apiKey / baseUrl / timeoutSeconds）
- `AiClient` / `ChatClientFactory` 抽象接口

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-model-starter</artifactId>
</dependency>
```

## 规划（P1）
- 基于 Spring AI 实现 DeepSeek / Qwen / Claude 客户端
- 统一密钥管理、Token 消耗统计、故障自动切换备用模型
- 默认模型 DeepSeek

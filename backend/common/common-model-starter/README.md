# common-model-starter（模型统一接入）

> 多模型统一封装：抽象接口 + 配置 + 实现（P1 接入 DeepSeek）。

## 能力

| 能力 | 说明 |
|---|---|
| `AiClient` | 统一对话抽象：`chat(system, user)` / `chatWithUsage(...)` 返回文本 + Token 用量 |
| `DeepSeekAiClient` | DeepSeek 实现（Spring AI `OpenAiChatModel`，OpenAI 兼容端点） |
| `ChatClientFactory` | 按 `ModelType` 取客户端；统一 Token 统计；DeepSeek 故障时切备用模型（P2 填充 Qwen/Claude） |
| `ModelProperties` | `finaudit.model.*` 配置绑定 |

## 配置（application.yml）

```yaml
finaudit:
  model:
    type: DEEPSEEK          # 默认模型类型
    model-name: deepseek-chat
    api-key: ${FINAUDIT_MODEL_API_KEY}   # 密钥走环境变量，禁止硬编码
    base-url: ${FINAUDIT_MODEL_BASE_URL:} # 留空用默认 https://api.deepseek.com
    fallback-type:            # 备用模型（P1 可留空）
```

## 使用

```java
@Autowired
private ChatClientFactory modelFactory;

String reply = modelFactory.getClient(ModelType.DEEPSEEK).chat(systemPrompt, userPrompt);
DefaultChatClientFactory factory = (DefaultChatClientFactory) modelFactory;
log.info("模型用量: {}", factory.usageSnapshot().summary());
```

## 验证

```bash
# 真实调用（需先设置环境变量，见 .env.example）
export FINAUDIT_MODEL_API_KEY=sk-xxx
mvn -pl common/common-model-starter -am test
```

> 未设置 key 时 `chat_returnsReplyFromDeepSeek` 测试自动跳过，其余单元测试正常通过。

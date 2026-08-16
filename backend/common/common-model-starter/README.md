# common-model-starter（模型统一接入）

> 多模型统一封装：抽象接口 + 配置 + 实现（P1 接入 DeepSeek）。

## 能力

| 能力 | 说明 |
|---|---|
| `AiClient` | 统一对话抽象：`chat(system, user)` / `chatWithUsage(...)` 返回文本 + Token 用量；`chatStructured(...)` 结构化输出（按目标类型返回反序列化对象） |
| `StructuredChatReply<T>` | 结构化回复：`data`（类型化结果）+ `text`（原始文本）+ `usage`（Token 用量） |
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

// 普通文本对话
String reply = modelFactory.getClient(ModelType.DEEPSEEK).chat(systemPrompt, userPrompt);

// 结构化输出：按目标类型返回反序列化结果（形状由 Spring AI BeanOutputConverter 生成的
// JSON Schema 注入提示词约束，模型无关；经工厂调用自动计入 Token 统计）
public record AuditConclusion(String summary, String decision) {}

StructuredChatReply<AuditConclusion> r = modelFactory.getClient(ModelType.DEEPSEEK)
        .chatStructured(systemPrompt, userPrompt, AuditConclusion.class);
AuditConclusion conclusion = r.data();

DefaultChatClientFactory factory = (DefaultChatClientFactory) modelFactory;
log.info("模型用量: {}", factory.usageSnapshot().summary());
```

> 结构化输出支持泛型目标类型（如 `List<...>`），传 `new ParameterizedTypeReference<>(){}` 即可；解析失败自动重试一次（追加纠错指令），仍失败抛异常由调用方回退。

## 验证

```bash
# 真实调用（需先设置环境变量，见 .env.example）
export FINAUDIT_MODEL_API_KEY=sk-xxx
mvn -pl common/common-model-starter -am test
```

> 未设置 key 时 `chat_returnsReplyFromDeepSeek` 测试自动跳过，其余单元测试正常通过。

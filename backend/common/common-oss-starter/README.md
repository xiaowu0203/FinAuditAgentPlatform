# common-oss-starter

对象存储统一抽象。基于 **AWS S3 SDK v2**，通过配置在 **MinIO（默认）** 与 **腾讯云 COS** 间切换，无需改业务代码。

## 能力
- 统一接口 `ObjectStorageService`：上传 / 读取 / 删除 / 存在性 / **预签名 URL**（前端直传、直读）
- 默认桶便捷方法（不带 bucket 参数即走 `defaultBucket`）
- 启动期自动确认默认桶存在（不存在则创建，失败仅告警不阻断启动）
- 自动配置开关：`finaudit.oss.enabled=true` 才加载 Bean，避免无关服务拉起 S3 连接；生效时启动自检凭据（accessKey/secretKey、COS 的 endpoint），缺失直接失败并提示

## 使用
```xml
<dependency>
  <groupId>com.finaudit</groupId>
  <artifactId>common-oss-starter</artifactId>
  <version>${project.version}</version>
</dependency>
```

### MinIO（默认）
```yaml
finaudit:
  oss:
    enabled: true
    provider: minio                # 默认即 minio，可省略
    endpoint: http://127.0.0.1:9000
    access-key: ${MINIO_ACCESS_KEY}
    secret-key: ${MINIO_SECRET_KEY}
    # region: us-east-1            # MinIO 无效可忽略
    default-bucket: finaudit-file
```

### 腾讯云 COS（远程）
```yaml
finaudit:
  oss:
    enabled: true
    provider: cos
    endpoint: https://cos.ap-guangzhou.myqcloud.com   # S3 兼容 endpoint
    access-key: ${COS_SECRET_ID}                      # SecretId
    secret-key: ${COS_SECRET_KEY}                     # SecretKey
    region: ap-guangzhou                              # 与桶所在地域一致
    default-bucket: your-bucket-1250000000
    path-style: false                                 # COS 为虚拟主机风格，需关闭路径风格
```

> 凭据一律经环境变量注入（CLAUDE.md §6），禁止硬编码。

**启动自检**：`finaudit.oss.enabled=true` 时，`CommonOssAutoConfiguration` 启动即校验
`access-key / secret-key` 是否配置（COS 还需 `endpoint`），缺失直接启动失败并给出明确提示——替代运行期首个请求才暴露的 403 报错。业务工程无需自行校验。

## 完整配置项
| 前缀 `finaudit.oss` | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 是否启用对象存储自动配置 |
| `provider` | `minio` | `minio` / `cos`，决定默认 endpoint/region 兜底 |
| `endpoint` | 见下 | S3 兼容 endpoint；`minio` 默认 `http://localhost:9000`，`cos` 必须显式填写 |
| `access-key` / `secret-key` | - | 凭据（环境变量注入） |
| `region` | `us-east-1` | COS 需与桶所在地域一致 |
| `default-bucket` | `finaudit-file` | 默认桶（docker-compose 的 minio-init 已预建） |
| `path-style` | `true` | 路径风格寻址；COS 需设 `false` |
| `presign-expire-minutes` | `15` | 预签名 URL 有效期（分钟） |

## 备注
- MinIO 与 COS 均为 S3 兼容，客户端统一走 AWS SDK v2；版本由根 pom `dependencyManagement` 的 `aws-sdk-bom` 锁定（2.53.1）。
- `path-style`：MinIO 必须为 `true`（未配置虚拟主机别名）；COS 默认虚拟主机风格，需显式 `false`。
- 幂等：`putObject` 返回完整对象 key（`bucket/key`），便于 DB 落库与追溯。

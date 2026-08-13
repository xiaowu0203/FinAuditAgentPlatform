# 部署文档

待补充：Docker 部署教程、环境启动教程、中间件启停脚本、环境配置清单。

## Nacos 初始化

```bash
bash docs/deploy/nacos-init.sh
```

- 前提：本机 Nacos 3.2.2 已运行（核心服务 8848 + 控制台 8080）
- 作用：创建 `dev`/`test` 命名空间，发布 3 个共享配置占位
  - `common-datasource.yaml` / `common-redis.yaml` / `common-model-keys.properties`
- 幂等：命名空间已存在则跳过，配置重复发布为覆盖
- 密钥策略：配置内全部使用 `${ENV_VAR:default}` 占位，真实密钥走环境变量，禁止入库

### Nacos 3.x 说明（前后端分离）

Nacos 3.x 控制台已独立部署，初始化脚本适配此架构：

| 能力 | 地址 | 端点 |
|---|---|---|
| 登录获取 accessToken | 核心服务 8848 | `POST /nacos/v1/auth/login` |
| 命名空间管理 | 控制台 8080 | `/v3/console/core/namespace*` |
| 配置发布/查询/删除 | 控制台 8080 | `/v3/console/cs/config` |

控制台 API 统一路径格式：`/v3/console/[module]/[subPath]`，module 含 `server/cs/ns/core`。
鉴权默认开启，请求头携带 `accessToken: <token>`。
若控制台端口/上下文有修改，通过 `NACOS_CONSOLE_ADDR` 环境变量覆盖。

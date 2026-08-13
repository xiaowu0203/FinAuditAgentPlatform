#!/usr/bin/env bash
# Nacos 初始化脚本（适配 Nacos 3.x 前后端分离架构）
# 用法: ./docs/deploy/nacos-init.sh   （依赖 curl）
# 说明:
#   - Nacos 3.x 控制台已独立部署：核心服务登录走 8848，控制台 API 走 8080（NACOS_CONSOLE_ADDR 可改）
#   - 配置仅作占位，密钥一律走环境变量（见 .env.example）
#   - 幂等：命名空间已存在则跳过，配置重复发布为覆盖

set -euo pipefail

NACOS_CORE_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
NACOS_CONSOLE_ADDR="${NACOS_CONSOLE_ADDR:-127.0.0.1:8080}"
NACOS_USER="${NACOS_USERNAME:-nacos}"
NACOS_PASS="${NACOS_PASSWORD:-nacos}"
GROUP="DEFAULT_GROUP"

echo "==> 登录 Nacos 核心服务: ${NACOS_CORE_ADDR}"
TOKEN=$(curl -s -X POST "http://${NACOS_CORE_ADDR}/nacos/v1/auth/login" \
  -d "username=${NACOS_USER}&password=${NACOS_PASS}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

if [ -z "${TOKEN}" ]; then
  echo "!! 登录失败，请检查 Nacos 账号密码" >&2
  exit 1
fi

# Nacos 3.x 控制台 API 基址（统一路径: /v3/console/[module]/[subPath]）
API="http://${NACOS_CONSOLE_ADDR}/v3/console"

# 创建命名空间（已存在则跳过）
ensure_namespace() {
  local ns="$1"
  local exists
  exists=$(curl -s "${API}/core/namespace/exist?customNamespaceId=${ns}" -H "accessToken: ${TOKEN}")
  if echo "${exists}" | grep -q '"data":true'; then
    echo "==> 命名空间 ${ns} 已存在，跳过"
    return
  fi
  echo "==> 创建命名空间: ${ns}"
  curl -s -X POST "${API}/core/namespace" \
    -H "accessToken: ${TOKEN}" \
    -d "customNamespaceId=${ns}&namespaceName=${ns}&namespaceDesc=${ns}-environment" || true
}

# 发布配置（幂等覆盖）
publish_config() {
  local ns="$1" data_id="$2" type="$3" content="$4"
  echo "==> 发布 ${ns}/${data_id}"
  curl -s -X POST "${API}/cs/config" \
    -H "accessToken: ${TOKEN}" \
    --data-urlencode "dataId=${data_id}" \
    --data-urlencode "groupName=${GROUP}" \
    --data-urlencode "namespaceId=${ns}" \
    --data-urlencode "type=${type}" \
    --data-urlencode "content=${content}" >/dev/null || true
}

for ns in dev test; do
  ensure_namespace "${ns}"
done

# 共享配置占位（密钥一律走环境变量占位，Spring 启动时解析 ${ENV_VAR}）
COMMON_DB='spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/finaudit?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:root123456}
    driver-class-name: com.mysql.cj.jdbc.Driver'

COMMON_REDIS='spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}'

COMMON_MODEL_KEYS='# 模型密钥占位：真实密钥走环境变量 FINAUDIT_MODEL_API_KEY，禁止提交
finaudit.model.api-key=${FINAUDIT_MODEL_API_KEY}'

for ns in dev test; do
  publish_config "${ns}" "common-datasource.yaml" "yaml" "${COMMON_DB}"
  publish_config "${ns}" "common-redis.yaml" "yaml" "${COMMON_REDIS}"
  publish_config "${ns}" "common-model-keys.properties" "properties" "${COMMON_MODEL_KEYS}"
done

echo "==> Nacos 初始化完成"

#!/usr/bin/env bash
# Nacos 初始化脚本：创建 dev/test 命名空间 + 发布共享配置占位
# 用法: ./docs/deploy/nacos-init.sh   （依赖 curl）
# 说明: 本地已有 Nacos 时执行；配置仅作占位，密钥一律走环境变量。

set -euo pipefail

NACOS_ADDR="${NACOS_SERVER_ADDR:-127.0.0.1:8848}"
NACOS_USER="${NACOS_USERNAME:-nacos}"
NACOS_PASS="${NACOS_PASSWORD:-nacos}"

echo "==> 登录 Nacos: ${NACOS_ADDR}"
TOKEN=$(curl -s -X POST "http://${NACOS_ADDR}/nacos/v1/auth/login" \
  -d "username=${NACOS_USER}&password=${NACOS_PASS}" \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

if [ -z "${TOKEN}" ]; then
  echo "!! 登录失败，请检查 Nacos 账号密码" >&2
  exit 1
fi

# 创建命名空间（已存在则忽略错误）
for ns in dev test; do
  echo "==> 创建命名空间: ${ns}"
  curl -s -X POST "http://${NACOS_ADDR}/nacos/v1/console/namespaces" \
    -H "accessToken: ${TOKEN}" \
    -d "customNamespaceId=${ns}&namespaceName=${ns}&namespaceDesc=${ns}-environment" \
    || true
done

# 发布共享配置占位（Group 统一用 DEFAULT_GROUP）
publish_config() {
  local ns="$1" data_id="$2" content="$3"
  echo "==> 发布 ${ns}/${data_id}"
  curl -s -X POST "http://${NACOS_ADDR}/nacos/v1/cs/configs" \
    -H "accessToken: ${TOKEN}" \
    --data-urlencode "tenant=${ns}" \
    --data-urlencode "dataId=${data_id}" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=${content}" >/dev/null || true
}

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

for ns in dev test; do
  publish_config "${ns}" "common-datasource.yaml" "${COMMON_DB}"
  publish_config "${ns}" "common-redis.yaml" "${COMMON_REDIS}"
done

echo "==> Nacos 初始化完成"

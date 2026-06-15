#!/usr/bin/env bash
# Keycloak realm 初始化 - 创建 onelake realm + 角色 + 客户端
# 严格对应《技术初始化文档》§八 校验清单第 2 条
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8081}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM="${REALM:-onelake}"

echo "[keycloak] 登录 admin..."
TOKEN=$(curl -sS -X POST \
  -d "client_id=admin-cli" \
  -d "username=${KC_ADMIN}" \
  -d "password=${KC_ADMIN_PASSWORD}" \
  -d "grant_type=password" \
  "${KC_URL}/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

echo "[keycloak] 创建 realm: ${REALM}"
curl -sS -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"realm":"'"${REALM}'","enabled":true}' \
  "${KC_URL}/admin/realms" || true

echo "[keycloak] 创建角色: DE / ADMIN / CONSUMER / SEC / OPS"
for ROLE in DE ADMIN CONSUMER SEC OPS; do
  curl -sS -X POST \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{"name":"'"${ROLE}"'","description":"OneLake role '"${ROLE}"'"}' \
    "${KC_URL}/admin/realms/${REALM}/roles" || true
done

echo "[keycloak] 创建客户端: onelake-app"
curl -sS -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"clientId":"onelake-app","enabled":true,"publicClient":true,"standardFlowEnabled":true,"directAccessGrantsEnabled":true,"redirectUris":["http://localhost:5173/*"]}' \
  "${KC_URL}/admin/realms/${REALM}/clients" || true

echo "[keycloak] realm=${REALM} 初始化完成。"

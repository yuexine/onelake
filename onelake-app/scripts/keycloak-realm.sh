#!/usr/bin/env bash
# Keycloak realm 初始化 - 创建 onelake realm + 角色 + 客户端 + 本地开发用户
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8081}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM="${REALM:-onelake}"
CLIENT_ID="${CLIENT_ID:-onelake-app}"
DEV_USERNAME="${DEV_USERNAME:-dev}"
DEV_PASSWORD="${DEV_PASSWORD:-dev123456}"
DEV_EMAIL="${DEV_EMAIL:-dev@onelake.local}"
DEV_TENANT_ID="${DEV_TENANT_ID:-11111111-1111-1111-1111-111111111111}"

api() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  if [ -n "${body}" ]; then
    curl -sS -X "${method}" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "${body}" \
      "${KC_URL}${path}"
  else
    curl -sS -X "${method}" \
      -H "Authorization: Bearer ${TOKEN}" \
      "${KC_URL}${path}"
  fi
}

echo "[keycloak] 登录 admin..."
TOKEN=$(curl -sS -X POST \
  -d "client_id=admin-cli" \
  -d "username=${KC_ADMIN}" \
  -d "password=${KC_ADMIN_PASSWORD}" \
  -d "grant_type=password" \
  "${KC_URL}/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

echo "[keycloak] 创建 realm: ${REALM}"
api POST "/admin/realms" "$(python3 - <<PY
import json
print(json.dumps({"realm": "${REALM}", "enabled": True}))
PY
)" >/dev/null || true

echo "[keycloak] 创建角色: DE / ADMIN / CONSUMER / SEC / OPS"
for ROLE in DE ADMIN CONSUMER SEC OPS; do
  api POST "/admin/realms/${REALM}/roles" "$(python3 - <<PY
import json
print(json.dumps({"name": "${ROLE}", "description": "OneLake role ${ROLE}"}))
PY
)" >/dev/null || true
done

echo "[keycloak] 创建客户端: ${CLIENT_ID}"
CLIENT_BODY=$(python3 - <<PY
import json
print(json.dumps({
  "clientId": "${CLIENT_ID}",
  "enabled": True,
  "publicClient": True,
  "standardFlowEnabled": True,
  "directAccessGrantsEnabled": True,
  "redirectUris": ["http://localhost:5173/sso/callback", "http://localhost:5173/*"],
  "webOrigins": ["http://localhost:5173"],
  "attributes": {
    "pkce.code.challenge.method": "S256"
  }
}))
PY
)
api POST "/admin/realms/${REALM}/clients" "${CLIENT_BODY}" >/dev/null || true

CLIENT_UUID=$(api GET "/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
api PUT "/admin/realms/${REALM}/clients/${CLIENT_UUID}" "${CLIENT_BODY}" >/dev/null

echo "[keycloak] 配置用户属性: tenant_id"
PROFILE=$(api GET "/admin/realms/${REALM}/users/profile" | python3 -c 'import json,sys; p=json.load(sys.stdin); attrs=p.setdefault("attributes", []); names={a.get("name") for a in attrs};
if "tenant_id" not in names:
    attrs.append({"name":"tenant_id","displayName":"Tenant ID","validations":{"length":{"max":36}},"permissions":{"view":["admin","user"],"edit":["admin"]},"multivalued":False})
print(json.dumps(p))')
api PUT "/admin/realms/${REALM}/users/profile" "${PROFILE}" >/dev/null

echo "[keycloak] 创建 token mapper: tenant_id"
api POST "/admin/realms/${REALM}/clients/${CLIENT_UUID}/protocol-mappers/models" "$(python3 - <<PY
import json
print(json.dumps({
  "name": "tenant_id",
  "protocol": "openid-connect",
  "protocolMapper": "oidc-usermodel-attribute-mapper",
  "config": {
    "user.attribute": "tenant_id",
    "claim.name": "tenant_id",
    "jsonType.label": "String",
    "id.token.claim": "true",
    "access.token.claim": "true",
    "userinfo.token.claim": "true"
  }
}))
PY
)" >/dev/null || true

echo "[keycloak] 创建本地开发用户: ${DEV_USERNAME}"
USER_BODY=$(python3 - <<PY
import json
print(json.dumps({
  "username": "${DEV_USERNAME}",
  "enabled": True,
  "email": "${DEV_EMAIL}",
  "firstName": "OneLake",
  "lastName": "Developer",
  "attributes": {"tenant_id": ["${DEV_TENANT_ID}"]}
}))
PY
)
api POST "/admin/realms/${REALM}/users" "${USER_BODY}" >/dev/null || true

USER_ID=$(api GET "/admin/realms/${REALM}/users?username=${DEV_USERNAME}" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')

api PUT "/admin/realms/${REALM}/users/${USER_ID}" "${USER_BODY}" >/dev/null
api PUT "/admin/realms/${REALM}/users/${USER_ID}/reset-password" "$(python3 - <<PY
import json
print(json.dumps({"type": "password", "value": "${DEV_PASSWORD}", "temporary": False}))
PY
)" >/dev/null

echo "[keycloak] 绑定用户角色: DE / ADMIN / CONSUMER / SEC / OPS"
ROLE_PAYLOAD=$(api GET "/admin/realms/${REALM}/roles" | python3 -c 'import json,sys; roles=json.load(sys.stdin); wanted={"DE","ADMIN","CONSUMER","SEC","OPS"}; print(json.dumps([{"id": r["id"], "name": r["name"]} for r in roles if r["name"] in wanted]))')
api POST "/admin/realms/${REALM}/users/${USER_ID}/role-mappings/realm" "${ROLE_PAYLOAD}" >/dev/null || true

echo "[keycloak] realm=${REALM} 初始化完成。"
echo "[keycloak] dev login: ${DEV_USERNAME} / ${DEV_PASSWORD}"

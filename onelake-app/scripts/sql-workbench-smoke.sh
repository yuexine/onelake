#!/usr/bin/env bash
# SQL 工作台 Sprint 1 冒烟用例。
#
# 用法：
#   BASE=http://localhost:9080 TOKEN=... scripts/sql-workbench-smoke.sh
#
# 前置：
#   - bootstrap 已在 :8080 或经 APISIX :9080 可达
#   - 测试用户 JWT（DE 角色）已获取并写入 TOKEN
#   - 当前租户内 Catalog 至少有一张可查询表（这里用 SHOW SCHEMAS 跳过 Catalog 依赖）
#
# 覆盖：
#   - estimate / submit / query / cancel
#   - export csv / tsv
#   - saved-queries CRUD
#   - history
set -euo pipefail

BASE="${BASE:-http://localhost:9080}"
TOKEN="${TOKEN:?TOKEN is required, pass a valid Keycloak JWT}"
TRACE="${TRACE:-smoke-$(date +%s)}"

curl_json() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$BASE/api/v1/lakehouse/sql$path" \
      -H "Authorization: Bearer $TOKEN" \
      -H "X-Trace-Id: $TRACE" \
      -H "Content-Type: application/json" \
      --data "$body"
  else
    curl -sS -X "$method" "$BASE/api/v1/lakehouse/sql$path" \
      -H "Authorization: Bearer $TOKEN" \
      -H "X-Trace-Id: $TRACE"
  fi
}

echo "==> 1. estimate (SHOW SCHEMAS，无 Catalog 依赖)"
curl_json POST /estimate '{"sql":"SHOW SCHEMAS","engine":"auto","resourceGroup":"rg-default"}' | jq .

echo "==> 2. submit (异步执行 SHOW SCHEMAS)"
RUN_ID=$(curl_json POST /queries '{"sql":"SHOW SCHEMAS","engine":"auto","resourceGroup":"rg-default"}' | jq -r '.data.historyId')
echo "historyId=$RUN_ID"

echo "==> 3. poll query until terminal"
for i in $(seq 1 10); do
  STATUS=$(curl_json GET "/queries/$RUN_ID" | tee /tmp/sql-query-$TRACE.json | jq -r '.data.status')
  echo "  poll #$i status=$STATUS"
  [[ "$STATUS" == "RUNNING" ]] || break
  sleep 1
done
jq '.data | {status, rowCount, durationMs, scanBytes, trinoQueryId}' /tmp/sql-query-$TRACE.json

echo "==> 4. export CSV"
curl -sS -X POST "$BASE/api/v1/lakehouse/sql/export?format=csv" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Trace-Id: $TRACE" \
  -H "Content-Type: application/json" \
  --data '{"sql":"SHOW SCHEMAS","engine":"auto","resourceGroup":"rg-default"}' \
  -D /tmp/sql-export-headers-$TRACE.txt \
  -o /tmp/sql-export-$TRACE.csv
echo "--- headers ---"; cat /tmp/sql-export-headers-$TRACE.txt
echo "--- first 3 lines ---"; head -n 3 /tmp/sql-export-$TRACE.csv

echo "==> 5. export TSV"
curl -sS -X POST "$BASE/api/v1/lakehouse/sql/export?format=tsv" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Trace-Id: $TRACE" \
  -H "Content-Type: application/json" \
  --data '{"sql":"SHOW SCHEMAS","engine":"auto","resourceGroup":"rg-default"}' \
  -o /tmp/sql-export-$TRACE.tsv
echo "--- first 3 lines ---"; head -n 3 /tmp/sql-export-$TRACE.tsv

echo "==> 6. save query (create)"
SAVED_ID=$(curl_json POST /saved-queries '{"name":"smoke-test-query","sql":"SHOW SCHEMAS","shared":false}' | tee /tmp/sql-save-$TRACE.json | jq -r '.data.id')
jq '.data' /tmp/sql-save-$TRACE.json

echo "==> 7. save query (update share=true)"
curl_json PUT "/saved-queries/$SAVED_ID" '{"name":"smoke-test-query","sql":"SHOW SCHEMAS","shared":true}' | jq '.data'

echo "==> 8. saved queries list"
curl_json GET /saved-queries | jq '.data[] | select(.id=="'$SAVED_ID'")'

echo "==> 9. delete saved query"
curl_json DELETE "/saved-queries/$SAVED_ID" | jq '.code'

echo "==> 10. history (最近 50 条)"
curl_json GET /history | jq '.data[0:3] | .[] | {id, status, sql, durationMs}'

# 取消链路需要一条运行中的长查询；这里只做接口 smoke，不做真实取消。
echo "==> 11. cancel (dry-run: 应返回 40404 或历史状态)"
curl_json POST "/queries/00000000-0000-0000-0000-000000000000/cancel" || true

echo
echo "✅ SQL 工作台冒烟用例完成"
echo "   产物：/tmp/sql-*-$TRACE.*"

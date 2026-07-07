#!/usr/bin/env bash
set -euo pipefail

APISIX_ADMIN_URL="${APISIX_ADMIN_URL:-http://localhost:9180/apisix/admin}"
APISIX_ADMIN_KEY="${APISIX_ADMIN_KEY:-edd1c9f034335f136f87ad84b625c8f1}"
BACKEND_UPSTREAM="${BACKEND_UPSTREAM:-host.docker.internal:8080}"

echo "==> Register APISIX route /api/* -> ${BACKEND_UPSTREAM}"
curl -fsS -X PUT "${APISIX_ADMIN_URL}/routes/onelake-control-api" \
  -H "X-API-KEY: ${APISIX_ADMIN_KEY}" \
  -H "Content-Type: application/json" \
  --data "{
    \"name\": \"onelake-control-api\",
    \"uri\": \"/api/*\",
    \"upstream\": {
      \"type\": \"roundrobin\",
      \"nodes\": {
        \"${BACKEND_UPSTREAM}\": 1
      }
    }
  }"
echo

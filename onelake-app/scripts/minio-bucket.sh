#!/usr/bin/env bash
# MinIO bucket 初始化
set -euo pipefail
MC="${MC:-mc}"
MC_ALIAS="${MC_ALIAS:-local}"
MC_URL="${MC_URL:-http://localhost:9000}"
MC_USER="${MC_USER:-minio}"
MC_PASS="${MC_PASS:-minio12345}"

echo "[minio] 配置 alias ${MC_ALIAS} -> ${MC_URL}"
"${MC}" alias set "${MC_ALIAS}" "${MC_URL}" "${MC_USER}" "${MC_PASS}" --api S3v4

for BUCKET in "$@"; do
  echo "[minio] 创建 bucket: ${BUCKET}"
  "${MC}" mb -p "${MC_ALIAS}/${BUCKET}" || true
done

echo "[minio] 完成。"

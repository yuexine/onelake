#!/usr/bin/env bash
# MinIO bucket 初始化
set -euo pipefail
MC="${MC:-mc}"
MC_ALIAS="${MC_ALIAS:-local}"
MC_URL="${MC_URL:-http://localhost:9000}"
MC_USER="${MC_USER:-minio}"
MC_PASS="${MC_PASS:-minio12345}"

run_mc() {
  if command -v "${MC}" >/dev/null 2>&1; then
    "${MC}" "$@"
    return
  fi

  if ! command -v docker >/dev/null 2>&1; then
    echo "[minio] 未找到 ${MC}，且 docker 不可用。请安装 MinIO Client 或 Docker。" >&2
    exit 127
  fi

  local network="${MINIO_MC_NETWORK:-$(basename "${PWD}")_default}"
  local container_url="${MINIO_MC_URL:-http://minio:9000}"
  local config_dir="${MINIO_MC_CONFIG_DIR:-${TMPDIR:-/tmp}/onelake-minio-mc}"
  local rewritten_args=()
  local arg

  mkdir -p "${config_dir}"

  for arg in "$@"; do
    if [[ "${arg}" == "${MC_URL}" ]]; then
      rewritten_args+=("${container_url}")
    else
      rewritten_args+=("${arg}")
    fi
  done

  docker run --rm \
    --network "${network}" \
    -v "${config_dir}:/tmp/.mc" \
    minio/mc --config-dir /tmp/.mc "${rewritten_args[@]}"
}

echo "[minio] 配置 alias ${MC_ALIAS} -> ${MC_URL}"
run_mc alias set "${MC_ALIAS}" "${MC_URL}" "${MC_USER}" "${MC_PASS}" --api S3v4

for BUCKET in "$@"; do
  echo "[minio] 创建 bucket: ${BUCKET}"
  run_mc mb -p "${MC_ALIAS}/${BUCKET}" || true
done

echo "[minio] 完成。"

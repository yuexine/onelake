#!/usr/bin/env bash
set -euo pipefail

command="${1:-install}"
port="${AIRBYTE_PORT:-8000}"
chart_index_url="${AIRBYTE_CHART_INDEX_URL:-https://airbytehq.github.io/charts/index.yaml}"

ensure_abctl() {
  if ! command -v abctl >/dev/null 2>&1; then
    cat >&2 <<'EOF'
abctl is required for local Airbyte.

Install it with one of:
  brew tap airbytehq/tap && brew install abctl
  curl -LsfS https://get.airbyte.com | bash -

Then run:
  make airbyte-up
EOF
    exit 127
  fi
}

case "$command" in
  install|up)
    ensure_abctl
    if [[ "${AIRBYTE_SKIP_CHART_PREFLIGHT:-false}" != "true" ]]; then
      if ! curl -fsSL --head --retry 2 --retry-delay 2 "$chart_index_url" >/dev/null; then
        cat >&2 <<EOF
Airbyte Helm chart index is not reachable:
  $chart_index_url

abctl would create a kind cluster and then fail during chart resolution.
Fix local network/proxy access to airbytehq.github.io, or set
AIRBYTE_SKIP_CHART_PREFLIGHT=true to bypass this check.
EOF
        exit 69
      fi
    fi
    args=(local install --port "$port")
    if [[ "${AIRBYTE_LOW_RESOURCE_MODE:-false}" == "true" ]]; then
      args+=(--low-resource-mode)
    fi
    abctl "${args[@]}"
    ;;
  status)
    ensure_abctl
    abctl local status
    ;;
  credentials)
    ensure_abctl
    abctl local credentials
    ;;
  restart)
    ensure_abctl
    abctl local deployments --restart
    ;;
  uninstall|down)
    ensure_abctl
    abctl local uninstall
    ;;
  *)
    echo "Usage: $0 {install|up|status|credentials|restart|uninstall|down}" >&2
    exit 2
    ;;
esac

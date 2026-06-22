#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_DATABASE="${PG_DATABASE:-onelake}"
PG_USER="${PG_USER:-onelake}"
PG_PASSWORD="${PG_PASSWORD:-onelake}"
MVN="${MVN:-mvn -q}"

run_schema() {
  local schema="$1"
  local schemas="$2"
  local location="filesystem:bootstrap/src/main/resources/db/migration/${schema}"

  echo "==> Flyway migrate ${schema} (${location})"
  ${MVN} -pl bootstrap flyway:migrate \
    -Dflyway.url="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${PG_DATABASE}" \
    -Dflyway.user="${PG_USER}" \
    -Dflyway.password="${PG_PASSWORD}" \
    -Dflyway.schemas="${schemas}" \
    -Dflyway.defaultSchema="${schema}" \
    -Dflyway.locations="${location}" \
    -Dflyway.baselineOnMigrate=true
}

run_schema common common
run_schema integration integration
run_schema orchestration orchestration
run_schema catalog catalog
run_schema modeling modeling
run_schema quality quality
run_schema security security
run_schema dataservice dataservice,dataservice_api

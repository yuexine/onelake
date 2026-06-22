#!/usr/bin/env bash
# Prepare and verify the fixed ODS -> DWD sample baseline.
#
# Usage:
#   scripts/ods-dwd-baseline.sh prepare
#   scripts/ods-dwd-baseline.sh verify
#   scripts/ods-dwd-baseline.sh all
#
# The script is intentionally scoped to the codex_orders sample tables:
# - PostgreSQL source: onelake_src.public.codex_orders
# - Iceberg ODS:       iceberg.ods.ods_codex_orders
# - Iceberg DWD:       iceberg.dwd.dwd_trade_order_df (schema only here)
set -euo pipefail

CMD="${1:-all}"

PG_CONTAINER="${PG_CONTAINER:-onelake-postgres}"
TRINO_CONTAINER="${TRINO_CONTAINER:-onelake-trino}"
PG_USER="${PG_USER:-onelake}"
SOURCE_DB="${SOURCE_DB:-onelake_src}"
SOURCE_SCHEMA="${SOURCE_SCHEMA:-public}"
SOURCE_TABLE="${SOURCE_TABLE:-codex_orders}"
ODS_SCHEMA="${ODS_SCHEMA:-ods}"
ODS_TABLE="${ODS_TABLE:-ods_codex_orders}"
DWD_SCHEMA="${DWD_SCHEMA:-dwd}"
DWD_TABLE="${DWD_TABLE:-dwd_trade_order_df}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_container() {
  local name="$1"
  docker inspect "$name" >/dev/null 2>&1 || die "container not found: $name"
  local running
  running="$(docker inspect -f '{{.State.Running}}' "$name")"
  [[ "$running" == "true" ]] || die "container is not running: $name"
}

pg_exec() {
  local db="$1"
  docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$db"
}

trino_exec() {
  docker exec "$TRINO_CONTAINER" trino --execute "$1"
}

ensure_database() {
  local db="$1"
  if ! docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d postgres -Atc \
    "SELECT 1 FROM pg_database WHERE datname = '${db}'" | grep -q '^1$'; then
    docker exec "$PG_CONTAINER" createdb -U "$PG_USER" "$db"
  fi
}

prepare_source() {
  require_container "$PG_CONTAINER"
  ensure_database "$SOURCE_DB"

  pg_exec "$SOURCE_DB" <<SQL
DROP TABLE IF EXISTS ${SOURCE_SCHEMA}.${SOURCE_TABLE};

CREATE TABLE ${SOURCE_SCHEMA}.${SOURCE_TABLE} (
  order_id   BIGINT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  amount     NUMERIC(18,2),
  status     TEXT,
  order_time TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO ${SOURCE_SCHEMA}.${SOURCE_TABLE}
  (order_id, user_id, amount, status, order_time, updated_at)
VALUES
  (10001, 501, 129.90, 'PAID',   TIMESTAMP '2026-06-20 10:15:00', TIMESTAMP '2026-06-20 10:20:00'),
  (10002, 502,  59.00, 'PAID',   TIMESTAMP '2026-06-20 11:05:00', TIMESTAMP '2026-06-20 11:09:00'),
  (10003, 503, 199.90, 'CANCEL', TIMESTAMP '2026-06-20 12:30:00', TIMESTAMP '2026-06-20 12:32:00'),
  (10004, 504,   0.00, 'PAID',   TIMESTAMP '2026-06-21 09:00:00', TIMESTAMP '2026-06-21 09:03:00'),
  (10005, 505, 399.99, 'PAID',   TIMESTAMP '2026-06-21 09:30:00', TIMESTAMP '2026-06-21 09:35:00'),
  (10006, 506,  88.80, 'REFUND', TIMESTAMP '2026-06-21 10:10:00', TIMESTAMP '2026-06-21 10:18:00'),
  (10007, 507, -12.00, 'PAID',   TIMESTAMP '2026-06-21 10:25:00', TIMESTAMP '2026-06-21 10:29:00'),
  (10008, 508,  72.50, NULL,     TIMESTAMP '2026-06-21 10:40:00', TIMESTAMP '2026-06-21 10:44:00'),
  (10009, 509, 150.00, 'PAID',   NULL,                           TIMESTAMP '2026-06-21 11:00:00'),
  (10010, 510, 260.00, 'PAID',   TIMESTAMP '2026-06-21 11:15:00', TIMESTAMP '2026-06-21 11:20:00');
SQL
}

prepare_lakehouse() {
  require_container "$TRINO_CONTAINER"
  trino_exec "CREATE SCHEMA IF NOT EXISTS iceberg.${ODS_SCHEMA}"
  trino_exec "CREATE SCHEMA IF NOT EXISTS iceberg.${DWD_SCHEMA}"
  trino_exec "DROP TABLE IF EXISTS iceberg.${ODS_SCHEMA}.${ODS_TABLE}"
  trino_exec "CREATE TABLE iceberg.${ODS_SCHEMA}.${ODS_TABLE} (
    order_id BIGINT,
    user_id BIGINT,
    amount DECIMAL(18,2),
    status VARCHAR,
    order_time TIMESTAMP(6),
    updated_at TIMESTAMP(6)
  )"
  trino_exec "INSERT INTO iceberg.${ODS_SCHEMA}.${ODS_TABLE}
    (order_id, user_id, amount, status, order_time, updated_at)
  VALUES
    (10001, 501, DECIMAL '129.90', 'PAID',   TIMESTAMP '2026-06-20 10:15:00', TIMESTAMP '2026-06-20 10:20:00'),
    (10002, 502, DECIMAL '59.00',  'PAID',   TIMESTAMP '2026-06-20 11:05:00', TIMESTAMP '2026-06-20 11:09:00'),
    (10003, 503, DECIMAL '199.90', 'CANCEL', TIMESTAMP '2026-06-20 12:30:00', TIMESTAMP '2026-06-20 12:32:00'),
    (10004, 504, DECIMAL '0.00',   'PAID',   TIMESTAMP '2026-06-21 09:00:00', TIMESTAMP '2026-06-21 09:03:00'),
    (10005, 505, DECIMAL '399.99', 'PAID',   TIMESTAMP '2026-06-21 09:30:00', TIMESTAMP '2026-06-21 09:35:00'),
    (10006, 506, DECIMAL '88.80',  'REFUND', TIMESTAMP '2026-06-21 10:10:00', TIMESTAMP '2026-06-21 10:18:00'),
    (10007, 507, DECIMAL '-12.00', 'PAID',   TIMESTAMP '2026-06-21 10:25:00', TIMESTAMP '2026-06-21 10:29:00'),
    (10008, 508, DECIMAL '72.50',  NULL,     TIMESTAMP '2026-06-21 10:40:00', TIMESTAMP '2026-06-21 10:44:00'),
    (10009, 509, DECIMAL '150.00', 'PAID',   NULL,                           TIMESTAMP '2026-06-21 11:00:00'),
    (10010, 510, DECIMAL '260.00', 'PAID',   TIMESTAMP '2026-06-21 11:15:00', TIMESTAMP '2026-06-21 11:20:00')"
}

verify_source() {
  require_container "$PG_CONTAINER"
  local count dirty
  count="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$SOURCE_DB" -Atc "SELECT count(*) FROM ${SOURCE_SCHEMA}.${SOURCE_TABLE}")"
  dirty="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$SOURCE_DB" -Atc "SELECT count(*) FROM ${SOURCE_SCHEMA}.${SOURCE_TABLE} WHERE amount < 0 OR status IS NULL OR order_time IS NULL")"
  [[ "$count" == "10" ]] || die "expected source row count 10, got $count"
  [[ "$dirty" -ge 2 ]] || die "expected at least 2 dirty source rows, got $dirty"
  echo "source ok: ${SOURCE_DB}.${SOURCE_SCHEMA}.${SOURCE_TABLE} rows=${count} dirty=${dirty}"
}

verify_lakehouse() {
  require_container "$TRINO_CONTAINER"
  local count dirty columns
  count="$(trino_exec "SELECT count(*) FROM iceberg.${ODS_SCHEMA}.${ODS_TABLE}" | tr -d '\"[:space:]')"
  dirty="$(trino_exec "SELECT count(*) FROM iceberg.${ODS_SCHEMA}.${ODS_TABLE} WHERE amount < DECIMAL '0.00' OR status IS NULL OR order_time IS NULL" | tr -d '\"[:space:]')"
  columns="$(trino_exec "SELECT count(*) FROM iceberg.information_schema.columns WHERE table_schema='${ODS_SCHEMA}' AND table_name='${ODS_TABLE}'" | tr -d '\"[:space:]')"
  [[ "$count" == "10" ]] || die "expected ODS row count 10, got $count"
  [[ "$dirty" -ge 2 ]] || die "expected at least 2 dirty ODS rows, got $dirty"
  [[ "$columns" -ge 6 ]] || die "expected at least 6 ODS columns, got $columns"
  echo "lakehouse ok: iceberg.${ODS_SCHEMA}.${ODS_TABLE} rows=${count} dirty=${dirty} columns=${columns}"
}

verify_catalog_hint() {
  require_container "$PG_CONTAINER"
  local asset_count
  asset_count="$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d onelake -Atc \
    "SELECT count(*) FROM catalog.asset WHERE om_fqn='${ODS_SCHEMA}.${ODS_TABLE}' AND columns IS NOT NULL AND jsonb_array_length(columns) >= 6" 2>/dev/null || echo "0")"
  if [[ "$asset_count" == "0" ]]; then
    echo "catalog note: asset ${ODS_SCHEMA}.${ODS_TABLE} not found yet; it will be produced by integration.table.loaded during the integration run."
  else
    echo "catalog ok: asset ${ODS_SCHEMA}.${ODS_TABLE} has columns"
  fi
}

prepare() {
  prepare_source
  prepare_lakehouse
  echo "prepared ODS -> DWD sample baseline"
}

verify() {
  verify_source
  verify_lakehouse
  verify_catalog_hint
}

case "$CMD" in
  prepare)
    prepare
    ;;
  verify)
    verify
    ;;
  all)
    prepare
    verify
    ;;
  *)
    die "unknown command: $CMD (expected prepare|verify|all)"
    ;;
esac

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Layout

This is the OneLake data platform — a **modular monolith control plane** (`onelake-app/`) that orchestrates a set of open-source **data plane** components via Docker Compose. Frontend is a React SPA at `onelake-app/web-console/`. Design specs (Chinese) live in `docs/`:

- `docs/技术初始化文档.md` — engineering scaffold + DB schema + key code samples (the authoritative spec)
- `docs/详细功能清单产品详细设计.md` — feature list with L1–L5 layer numbering
- `docs/数据平台 · 原型设计与交互说明文档.md` — UI wireframes (§8.x) each frontend page maps to
- `docs/IMPLEMENTATION_STATUS.md`, `docs/FRONTEND_VERIFICATION.md` — what's done and where

When a change touches an L1-L5 feature or a §6.x/§7.x code pattern, the design doc reference is usually already in the relevant file's header comment.

## Common Commands

All commands run from `onelake-app/` unless noted.

### Backend (Java 17 + Spring Boot 3.3.2, Maven multi-module)

```bash
mvn install -DskipTests           # build all 10 modules (parent + 8 business + bootstrap)
mvn -pl bootstrap spring-boot:run # run control plane (port 8080)
mvn -pl bootstrap flyway:migrate  # apply Flyway migrations (needs PG up)
mvn -pl <module> compile          # compile a single module, e.g. module-integration
mvn -pl <module> test             # run one module's tests
mvn -pl <module> -Dtest=ClassName test                 # single test class
mvn -pl <module> -Dtest=ClassName#method test          # single test method
mvn clean                         # wipe target/
```

### Frontend (`web-console/`, pnpm + Vite + React 18 + TS)

```bash
cd web-console
pnpm install
pnpm dev              # vite dev server, port 5173, proxies /api → :9080 and /auth → :8081
pnpm build            # tsc --noEmit then vite build
pnpm gen:api          # regenerate src/api from backend /v3/api-docs (backend must be running)
```

### Data plane + seed (Docker Compose v2 required)

```bash
make up        # postgres/redis/minio/hive-metastore/trino/keycloak/openmetadata/postgrest/apisix/dagster/airbyte/superset
make seed      # Keycloak realm "onelake" + roles + MinIO bucket
make migrate   # Flyway across 9 schemas
make backend   # spring-boot:run
make debug     # JDWP on :5005 for IDE remote attach
make dev       # up + migrate + backend in sequence
```

Default ports: PG `5432`, Redis `6379`, MinIO `9000/9001`, Trino `8080`, Keycloak `8081`, OpenMetadata `8585`, PostgREST `3001`, APISIX `9080/9180`, Dagster `3000`, Airbyte `8000`, Superset `8088`. All credentials are dev defaults in `docker-compose.yml`.

## Architecture

### Control plane = modular monolith + 9 schemas in one PostgreSQL

Eight business modules (`module-{common,integration,orchestration,catalog,modeling,quality,security,dataservice}`) plus `bootstrap` (the Spring Boot main + aggregation config + Flyway scripts). Each module owns exactly **one DB schema** of the same name; **cross-schema direct reads/writes are forbidden** — modules talk to each other only via:

1. The `Outbox` pattern in `module-common.outbox` — write `outbox_event` row in the same business transaction; `OutboxDispatcher` polls every 2s and routes to `DomainEventHandler` implementations.
2. Calls to `module-common` SPI utilities (audit, tenant context).

This is the **single most important architectural rule**. Never import another module's `domain/`, `repository/`, or `entity/`. If you need cross-module data, publish an Outbox event or call a service.

### Per-module layered package structure (strict)

```
com.onelake.<module>/
├── api/         # @RestController + VO (vo/), only boundary concerns, no business logic
├── service/     # interface + impl/, @Transactional, orchestrates domain/client/outbox
├── domain/      # entity/ + enums/ + event/ — no Spring/JPA annotations leak beyond repository
├── repository/  # Spring Data JPA Repository interfaces, called only by service
├── client/      # WebClient wrappers for data plane components (Airbyte/Dagster/OM/APISIX)
├── mapper/      # MapStruct Entity ↔ DTO/VO
├── dto/         # cross-layer transfer objects (never expose entities to api layer)
└── config/      # module-local @Configuration (WebClient, SPI registration)
```

Dependency direction: `api → service → domain`, `repository` and `client` are called only by `service`. `module-integration` is the canonical example — clone its layering for new modules.

### Single source of truth: bootstrap module

`bootstrap/` is the **only** Spring Boot application. It scans `com.onelake.*` and aggregates all modules. The root POM and `bootstrap/pom.xml` lock dependency versions — never override in module POMs.

### Tenant + audit + outbox = same transaction

- `TenantContext` (ThreadLocal in `module-common.context`) is populated by `TenantContextFilterConfig` from JWT claims (`realm_access.roles` → `ROLE_*`, plus `tenant_id` / `sub` / `preferred_username`). The filter also assigns `X-Trace-Id` (or generates one) and propagates to MDC.
- Every business table carries `tenant_id`. Service entrypoints read `TenantContext.getTenantId()` — missing tenant context throws `BizException(40100, ...)`.
- `AuditLogger` writes `common.audit_log` in `REQUIRES_NEW` so audit survives business rollback.
- `OutboxPublisher.publish(...)` must be called **inside the same `@Transactional`** as the business write. This is what guarantees "write row = emit event".

### Security: OAuth2 resource server + Keycloak roles

`module-common.security.SecurityConfig` is the single `SecurityFilterChain`. It accepts Keycloak JWTs and converts `realm_access.roles` into Spring `ROLE_<role>`. The five platform roles are `DE / ADMIN / CONSUMER / SEC / OPS` (created by `scripts/keycloak-realm.sh`). Use `@PreAuthorize("hasRole('DE')")` on controllers — never implement your own auth check.

Endpoints `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**` are permitAll; everything else requires authentication.

### Data plane = "drive, don't reimplement"

The control plane never reimplements what Airbyte/Dagster/Trino/OpenMetadata/APISIX already do. Modules hold **handle references** (e.g., `airbyte_connection_id`, `dagster_run_id`, `om_fqn`) and call the data plane via WebClient:

- `module-integration.client.AirbyteSyncDriver` → triggers/polls Airbyte jobs
- `module-orchestration.client.DagsterClient` → GraphQL `launchRun`
- `module-catalog.client.OpenMetadataClient` → syncs assets into `catalog.asset` (OM remains authoritative)
- `module-dataservice.service.DataServicePublisher` → creates PostgREST view in schema `dataservice_api` AND registers APISIX route (`key-auth` + `limit-req`)

Endpoints for these are under `onelake.dataplane.*` in `application.yml`. They are environment-overridable and tolerate dev defaults.

### Flyway: 9 schemas, one migration dir per schema

```
bootstrap/src/main/resources/db/migration/<schema>/V<n>__<schema>.sql
```

Schemas: `common, integration, orchestration, catalog, modeling, quality, security, dataservice, dataservice_api` (the last is for PostgREST-exposed views, granted to role `web_anon` in `scripts/postgres-init.sql`).

**Never edit a historical migration** — write a new `V2__...sql`. `Flyway` is configured with `baseline-on-migrate: true` and `ddl-auto: none` (Hibernate never creates tables). Schema review notes from §7.9 of the spec are already implemented as extra tables in the same `V1__*.sql` files (e.g., `security.role`, `security.role_binding`, `security.approval_request`, `common.dict_type`, `common.dict_item`, `common.tag`, `integration.source_schema_snapshot`, `modeling.dimension`, `quality.score_snapshot`, `quality.alert`, `dataservice.quota_usage`).

### API URL convention

`/api/v1/<module>/<resource>` — e.g., `/api/v1/integration/datasources`, `/api/v1/catalog/assets/{id}`. The frontend `web-console/src/api/http.ts` axios client prepends `/api/v1`. Endpoints return `ApiResponse<T>` (`{code, message, data}`); code `0` = success, `40000` = validation, `40100` = no tenant context, `40400` = not found, `40901` = duplicate, `50010` = data plane error (`DataplaneException`), `50000` = unknown.

### Frontend layout-route pattern

`web-console/src/App.tsx` is a React Router **layout route** (renders Sider + Header + `<Outlet />` + global task bar). All page routes are nested under `<Route element={<App />}>` in `routes.tsx` — do not add `<App />` as a sibling of `<AppRoutes />` or the right-side content area will go blank. Ten menu sections in SideNav correspond to the 10 top-level route prefixes.

Mock data lives in `web-console/src/mock/` and is the current source for the UI; the real axios calls in `src/api/index.ts` will swap in once the backend is wired. The mock layer is organized by L-layer (`l1-integration.ts`, `l2-lakehouse.ts`, etc.) to mirror the spec.

### Design tokens — classification colors are load-bearing

`web-console/src/components/tokens.ts` defines `classifications[L1..L4]` (L1 gray, L2 blue, L3 orange, L4 red). These are used in **9 places** per the spec (collection field markers, catalog asset badges, lineage node borders, masking policies, API return fields, PII scan, RBAC column permissions, etc.). Always use `<ClassificationBadge level={...} />`, never inline colors for sensitivity. Same for `StatusBadge` over ad-hoc `<Tag>` calls.

## Module Quick Reference

| Module | Owns | Drives |
|--------|------|-------|
| `module-common` | tenant/audit/outbox/exception/security/config | (foundation) |
| `module-integration` | datasource, sync_task, sync_run, source_schema_snapshot | Airbyte (sync jobs) |
| `module-orchestration` | dag, job_run | Dagster (GraphQL) |
| `module-catalog` | asset, lineage_edge | OpenMetadata (asset sync) |
| `module-modeling` | subject_domain, data_standard, metric, dimension | dbt (generates models) |
| `module-quality` | rule, run_result, score_snapshot, alert | dbt tests / Dagster checks |
| `module-security` | secret, masking_policy, access_grant, role, role_binding, approval_request | Keycloak (RBAC) |
| `module-dataservice` | api_definition, api_version, app_key, subscription, api_call_log, quota_usage | PostgREST + APISIX |

`module-common` is depended on by all others; no module depends on another module directly (only via Outbox events or shared SPI).

## dbt project (`onelake-app/dbt/`)

Three layers map to lakehouse layers: `staging/` (ODS views), `intermediate/` (DWD), `marts/` (DWS/ADS, materialized as Iceberg tables). Profile `onelake` targets Trino on Iceberg; macros in `macros/onelake_macros.sql` provide `incremental_filter` (watermark + lookback) and masking helpers. The seed example `marts/ads_order_gmv_daily.sql` is the spec's reference model — keep its `schema.yml` quality tests in sync.

## When extending the codebase

- New business table → add to the relevant `V<n>__<schema>.sql`, add JPA `@Entity` in `domain/entity/`, add Repository, expose via Service + Controller. Always include `tenant_id`.
- New cross-module event → publish via `OutboxPublisher.publish("module.event-name", aggregateId, payload)` and implement `DomainEventHandler` in the consuming module.
- New data plane integration → add a `client/` class using the shared `WebClient.Builder` bean, never `new WebClient()`.
- New API endpoint → path under `/api/v1/<module>/...`, return `ApiResponse<T>`, add `@PreAuthorize` for the required role, call `AuditLogger` for sensitive ops.
- New frontend page → register route under `<Route element={<App />}>` in `routes.tsx`, add menu entry to `NAV` in `App.tsx`, mock data in `src/mock/`, axios client method in `src/api/index.ts`.

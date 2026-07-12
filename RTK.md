# OneLake Runtime Toolkit

> This file is the project-level development entrypoint for AI/vibecoding work in
> OneLake. Its job is to get a developer or Agent to the right context quickly,
> preserve the current architecture boundaries, and push each change to a
> verifiable state.
>
> Updated: 2026-07-12. If docs and code disagree, trust the current code first,
> then update the affected docs.

## 1. Working Principles

1. **Code facts first**: before changing a module, read the target controller,
   service, entity/repository, frontend page/API, tests, and the matching docs.
2. **Small verified slices**: each coding turn should cover context reading,
   scoped implementation, focused verification, and a clear final status.
3. **Protect existing work**: this repo may contain user changes. Never revert,
   reformat, or clean unrelated files.
4. **Use the local stack intentionally**: distinguish frontend shell, backend app,
   Docker data plane, and full-stack readiness in status reports.
5. **Document drift immediately**: when behavior, endpoints, routes, or module
   status changes, update the relevant docs under `docs/`.
6. **No hidden mock promotion**: frontend mock/prototype data is acceptable for
   prototype surfaces, but backend production paths should not silently rely on
   stub/in-memory behavior.

## 2. Current Baseline

| Area | Current Baseline |
| --- | --- |
| Backend | Java 17, Spring Boot 3.3.2, Maven multi-module modular monolith under `onelake-app/`. |
| Modules | `module-common`, `module-integration`, `module-orchestration`, `module-catalog`, `module-modeling`, `module-quality`, `module-security`, `module-dataservice`, `module-analytics`, and `bootstrap`. |
| Data plane | `onelake-app/docker-compose.yml` defines Postgres, Redis, MinIO, Hive Metastore, Trino, Keycloak, OpenMetadata, PostgREST, APISIX, Dagster, Superset, JupyterHub, Spark, Flink, Kafka/Zookeeper, and etcd. Airbyte local deployment is managed by `abctl` through `onelake-app/scripts/airbyte-local.sh`. |
| Database | Flyway migrations live under `onelake-app/bootstrap/src/main/resources/db/migration/*` and target multiple schemas. The orchestration migration head is V32; M4 started at V31. |
| Frontend | React 18, Vite 5, TypeScript, Ant Design 5, Pro Components, React Router, React Query, Zustand, X6, Monaco, ECharts under `onelake-app/web-console`. |
| Product Scope | Modular control-plane implementation with broad frontend coverage. Orchestration V2 M1–M3 are complete; M4 status is tracked in `docs/数据开发与编排模块V2升级计划.md` and the detailed plan. |
| Runtime Logs | Use root `.run-logs/` for long-running local process logs. Existing frontend log path: `.run-logs/web-console-vite.log`. |

## 3. Must-Read Context

| Scenario | Read First |
| --- | --- |
| Project map | This file's directory map plus the active module-specific plans under `docs/`. |
| Technical initialization | `docs/技术初始化文档.md` |
| Full local deployment | `docs/本地开发环境完整部署指南.md` |
| Product and function scope | `docs/详细功能清单产品详细设计.md` |
| Frontend prototype and interaction | `docs/数据平台 · 原型设计与交互说明文档.md` plus the matching module plan. |
| Backend boot/config | `onelake-app/pom.xml`, `onelake-app/bootstrap/src/main/resources/application.yml`, `onelake-app/Makefile` |
| Data plane | `onelake-app/docker-compose.yml`, `onelake-app/scripts/`, `onelake-app/trino/`, `onelake-app/apisix/`, `docs/本地开发环境完整部署指南.md` |
| Frontend app | `onelake-app/web-console/package.json`, `onelake-app/web-console/vite.config.ts`, `onelake-app/web-console/src/App.tsx`, `onelake-app/web-console/src/routes.tsx` |

Read module-specific docs/code on demand. Do not load every large product
document unless the task genuinely needs broad product context.

## 4. Directory Map

```text
docs/
  本地开发环境完整部署指南.md
  技术初始化文档.md
  数据平台 · 原型设计与交互说明文档.md
  详细功能清单产品详细设计.md

onelake-app/
  Makefile                              # Local lifecycle commands
  docker-compose.yml                    # Data plane services
  pom.xml                               # Parent Maven project
  bootstrap/                            # Spring Boot application and migrations
  module-common/                        # Common API, exception, audit, security, outbox
  module-integration/                   # Datasources and sync tasks
  module-orchestration/                 # DAG orchestration
  module-catalog/                       # Metadata, assets, lineage
  module-modeling/                      # Domains, standards, metrics, dimensions
  module-quality/                       # Rules, results, scores, alerts
  module-security/                      # Secrets, masking, grants, RBAC, approvals
  module-dataservice/                   # API publishing, app keys, subscriptions, quotas
  module-analytics/                     # Dataset, Superset embed, screen designer, notebooks
  dbt/                                  # Trino/dbt models
  scripts/                              # Postgres, Keycloak, MinIO init scripts
  trino/                                # Trino/Hive/Iceberg config
  apisix/                               # APISIX config
  web-console/                          # React control console
```

## 5. Local Runtime Commands

Run Makefile commands from `onelake-app/`.

```bash
cd onelake-app
make help
make up          # Start Docker data plane
make up-core     # Start minimum local control-plane dependencies
make dagster-up  # Build and start Dagster webserver/daemon/code-location
make airbyte-up  # Start local Airbyte with abctl
make airbyte-status
make ps          # Inspect data plane containers
make logs        # Follow data plane logs
make seed        # Initialize Keycloak realm and MinIO bucket
make migrate     # Run Flyway migrations
make ods-dwd-baseline  # Prepare fixed ODS->DWD sample source/ODS data
make ods-dwd-verify    # Verify fixed ODS->DWD sample baseline
make mall-mysql-ods-load # Load local mall MySQL test tables into Iceberg ODS
make backend     # Run Spring Boot backend in foreground
make debug       # Run backend with JDWP on 5005
make frontend    # pnpm install + gen API SDK + Vite dev server
make down        # Stop Docker data plane
make clean       # Maven clean
make fmt         # Maven spotless if configured
```

Recommended log pattern for long-running local processes:

```bash
mkdir -p .run-logs
(cd onelake-app && make backend) 2>&1 | tee .run-logs/backend.log
(cd onelake-app/web-console && pnpm dev) 2>&1 | tee .run-logs/web-console-vite.log
```

Useful URLs when services are actually running:

| Service | URL |
| --- | --- |
| Frontend | `http://localhost:5173/` |
| Backend health | `http://localhost:8080/actuator/health` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| MinIO Console | `http://localhost:9001/` |
| Keycloak | `http://localhost:8081/` |
| Trino | `http://localhost:18080/` |
| Spark Master / Worker | `http://localhost:18081/`, `http://localhost:18082/` |
| Flink | `http://localhost:8082/` |
| OpenMetadata | `http://localhost:8585/` |
| APISIX Admin | `http://localhost:9180/` |
| PostgREST | `http://localhost:3001/` |
| Dagster | `http://localhost:3000/` |
| JupyterHub | `http://localhost:18000/` |
| Airbyte UI | `http://localhost:8000/` after `make airbyte-up` |
| Airbyte API fallback | `http://localhost:18001/api/v1/health` via `kubectl port-forward` |
| Superset | `http://localhost:8088/` |

## 6. Known Runtime Notes

- The current full local deployment runbook is
  `docs/本地开发环境完整部署指南.md`. Use it for end-to-end environment
  lifecycle requests.
- `docker-compose.yml` maps Trino to host port `18080`, leaving Spring Boot on
  `8080`. JupyterHub maps to host `18000` because Airbyte reserves `8000`.
- `web-console/package.json` has `gen:api` pointing at
  `http://localhost:8080/v3/api-docs`; this requires a reachable backend OpenAPI
  endpoint before running `make frontend` successfully. It currently writes to
  `web-console/src/api`; because `src/api/index.ts` is also the hand-written
  API facade, review generated output before accepting any overwrite.
- Vite proxies `/api` to `VITE_API_PROXY_TARGET` and defaults to
  `http://localhost:8080`; set `VITE_API_PROXY_TARGET=http://localhost:9080`
  to verify the APISIX path. Run `make apisix-routes` after APISIX starts so
  `/api/*` forwards to the local backend.
- Data-plane startup can be heavy. Report container health and reachable URLs
  separately from local app readiness.
- Airbyte is no longer started by Docker Compose. Use `make airbyte-up`, which
  delegates to `abctl local install --port 8000`; use `make airbyte-status` and
  `make airbyte-credentials` for readiness and login credentials. Airbyte runs
  inside a separate kind cluster and can return `503` while pods are
  `CrashLoopBackOff`; check it separately from Compose readiness. The backend
  default Airbyte API endpoint is `http://localhost:18001/api/v1`, which expects
  a local `kubectl port-forward` to the Airbyte server service when the abctl
  ingress on `8000` is unstable.
- Flyway runtime scope has 10 schemas configured:
  `common,integration,orchestration,catalog,modeling,quality,security,dataservice,dataservice_api,analytics`.
  Migration directories exist for the 9 schemas that own tables; `dataservice_api`
  is the PostgREST/API-view schema.
- ODS -> DWD execution is currently Spark pipeline based. `module-modeling`
  owns DWD model definition, validation, compilation, and publication; runtime,
  scheduling, backfill, and observation live in `module-orchestration` through
  Spark-only `pipeline_task`, `job_run`, and `task_run` paths. Treat old
  dbt-on-Trino run documents as historical ADR/background unless updated.

## 7. Verification Strategy

Choose the smallest meaningful check for the change:

| Change Area | Recommended Verification |
| --- | --- |
| Backend compile | `cd onelake-app && mvn -q install -DskipTests` |
| Backend module | `cd onelake-app && mvn -q -pl <module> -am test` |
| Flyway/migrations | `cd onelake-app && make up && make migrate` |
| Data-plane config | `cd onelake-app && make up && make ps` |
| Frontend build | `cd onelake-app/web-console && pnpm build` |
| Frontend lint | `cd onelake-app/web-console && pnpm lint` |
| Frontend visual fix | Run Vite, open `http://localhost:5173/`, verify in browser with screenshot/computed styles when layout matters. |

If a command cannot be run because dependencies, ports, credentials, or long
runtime setup are unavailable, say that explicitly and include the next best
verification that did run.

## 8. Backend Development Rules

- Keep business code inside the matching `module-*` package. Shared API,
  exceptions, audit, tenant context, security, outbox, and WebClient helpers
  belong in `module-common`.
- Preserve the controller -> service -> domain/repository/client layering used
  by existing modules.
- Use `ApiResponse`, `BizException`, `DataplaneException`, global exception
  handling, audit logging, and outbox patterns already present in the codebase.
- Do not add a new persistence or API framework without a clear architectural
  reason.
- New or changed REST endpoints should be reflected in frontend API contracts
  and docs when applicable.

## 9. Frontend Development Rules

- `web-console` is an enterprise data-platform console, not a marketing site.
  Build dense, scannable, workflow-oriented screens.
- Reuse existing design tokens and shared components:
  `src/components/tokens.ts`, `ClassificationBadge`, `StatusBadge`,
  `StateView`, `DetailPageLayout`, and components under `src/components/primitives/`.
- For visible UI changes, cover loading, empty, error, disabled, and permission
  states when the workflow implies them.
- Avoid introducing a second styling language. Prefer the existing Ant Design
  and local CSS patterns.
- After substantial frontend changes, run a build and verify the relevant route
  in the browser.

## 10. Documentation Sync

- Update the matching active module plan when capability status, routes, or verification coverage changes.
- Keep this `RTK.md` focused on runtime and development guidance. Avoid copying
  long implementation inventories that already live in docs.

## 11. Agent Output Requirements

- During work, give short progress updates about context read, files changed,
  and verification being run.
- Final responses should include the concrete files changed, verification result,
  and any remaining blockers or risks.
- For environment lifecycle requests, report actual process/log ownership,
  reachable URLs, and blockers separately.

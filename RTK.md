# OneLake Runtime Toolkit

> This file is the project-level development entrypoint for AI/vibecoding work in
> OneLake. Its job is to get a developer or Agent to the right context quickly,
> preserve the current architecture boundaries, and push each change to a
> verifiable state.
>
> Updated: 2026-06-15. If docs and code disagree, trust the current code first,
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
| Modules | `module-common`, `module-integration`, `module-orchestration`, `module-catalog`, `module-modeling`, `module-quality`, `module-security`, `module-dataservice`, and `bootstrap`. |
| Data plane | `onelake-app/docker-compose.yml` defines Postgres, Redis, MinIO, Hive Metastore, Trino, Keycloak, OpenMetadata, PostgREST, APISIX, Dagster, Airbyte, and Superset. |
| Database | Flyway migrations live under `onelake-app/bootstrap/src/main/resources/db/migration/*` and target multiple schemas. |
| Frontend | React 18, Vite 5, TypeScript, Ant Design 5, Pro Components, React Router, React Query, Zustand, X6, Monaco, ECharts under `onelake-app/web-console`. |
| Product Scope | MVP control-plane skeleton plus full frontend prototype coverage for the OneLake data platform. |
| Runtime Logs | Use root `.run-logs/` for long-running local process logs. Existing frontend log path: `.run-logs/web-console-vite.log`. |

## 3. Must-Read Context

| Scenario | Read First |
| --- | --- |
| Project map | `docs/PROJECT_STRUCTURE.md`, `docs/IMPLEMENTATION_STATUS.md` |
| Technical initialization | `docs/技术初始化文档.md` |
| Product and function scope | `docs/详细功能清单产品详细设计.md` |
| Frontend prototype and interaction | `docs/数据平台 · 原型设计与交互说明文档.md`, `docs/FRONTEND_VERIFICATION.md` |
| Backend boot/config | `onelake-app/pom.xml`, `onelake-app/bootstrap/src/main/resources/application.yml`, `onelake-app/Makefile` |
| Data plane | `onelake-app/docker-compose.yml`, `onelake-app/scripts/`, `onelake-app/trino/`, `onelake-app/apisix/` |
| Frontend app | `onelake-app/web-console/package.json`, `onelake-app/web-console/vite.config.ts`, `onelake-app/web-console/src/App.tsx`, `onelake-app/web-console/src/routes.tsx` |

Read module-specific docs/code on demand. Do not load every large product
document unless the task genuinely needs broad product context.

## 4. Directory Map

```text
docs/
  PROJECT_STRUCTURE.md                  # Codebase map and module index
  IMPLEMENTATION_STATUS.md              # Current implementation status
  FRONTEND_VERIFICATION.md              # Frontend prototype verification
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
make ps          # Inspect data plane containers
make logs        # Follow data plane logs
make seed        # Initialize Keycloak realm and MinIO bucket
make migrate     # Run Flyway migrations
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
| OpenMetadata | `http://localhost:8585/` |
| APISIX Admin | `http://localhost:9180/` |
| PostgREST | `http://localhost:3001/` |
| Dagster | `http://localhost:3000/` |
| Superset | `http://localhost:8088/` |

## 6. Known Runtime Notes

- `docker-compose.yml` maps Trino to host port `8080`, while
  `bootstrap/src/main/resources/application.yml` configures the Spring Boot
  backend on port `8080`. Treat this as a current full-stack startup conflict.
  Before reporting "full stack ready", either move Trino's host port and align
  dbt `TRINO_PORT`, or make the backend port configurable and update frontend/API
  generation commands accordingly.
- `web-console/package.json` has `gen:api` pointing at
  `http://localhost:8080/v3/api-docs`; this requires a reachable backend OpenAPI
  endpoint before running `make frontend` successfully.
- Vite proxies `/api` to APISIX `http://localhost:9080` and `/auth` to Keycloak
  `http://localhost:8081`. Validate whether a frontend issue is proxy,
  gateway, backend, or mock/prototype data before changing UI code.
- Data-plane startup can be heavy. Report container health and reachable URLs
  separately from local app readiness.

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

- Update `docs/PROJECT_STRUCTURE.md` when moving or adding structural modules.
- Update `docs/IMPLEMENTATION_STATUS.md` when capability status changes.
- Update `docs/FRONTEND_VERIFICATION.md` after material frontend prototype
  coverage or navigation changes.
- Keep this `RTK.md` focused on runtime and development guidance. Avoid copying
  long implementation inventories that already live in docs.

## 11. Agent Output Requirements

- During work, give short progress updates about context read, files changed,
  and verification being run.
- Final responses should include the concrete files changed, verification result,
  and any remaining blockers or risks.
- For environment lifecycle requests, report actual process/log ownership,
  reachable URLs, and blockers separately.

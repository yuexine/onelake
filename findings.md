# 发现与决策：数据集成模块后端迭代调研

## 需求
- 用户希望以“后端技术专家”视角，阅读数据集成模块、数据面开发指南文档和前端页面代码。
- 输出详细的后端迭代开发计划，并研究实施路线可行性。
- 当前任务以调研和计划为主，暂不修改业务代码。

## 研究发现
- 项目入口确认后端是 Java 17 + Spring Boot 3.3.2 的 Maven 多模块单体，`module-integration` 是数据源与同步任务模块。
- 数据面基线包含 Postgres、Redis、MinIO、Hive Metastore、Trino、Keycloak、OpenMetadata、PostgREST、APISIX、Dagster、Airbyte、Superset。
- 与本次任务直接相关的文档包括：
  - `docs/数据集成模块技术方案（依赖 · 架构 · 数据流 · API · 关键代码）.md`
  - `docs/数据面开发指南（部署 · Airbyte · dbt · Dagster · Trino）.md`
  - `docs/技术初始化文档.md`
  - `docs/数据平台 · 原型设计与交互说明文档.md`
  - `docs/FRONTEND_VERIFICATION.md`
- 前端数据集成页面位于 `onelake-app/web-console/src/pages/integration/`，路由覆盖连接管理、采集任务、采集向导、任务详情、CDC、文件采集、模板、失败诊断、Schema 变更审批、采集监控。
- 前端已有 `IntegrationAPI` 封装 `/integration/datasources`、`/integration/sync-tasks/...` 的部分真实接口，但页面代码仍大量直接消费 `src/mock/l1-integration.ts`。
- 后端 Controller 当前暴露路径为 `/api/v1/integration/datasources` 与 `/api/v1/integration/sync-tasks`，而前端 `IntegrationAPI` 使用 `/integration/...`；需要通过网关/代理统一路径或调整前端 SDK。
- `DataSourceService` 已支持创建、更新、删除、详情、列表、测连，创建时写 outbox 和 audit；列表按 `TenantContext` 过滤。
- `ConnectivityTester` 已实现 TCP 探活和关系库 JDBC `SELECT 1`，但仍从 `config.password` 读取密码，和实体注释“密码不明文落库，secret_ref 指向 security.secret”存在实现差距。
- `SyncTaskService` 已支持创建、详情、按源列表、触发运行、reconcile、运行分页；任务创建固定 `DRAFT`，当前没有启用/停用/更新/删除/全量列表接口。
- `AirbyteSyncDriver` 已真实调用 Airbyte `/connections/sync` 与 `/jobs/get`，但没有创建 source/destination/connection 的控制面编排，也没有日志代理、取消作业、状态映射容错和鉴权配置。
- 数据库迁移已建 `integration.datasource`、`integration.sync_task`、`integration.sync_run`、`integration.source_schema_snapshot`，但缺任务按租户+名称唯一约束、运行表租户字段、schema drift 审批/事件表、连接器模板表等产品化支撑。
- `SourceSchemaSnapshot` 与 Repository 已存在，但当前没有 Service/Controller/定时采集/差异计算流程使用它。
- 数据集成技术方案目标定位：统一纳管外部数据源接入、连通性校验、同步任务编排、运行监控与 schema 漂移检测；控制面只编排治理，不亲自搬数据，搬运委托 Airbyte。
- 技术方案要求引入 `SyncDriver` SPI/Registry，Airbyte 只是首个驱动，未来可替换 Flink CDC/自研采集器；当前代码直接依赖 `AirbyteSyncDriver`，尚未抽象。
- 技术方案 API 清单比代码现状多：数据源分页/关键字查询、源端 schemas、snapshots、drift；同步任务列表/更新/删除/enable/disable/trigger；sync-runs 列表/详情/logs/cancel/reconcile；connectors 表单模式。
- 技术方案业务闭环是：注册数据源 → 连通性校验+schema 发现 → 创建任务并生成 Airbyte Connection → 启用并注册 Dagster 调度 → 触发运行入湖 → reconcile 回写 → 事件通知 catalog/quality。
- 数据面开发指南明确数据面不在 Java 控制面写逻辑，由开源组件部署配置 + dbt SQL + Dagster Python 组成；控制面通过 API/事件驱动。
- 数据面指南推荐 Airbyte 连接由控制面 API 动态创建：`ensureConnection()` 调 `/sources/create`、`/destinations/create`、`/connections/create`；Airbyte 自身调度应为 manual，由 Dagster/控制面触发。
- 数据面与控制面关键衔接点：控制面触发同步并给 Dagster run 打 `sync_run_id` tag；Dagster `run_status_sensor` 回写 `/sync-runs/{id}/reconcile`；reconcile 成功后控制面发 `integration.table.loaded` 给 catalog/quality。
- 当前实际 `docker-compose.yml` 使用 Hive Metastore Iceberg catalog，文档示例里还出现过 Iceberg REST Catalog；后端计划需以当前 compose/trino 配置为准。
- 前端 `http` baseURL 是 `/api/v1`，所以 `IntegrationAPI('/integration/...')` 实际请求 `/api/v1/integration/...`，与后端 Controller 路径一致；路径本身可用，但页面暂未接入该 API。
- 前端 `DataSource` 类型包含 `tenantId`、`host`、`port`、`dbName`、`username`、`rttMs` 等展示字段；后端 `DataSourceDTO` 当前不返回这些 config 派生展示字段。
- 前端 `SyncTask` 类型包含 `sourceName`、`fieldMapping`、`dirtyThreshold`、`airbyteConnectionId`；后端 `SyncTaskDTO` 当前不返回 `sourceName`、`fieldMapping`、`dirtyThreshold`、`airbyteConnectionId`。
- 前端 `SyncRun` 类型包含 `errorMsg`、`checkpoint`、`shardProgress`、`durationMs`、`throughputRows`；后端 `SyncRunDTO` 当前缺 `errorMsg`、`checkpoint` 和派生指标。
- 前端页面的关键操作包括测连、创建连接、删除连接、创建任务、试跑、发布、触发、暂停、重试、checkpoint 恢复、schema 变更审批、文件采集配置、模板生成；当前后端只覆盖其中一部分。
- 公共模块已有 `TenantContext`、`AuditLogger`、`OutboxPublisher/Dispatcher/DomainEventHandler`，后端迭代应复用这些能力，而不是另起事件或审计机制。
- 2026-06-15 23:44 重新校准采集任务创建链路：`SyncTaskController` 已提供 create/list/get/update/delete/enable/disable/trigger/runs/reconcile 接口；`SyncTaskServiceImpl#create` 只保存 `DRAFT` 任务并发布 `integration.sync_task.created`，`enable` 只改状态，不会调用 Airbyte 创建 connection；`trigger` 要求任务已启用且已有 `airbyteConnectionId`。
- `AirbyteSyncDriver` 当前已有 `ensureSource`、`ensureDestination`、`ensureConnection`、`triggerSync`、`getJobSnapshot`、`getJobLogs`、`cancel`；`SyncTaskServiceImpl` 已在发布、触发、reconcile、日志和取消链路使用这些能力。
- 前端 `SyncTaskWizard` 已把“发布”串为 `createSyncTask -> enableSyncTask -> navigate('/integration/sync-tasks')`，但选表、字段映射、cron、限流仍主要是样例/默认值；“保存草稿”和“试跑”只是消息提示，尚未接真实后端。
- `SourceSchemaSnapshotServiceImpl` 已有快照和漂移检测 API，但 `captureColumns` 明确是离线 stub；`DatabaseDiscoveryClient` 只支持 MySQL/Postgres 库列表探查，尚未提供表/字段 discovery。
- 任务运行闭环已有 `sync_run` 落库、reconcile、成功/失败事件发布和下游消费者雏形；但 Airbyte 状态映射只做 `RunStatus.valueOf(s.toUpperCase())`，无法处理 `pending/cancelled/unknown`，也没有 rows/error/checkpoint/logs 采集。
- 当前闭环状态可概括为：前端创建可达、后端任务可落库、列表详情可读、事件契约已有；但“创建任务自动生成 Airbyte connection -> 触发真实入湖 -> 回写指标/日志 -> 前端诊断/血缘展示”还未闭合。
- 下一轮实现已补齐 MySQL/Postgres 的 schema/table/column discovery：`GET /api/v1/integration/datasources/{id}/schemas`、`/tables?schema=`、`/tables/{objectName}/columns`。
- `SourceSchemaSnapshotServiceImpl#captureColumns` 已改为调用 `DatabaseDiscoveryClient.describeTable`，不再写占位 columns；CDC/file schema 仍未接入。
- `SyncTaskServiceImpl#enable` 已在缺少 `airbyteConnectionId` 时调用 `AirbyteSyncDriver.ensureConnection`，读取数据源 config 中的 `airbyteSourceId`/`externalSourceId` 和 `airbyteDestinationId`/`externalDestinationId`，或配置项 `onelake.dataplane.airbyte.destination-id`。
- `SyncTaskWizard` 已从真实接口加载 schema/table/columns 并自动生成字段映射；“保存草稿”已调用真实 `createSyncTask`，“发布”仍是 `createSyncTask -> enableSyncTask`。
- 阶段 15 已补齐 Airbyte source/destination 动态创建、dry-run、日志、取消和运行指标回写的第一批能力；仍未完成的是 Dagster 调度注册、Airbyte connector definition 自动发现/表单 schema、真实端到端入湖环境联调。

## 2026-06-16 数据面执行闭环实施发现
- 发布任务时，如果数据源 config 已有 `airbyteSourceId`/`airbyteDestinationId`，系统复用现有 Airbyte 资源；如果缺失但配置了 `airbyteWorkspaceId`、`sourceDefinitionId`、`destinationDefinitionId` 和对应 connection config，则会动态创建 source/destination 并回写到 datasource config。
- dry-run 当前是控制面前置检查：验证数据源、目标表、字段映射、Airbyte workspace/source/destination 准备条件；已有 connection id 时会调用 Airbyte 检查 connection 可达性。
- `trigger` 不再只在 Airbyte 返回 job id 后才落库；现在会先创建 `QUEUED` run，触发失败也能留下 `FAILED` 运行实例用于诊断。
- `reconcile` 从 Airbyte job snapshot 显式映射 `pending/queued/running/succeeded/failed/incomplete/cancelled`，并写入 rows、checkpoint、error。
- 前端“试跑”按钮已从静态提示改为真实 dry-run；任务详情运行历史支持真实日志弹窗和运行取消。
- `DatabaseDiscoveryClient` 已从集中 `switch` 改为策略模式：门面按 `DataSourceType` 分发，MySQL/Postgres 分别由独立策略承载 SQL 和默认 schema 规则，后续新增数据源只需增加策略实现。
- 阶段 A 创建表单真实化收口后，`SyncTaskWizard` 不再使用 mock 数据源和样例字段映射兜底；真实接口失败会展示错误和重试，并阻止保存/发布，避免创建看似成功但来源结构不真实的任务。
- 当前创建向导收敛为单表任务创建；批量选表/批量建任务与模板生成留到后续阶段，避免一个任务 payload 只含单个 `targetTable` 但 UI 允许多表造成误导。
- 阶段 16 已补齐 Airbyte connector definition/spec 发现接口，数据源创建表单可以加载 source definition 并把 `airbyteWorkspaceId`、`airbyteSourceDefinitionId`、`airbyteSourceId`、`airbyteDestinationId`、`airbyteDestinationDefinitionId` 写入 datasource config。
- Dagster 调度注册采用 reconciliation job 模式：Java 控制面在任务启用/暂停时发送 `UPSERT/DISABLE` 意图；由于 Dagster schedule 仍需 Python repository 定义，本地默认 `DAGSTER_SCHEDULE_ENABLED=false`，不阻断任务发布。
- 本地真实数据面联调出现 compose 定义问题：`airbyte/airbyte:latest` 与 `dagster/dagster:latest` 当前均无法拉取，需后续修正 Airbyte/Dagster 部署方式；基础依赖 `postgres/redis/keycloak/minio` 可启动并健康。

## 2026-06-17 数据面阻断项修正发现
- Airbyte 官方当前本地部署路径是 `abctl`：`abctl local install --port 8000` 会创建本地 kind/Kubernetes 环境并暴露 `http://localhost:8000`；Docker Compose 部署已不是支持路径。
- Dagster 官方 Docker Compose 部署形态是 webserver、daemon、code location、Postgres 多容器；项目中旧的 `dagster/dagster:latest` 单镜像不存在/不可拉取，且即使存在也缺少 code location。
- 阶段 17 将 Airbyte 从 `docker-compose.yml` 移出，改为 `scripts/airbyte-local.sh` + `make airbyte-up/status/credentials/down`；后端 `AIRBYTE_URL` 仍默认指向 `http://localhost:8000/api/v1`。
- 阶段 17 新增本地 Dagster code location，repo name 为 `onelake`、location name 为 `onelake-loc`，包含 `onelake_sync_task_schedule_reconcile` job，与 `DagsterScheduleClient` 默认参数对齐。
- `make dagster-up` 已验证通过，Dagster webserver 返回版本 `1.13.9`，GraphQL repository 查询返回 `onelake` / `onelake-loc`，`onelake_sync_task_schedule_reconcile` smoke run 可执行到 `SUCCESS`。
- 本地已安装 `abctl v0.30.4`；早先因 `https://airbytehq.github.io/charts/index.yaml` TLS 访问失败导致 Airbyte 部署阻塞。
- 2026-06-17 追加复核：当前 Airbyte 已通过 `abctl` 完成部署，`make airbyte-status` 显示 `airbyte-abctl` 2.1.0 与 `ingress-nginx` 4.15.1 release 均为 deployed，`kubectl get pods -n airbyte-abctl` 中核心 pod Ready，`http://localhost:8000` 返回 200。
- 数据集成全链路剩余缺口已从“Airbyte 本地部署阻塞”转为“真实端到端任务实证”：需要准备真实源库、Airbyte workspace/source/destination/connector config 与控制面运行环境，执行创建数据源 -> 探查 schema/table/columns -> 创建任务 -> 发布 -> 触发 Airbyte job -> reconcile -> 前端日志/诊断查看。

## 2026-06-17 真实端到端联调闭环发现
- Airbyte 2.1 本地 `abctl` 部署默认需要应用 OAuth client credentials；OneLake 后端需要配置 `AIRBYTE_CLIENT_ID`、`AIRBYTE_CLIENT_SECRET` 后为 Airbyte API 自动换取 Bearer token。
- Airbyte 2.1 的部分内部 API 已变成 workspace-scoped：definition spec 和 `/connections/list` 均需要 `workspaceId`，空 body 会返回 500/errorId。
- 采集任务只持久化 `targetTable` 不足以创建可执行 Airbyte catalog；本轮新增 `sourceTable` 并贯穿 VO/entity/DTO/mapper/前端 payload/Flyway 迁移。
- Airbyte connection 的 syncCatalog 应优先来自 `/sources/discover_schema`，再由 OneLake 覆盖 `syncMode`、`destinationSyncMode`、`aliasName`；纯手工 catalog 会出现 job 成功但 records 为 0 的风险。
- `targetTable=ods_airbyte.codex_orders` 需要在 connection create 时设置 `namespaceDefinition=customformat`、`namespaceFormat=ods_airbyte`，否则 Postgres destination 会按 source namespace 落到 `public`。
- Airbyte 2.x job 统计位于 `attempts[].attempt.recordsSynced/bytesSynced`；reconcile 需要兼容 nested attempt 才能正确回写 OneLake `sync_run.rows_read/rows_written`。
- 真实验证结果：本地源库 `onelake_src.public.codex_orders` 3 行，经 OneLake 创建数据源、schema/table/columns 探查、创建任务、发布、触发 Airbyte job 2、reconcile 后，落入 `onelake_lake.ods_airbyte.codex_orders` 3 行；OneLake run `b582f99c-f602-4dac-b2b1-72abd7e9c3a7` 为 `SUCCEEDED`，`rowsRead=3`、`rowsWritten=3`。

## 2026-06-20 Integration → Catalog 联动检查
- `integration.table.loaded` 事件已经能由 `SyncTaskServiceImpl#refreshRunSnapshot` 在运行进入 `SUCCEEDED` 终态时发布，payload 包含 `runId/taskId/externalJobId/sourceTable/targetTable/namespace/table/rowsRead/rowsSynced/sourceId/tenantId`。
- Catalog 当前已有 `SyncRunEventHandler` 消费 `integration.table.loaded` / `integration.sync.failed`，但成功时只执行 `assetRepo.findByTenantIdAndOmFqn(tenantId, targetTable)` 并刷新已存在资产的 `syncedAt`；如果目录资产不存在则直接跳过。
- 本地数据库实证：`common.outbox_event` 中已有多条 `integration.table.loaded` 且状态为 `PUBLISHED`，但 `catalog.asset` 仍为 0 条，说明当前联动无法自动把新入湖表登记进数据目录。
- Catalog 后端现有 API 为 `GET /api/v1/catalog/assets`、`GET /api/v1/catalog/assets/{id}`、`GET /api/v1/catalog/lineage/downstream`、`POST /api/v1/catalog/sync`；`CatalogSyncService` 目前只从 OpenMetadata `listTables` 拉取本地索引。
- Catalog 前端已有 `CatalogAPI` 封装，但 `CatalogSearch`、`AssetDetail`、`LineageGraph` 仍直接使用 mock 数据；下一步前端范围应仅替换数据来源和 loading/error 状态，不调整样式布局。
- `catalog.asset` 当前表结构缺少 columns、rows、sizeBytes、sourceRunId、sourceTaskId、rowCount 等字段；若要前端资产详情展示真实 schema，需要新增后端 DTO 或扩展表结构/接口，不应直接返回 JPA entity 给前端。
- `common.consumed_event` 记录显示最新 `integration.table.loaded` 已被 `catalog` 和 `orchestration` consumer 消费；因此 Catalog 资产为空不是消费失败，而是 handler 找不到已有资产后跳过。
- `module-catalog` 当前没有测试目录；下一步应新增 `SyncRunEventHandlerTest`、`CatalogServiceTest` 或 controller slice 测试，覆盖成功事件首次建档、重复事件幂等刷新、失败事件不覆盖新鲜度。
- Maven 依赖上 `module-catalog` 只依赖 `module-common`，不依赖 `module-integration`；因此 Catalog 不应直接注入 Integration Repository。若目录需要字段信息，应由 `integration.table.loaded` payload 携带字段/映射摘要，或通过独立公共契约 DTO 传递。
- 下一步最小闭环建议：先在 Catalog 消费 `integration.table.loaded` 时本地 upsert `catalog.asset`，让采集成功后的 ODS 表可在目录搜索/详情 API 中出现；OpenMetadata 注册/双向同步作为增强项，不作为首轮阻塞。

## 2026-06-20 最小可见闭环实施发现
- `SyncRunEventHandler` 已从“只刷新已存在资产”改为成功事件首次建档或刷新：缺失资产时创建 `TABLE` 类型资产，`omFqn=targetTable`，`layer` 从 namespace/FQN 前缀推导，默认 tags 为 `["integration","auto"]`。
- Catalog API 已改为返回 `AssetDTO`，对齐前端 `Asset` 的关键字段，避免前端直接依赖 JPA Entity。
- `CatalogSearch` 已从 mock 资产列表切到 `CatalogAPI.listAssets()`；本轮未改页面布局和样式，只增加 loading/error/empty 数据状态。
- 已消费的历史 `integration.table.loaded` 不会自动重放；部署新代码后，下一次成功采集事件会自动建档。若要历史补数，应另做 backfill/replay 工具或管理入口。

## 2026-06-20 Integration -> Catalog 第二轮实施发现
- 第一轮全链路验证已通过：MySQL 源表新增测试行后，Airbyte job `6` 对应 run `b1dbe486-3ec3-4e01-9623-22d77e76d959` 成功，`rowsRead=9`、`rowsWritten=9`，目标库 `onelake_lake.ods.ods_customers_100k` 为 9 行，Catalog 自动创建资产并在目录搜索页显示。
- 第二轮实现后，`integration.table.loaded` payload 增加 `fieldMapping`，Catalog 消费成功事件后将字段映射转成 `catalog.asset.columns`，同时写入 `catalog.lineage_edge.column_level`。
- 本地测试库已应用 `catalog/V2__asset_columns.sql`，新增 `catalog.asset.columns jsonb`；本地运行仍使用 `SPRING_FLYWAY_ENABLED=false`，因此测试库迁移需手工应用，后续应修复 Flyway 多目录版本策略。
- OpenMetadata 回写已接入 `OPENMETADATA_WRITEBACK_ENABLED` 开关，默认关闭；开启后会 best-effort 写 table 与 lineage，失败只记 warn，不阻塞本地 Catalog 建档。
- 进程级后端重启很关键：仅 DevTools 热重载 bootstrap 时，module-integration 新 jar 未完全进入运行链路，导致一次成功事件未携带 `fieldMapping`；完整重启后 run `fa739e86-10d0-44c1-9461-b120a74c363c` 的事件 payload 携带 20 个字段映射。
- 第二轮实证结果：Airbyte job `8` 成功，run `rowsRead=10`、`rowsWritten=10`；Catalog asset `ods.ods_customers_100k` 写入 20 个字段；血缘边 `ods.ods_customers_100k -> ods.ods_customers_100k` 写入 20 个字段映射，`jobRef` 指向该 run。
- `AssetDetail` 已接 `CatalogAPI.getAsset`，不改样式；同时修复了 `DetailPageLayout` 未传 `activeTab/onTabChange` 时 Tabs 被锁定在第一个 tab 的既有问题。
- 低数据量 run 的 `throughputRows` 原先为 0，原因是后端按整数 rows/sec 计算，10 行 / 约 30 秒会被截断；已改为 `Double` 小数吞吐，并在前端最多显示两位小数，例如 `0.32/s`。

## 2026-06-20 创建采集任务触发 PII 扫描计划发现
- 当前已有 `integration.sync_task.created` 事件：`SyncTaskServiceImpl#create` 在任务落库后发布，payload 包含 `name/sourceId/mode/sourceTable/targetTable/tenantId`。
- 当前已有 Security 消费入口：`SyncTaskCreatedEventHandler` 监听 `integration.sync_task.created`，并调用 `PiiScanService.enqueueScan(tenantId, targetTable)`。
- 当前 PII 扫描还不是真实目标表字段扫描：`PiiScanServiceImpl` 使用 `detectColumns(tableFqn)` 根据表名猜测 `phone/id_card/email/name`，没有读取 `fieldMapping`、`source_schema_snapshot` 或 Catalog asset columns。
- `CreateSyncTaskVO` 和 `SyncTaskDTO` 已有 `fieldMapping`，这是最适合本轮最小闭环的字段来源；创建任务时把 `fieldMapping` 带进事件，可以避免 Security 模块直接依赖 Integration Repository。
- `security.pii_scan_record` 当前没有唯一约束，Repository 也没有按 `tenantId + fqn` 查重；重复事件、任务重建或 replay 可能产生重复 PII 记录。
- 建议本轮将 PII 扫描服务升级为字段驱动：新增 `enqueueScan(tenantId, tableFqn, columns)` 或等价 DTO，优先使用事件 payload 中的 target 字段名/type/classification；字段为空时再降级到现有表名猜测。
- 对于本地测试任务 `ods.ods_customers_100k`，字段映射中存在 `phone_hash/email_hash/id_card_hash/full_name/customer_no`，规则需要识别 `_hash` 后缀和 `full_name/customer_no` 这类常见敏感字段命名。
- 前端 PII 页面已通过 `SecurityAPI.listPiiScan()` 接真实接口并有 mock fallback；后端扫描记录写入后无需改样式，最多做字段状态枚举大小写适配检查。

## 2026-06-20 创建采集任务触发 PII 扫描实施发现
- `integration.sync_task.created` payload 已扩展为结构化 Map，包含 `taskId/name/sourceId/mode/sourceTable/targetTable/fieldMapping/tenantId`；Security 可直接从事件读取目标表和字段映射，不依赖 Integration Repository。
- `SyncTaskCreatedEventHandler` 已解析事件中的 `fieldMapping`，调用 `PiiScanService.enqueueScan(tenantId, targetTable, columns)`；字段为空时保留原有表名降级扫描能力。
- `PiiScanServiceImpl` 已升级为字段驱动扫描：优先识别 `target/name/column/columnName/targetColumn/source` 中的目标字段名；当前可识别手机号、邮箱、身份证、姓名、银行卡、客户编号等常见命名，包括 `_hash` 后缀字段。
- `security.pii_scan_record` 已增加本地幂等保护：Repository 按 `tenantId + fqn` 查重，Flyway V4 清理历史重复记录后创建唯一索引 `uq_pii_scan_tenant_fqn`。
- 本轮没有修改前端 UI；验证使用后端 API 和数据库记录完成。后续如需让 Security 页面更强，只应做 API 字段适配或刷新策略，不调整样式。
- 本地端到端验证结果：创建测试任务 `codex_pii_scan_20260620223345` 后，`integration.sync_task.created` 事件状态为 `PUBLISHED`，payload 中 `fieldMapping` 数量为 5；`security` consumer 已消费该事件，并生成 4 条 PII 扫描记录：`phone_hash=手机号/L3`、`email_hash=邮箱/L3`、`id_card_hash=身份证/L4`、`full_name=姓名/L3`。

## 2026-06-20 PII 扫描结果反哺 Catalog 实施发现
- Security 扫描新增 PII 记录后已发布 `security.pii.detected` 事件，payload 包含 `tenantId/tableFqn/detectionCount/detections[]`；每个 detection 包含 `fqn/column/piiType/confidence/suggestLevel/status`。
- Catalog 新增 `PiiDetectedEventHandler` 消费 `security.pii.detected`，按 `tenantId + tableFqn` 找资产；资产不存在时会先预登记 `TABLE` 资产，描述为“由安全扫描自动登记，等待采集成功后刷新字段 schema”。
- Catalog 字段 JSON 合并策略为：保留已有字段 schema 的 `name/type/description`，补充或更新 `classification/suggestLevel/piiType/piiConfidence`；表级 `classification` 取检测结果中的最高密级。
- 事件顺序已经处理：如果 PII 扫描先于 `integration.table.loaded`，后续 table.loaded 用真实 fieldMapping 刷新 `columns` 时会继承已有 PII 标签，避免安全分类被 schema 刷新覆盖。
- Catalog API 的 `AssetColumnDTO` 已增加 `piiType` 与 `suggestLevel` 字段；这是 API 字段扩展，不涉及前端样式修改。
- 本地实证结果：创建测试任务 `codex_pii_catalog_20260620224545` 后，`integration.sync_task.created` 与 `security.pii.detected` 均为 `PUBLISHED`；`security` 消费 created，`catalog` 消费 pii.detected；Catalog 资产 `ods.codex_pii_catalog_20260620224545` 表级密级为 `L4`，字段包含 `phone_hash/email_hash/id_card_hash/full_name` 的 PII 标签。

## 2026-06-21 Catalog 前端接入字段级 PII 标签发现
- 后端 Catalog API 已返回 `AssetColumnDTO.piiType` 与 `suggestLevel`，但前端 `AssetColumn.piiType` 原先仍定义为英文枚举，和后端中文类型 `手机号/邮箱/身份证/姓名` 不匹配。
- `AssetDetail` 的 Schema 表格原先只展示字段 `classification`，因此用户能看到密级，但看不到 PII 类型和建议密级。
- 本轮保持页面样式和组件体系不变，仅补齐字段类型并在现有 Schema 表格增加 `PII类型`、`建议密级` 两列。
- 浏览器实证：资产 `ods.codex_pii_catalog_20260620224545` 的 Schema 表格已显示 `phone_hash=手机号/L3`、`email_hash=邮箱/L3`、`id_card_hash=身份证/L4`、`full_name=姓名/L3`。

## 2026-06-21 数据质量模块实施计划与 UI 完整性检查发现
- 后端 `module-quality` 当前具备基础 API：`POST/GET /api/v1/quality/rules`、`GET /rules/by-target`、`POST /results`、`GET /results/{ruleId}`、`GET /alerts`、`POST /alerts/{id}/close`。
- 后端表结构已覆盖 `quality.rule`、`quality.run_result`、`quality.score_snapshot`、`quality.alert`，但本地测试库四张表当前均为 0 条数据。
- 后端当前只支持“规则/结果/告警的记录与查询”，还没有真实稽核运行器、质量分计算、Outbox 事件、Catalog 质量分回写、数据服务/编排门禁联动。
- 前端已有三张质量页面且视觉结构完整：`/quality/rules` 规则配置、`/quality/results` 稽核结果、`/quality/gate` 质量门禁失败处理。
- `/quality/rules` 页面浏览器检查显示规则表、统计、创建弹窗、编辑/试跑按钮均存在；但页面使用 `qualityRules` mock，创建弹窗资产/字段是固定选项，确认按钮只触发本地 `message.success`。
- `/quality/results` 页面浏览器检查显示整体通过率、四维度评分、趋势图、异常行明细；但标题、评分、趋势和异常样例均来自 mock，不支持按规则/资产选择真实结果。
- `/quality/gate` 页面浏览器检查显示失败原因、异常行、下游影响、处理动作和审批记录；但处理动作只触发本地提示，不会写后端告警/豁免/重跑状态。
- 前端已有 `QualityAPI` 封装 `listRules/openAlerts/recentResults`，但未在质量页面使用；缺少 `createRule/recordResult/closeAlert/rulesByTarget` 等方法。
- UI 完整性结论：原型级页面完整，能表达目标体验；产品级闭环不完整，主要缺真实数据接入、加载/空状态、规则创建提交、结果页参数化、门禁动作 API 化。

## 2026-06-21 数据质量规则与稽核结果最小闭环实施发现
- 质量规则后端已从直接暴露实体调整为 DTO 契约，规则新增 `targetColumn` 与 `schedule`，可以表达“资产 + 字段 + 规则类型 + 调度策略”的基础产品参数。
- `quality.rule` 本地新增 `target_column`、`schedule` 字段和目标查询索引；`quality.run_result.sample` 已补齐 PostgreSQL `jsonb` 写入映射，避免记录异常样例时出现 varchar/jsonb 类型错误。
- 新增 `POST /api/v1/quality/rules/{id}/run` 试跑接口：当前执行器是控制面最小实现，按规则类型生成可验证的运行结果、失败样例和告警，不代表已经接入 Trino/JDBC 对目标表做真实数据采样。
- 规则试跑未通过时会写入 `quality.alert`，并发布 `quality.check.failed`；通过时发布 `quality.check.completed`。事件 payload 包含 `tenantId/ruleId/resultId/targetFqn/targetColumn/ruleType/passRate/failedRows`。
- `QualityAPI` 已补齐 `createRule/runRule/rulesByTarget/recentResults/openAlerts` 等方法，且返回类型改为解包后的前端 DTO。
- `/quality/rules` 已从 mock 切换为真实 API：规则列表、统计、创建、资产/字段选择和试跑都走后端；资产和字段来自 Catalog API。本轮未调整页面布局和样式。
- `/quality/results` 已从 mock 切换为真实 API：规则选择、整体通过率、失败行数、趋势和异常样例来自 `quality.run_result`；没有结果时显示现有空状态。本轮未调整页面布局和样式。
- `/quality/gate` 本轮未接入真实告警处理动作，仍属于下一轮；后续应优先接 `openAlerts/closeAlert` 和门禁审批/豁免状态，而不是改 UI 样式。
- 本地实证结果：创建规则 `0b95c483-6add-4705-9c03-2af512829d73` 后执行试跑，生成失败结果 `9c565dd9-16b3-4d26-ab75-1473fcd6d0cc`，`passRate=96.00`、`failedRows=32`、异常样例 3 行；`quality.check.failed` 已发布为 `PUBLISHED`。

## 2026-06-21 数据质量门禁失败处理最小闭环实施发现
- 质量告警列表已从直接返回 JPA Entity 改为 `QualityAlertDTO`，除告警基础字段外，还携带 `targetFqn/targetColumn/ruleType/expression/passRate/failedRows/sample`，前端 Gate 页无需再拼 mock 结果。
- `closeAlert` 已补租户隔离校验：只能关闭当前 `TenantContext` 下的告警，跨租户告警会返回“无权处理该质量告警”。
- `/quality/gate` 已从 `qualityResults/gateExemptions` mock 切到真实 `QualityAPI.openAlerts()`；页面标题、失败规则、异常行样例和影响资产均来自后端开放告警。
- Gate 页处理动作的当前边界是：`fix/exempt/warn` 调用 `closeAlert` 关闭当前待处理告警；`block` 代表保持阻断状态，不会把告警伪装成已解决。
- Gate 页审批记录不再展示 mock 数据；在审批/豁免实体和 API 完成前，保持真实空表状态。
- 前端没有改样式和布局，仅替换数据来源、接入 loading/empty 状态和后端动作；`message` 调用已改为 AntD provider 版本，浏览器验证不再产生 static message 警告。
- 本地实证结果：试跑规则生成开放告警 `70453798-c138-4b7e-a023-3a4e69cf1790` 后，浏览器 `/quality/gate` 展示真实目标表 `ods.codex_pii_catalog_20260620224545`、字段 `id_card_hash`、失败 32 行和 3 条异常样例；点击“降级为告警/应用”后页面进入“暂无质量门禁失败”，后端开放告警列表为空。

## 技术决策
| 决策 | 理由 |
|------|------|
| 以当前代码校准文档 | `RTK.md` 明确要求 docs 和 code 不一致时先信当前代码 |
| Airbyte/Dagster 本轮推进到真实数据面实证 | Airbyte 已通过 abctl 运行，Dagster 本地 compose 可用，Postgres 源表到目标库的真实同步已完成 |
| Airbyte 不再放入 `docker-compose.yml` | 官方本地部署已转向 `abctl`，保留无效 compose 服务会持续制造误导和启动失败 |

## 遇到的问题
| 问题 | 解决方案 |
|------|---------|

## 资源
- `RTK.md`
- `docs/数据集成模块技术方案（依赖 · 架构 · 数据流 · API · 关键代码）.md`
- `docs/数据面开发指南（部署 · Airbyte · dbt · Dagster · Trino）.md`
- `onelake-app/module-integration/`
- `onelake-app/web-console/src/pages/integration/`
- `onelake-app/web-console/src/api/index.ts`
- `docs/采集任务创建流程闭环迭代实施计划.md`

## 视觉/浏览器发现
- 本次任务暂不涉及浏览器视觉验证。

---
*每执行2次查看/浏览器/搜索操作后更新此文件*
*防止视觉信息丢失*

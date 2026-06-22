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

## 2026-06-21 SQL 工作台开发现状检查发现
- SQL 工作台后端已落在 `module-catalog`：`SqlWorkbenchController` 暴露 `/api/v1/lakehouse/sql/estimate`、`/execute`、`/history`、`/saved-queries`，`SqlWorkbenchService` 负责只读校验、Trino JDBC 执行、查询历史和保存查询。
- `catalog/V5__sql_workbench.sql` 已新增 `catalog.sql_query_history` 与 `catalog.saved_query`，具备租户隔离字段、执行人、SQL 文本、状态、耗时、扫描量、行数和错误信息。
- 当前执行链路是真 Trino JDBC，不是纯 mock：默认 `TRINO_JDBC_URL=jdbc:trino://localhost:18080/iceberg`，`docker-compose.yml` 已将 Trino 映射到宿主 `18080`，避开 Spring Boot 的 `8080`。
- 查询安全当前是字符串级只读校验：允许 `select/with/show/describe/desc/explain`，拒绝多语句和常见写操作关键字；尚未做 SQL AST 解析、Catalog 资产权限、字段级脱敏或行级策略。
- `estimate` 目前只返回“已通过只读校验”，`estimatedScanBytes` 为空，`thresholdExceeded=false`；还没有接 Trino `EXPLAIN`、`QueryInfo` 或 resource group 的真实扫描量/成本估算。
- `execute` 目前同步阻塞执行并最多返回 `maxRows` 行预览；超过预览上限时会继续遍历结果计算 `rowCount`，但没有服务端分页、异步 query id、取消、下载或结果缓存。
- 历史记录只保存执行摘要与错误；成功记录未写 `scanBytes`，也没有 Trino query id、catalog/schema、输入输出资产、SQL 指纹、快照版本或审计事件。
- 前端 `/lakehouse/sql` 已接真实 `CatalogAPI.listAssets()`、`SqlWorkbenchAPI.history()`、`savedQueries()`、`estimate()`、`execute()` 和 `saveQuery()`；Catalog 失败时仍 fallback 到 `lakehouseAssets` mock，表树没有 schema/字段层级，也没有点击表名插入 SQL。
- 前端页面显示“Trino / Spark 双引擎”，但后端非 Trino 会返回“当前仅支持 Trino 查询执行”；Spark 选项目前会产生可预期失败，属于产品文案与后端边界不一致。
- “发布为 API”和“加入流水线”目前只是前端导航，未把当前 SQL、参数、结果 schema 或上下文带到数据服务/编排模块；数据服务发布器本身直接拼接 `CREATE VIEW ... AS selectSql`，也缺少 SQL 工作台到受控发布的安全契约。
- 验证结果：`mvn -q -pl module-catalog -am test -Djacoco.skip=true` 通过，`pnpm exec tsc --noEmit` 通过；本轮未启动完整 Trino/后端/前端做浏览器实跑。

## 2026-06-21 SQL 工作台查询生命周期实施发现
- 后端已新增 `POST /api/v1/lakehouse/sql/queries`、`GET /queries/{id}`、`POST /queries/{id}/cancel`，用于异步提交、轮询状态和取消查询；旧 `/execute` 同步接口保留兼容。
- `SqlWorkbenchService` 使用内存运行态保存短期查询结果和 JDBC `Statement` 引用，取消时先调用 `Statement.cancel()`，再中断 Future，并把历史状态写为 `CANCELLED`。
- 查询状态读取和取消都按 `TenantContext.tenantId` 校验，避免用户通过 query id 读取或取消其他租户的运行中查询。
- Trino 结果预览不再为了精确总行数消费完整结果集，达到 `SQL_WORKBENCH_MAX_ROWS` 后直接截断返回，降低大结果查询拖垮工作台的风险。
- 前端 SQL 工作台已改为提交后轮询：运行按钮提交 query，查询中显示 loading 和“取消”按钮，终态后刷新历史；取消成功显示 `SQL 查询已取消`。
- 前端移除 Spark 选项，页面描述改为 Trino 交互式查询；默认 SQL 改为 `SHOW SCHEMAS`，表树点击会填入 `SELECT * FROM <fqn> LIMIT 100`。
- 验证结果：`mvn -q -pl module-catalog -am test -Djacoco.skip=true`、`mvn -q install -DskipTests -Djacoco.skip=true`、`pnpm exec tsc --noEmit` 和 `git diff --check` 均通过。

## 2026-06-21 SQL 工作台到 API 草稿联动实施发现
- 现有数据服务发布器面向 PostgREST/PostgreSQL 视图，不能直接把 Trino/Iceberg SQL 用 `CREATE VIEW dataservice_api... AS <trino sql>` 发布；因此本轮新增 API 草稿创建链路，避免伪装成已发布 API。
- `DataServiceController` 新增 `POST /api/v1/dataservice/apis/draft`，`DataServicePublisher#createDraft` 只校验 `apiPath/viewName/selectSql`、写入 `DRAFT` 状态和审计，不触发 APISIX/PostgREST 发布。
- SQL 工作台“发布为 API”现在通过 React Router state 携带当前 SQL、来源资产 FQN 和结果列，避免把长 SQL 放进 URL。
- API 构建向导接收 SQL 工作台上下文后预填 API 路径、视图名、来源、SQL、参数和返回字段；其中 `:param` 占位符会自动转成请求参数。
- API 构建向导的保存草稿和最后一步按钮均调用真实 `DataserviceAPI.createDraft`，保存成功后进入 API 详情。
- API 市场与 API 详情页已接 `DataserviceAPI.listApis/getApi`，失败时回退 mock；这样新建草稿在后端可用时能出现在数据服务页面。
- 本轮仍未实现 Trino SQL 真实发布为在线 API；下一步应设计统一 SQL API 网关或 Trino-backed service runtime，而不是复用 PostgREST 视图发布器。

## 2026-06-21 SQL API 草稿 Trino 调试运行实施发现
- 数据服务模块新增 `SqlApiRuntimeService`，通过同一组 `onelake.dataplane.trino.*` 配置连接 Trino，用于调试 API 草稿中的 `selectSql`。
- `POST /api/v1/dataservice/apis/{id}/debug` 会按当前租户读取 API 定义，执行只读 SQL 预览并返回 columns、rows、durationMs、rowCount、truncated。
- 调试服务支持 `:param` 命名参数，执行前转为 PreparedStatement `?` 参数；缺少参数时返回业务错误，不连接 Trino。
- 调试服务在连接 Trino 前执行只读/单语句校验，拒绝 `drop/insert/update/delete/create/alter` 等写操作；本轮新增单测覆盖缺参数和写 SQL。
- API 详情页调试区已从本地假响应改为 JSON 参数输入 + `DataserviceAPI.debugApi` 真实请求，返回结果原样 JSON 展示。
- 这仍是“开发者调试运行”，不是外部 API 网关：尚未接 AppKey 鉴权、订阅状态、限流、调用日志、动态脱敏、公开路由和参数 schema 校验。

## 2026-06-21 SQL 工作台真实边界与 Trino 观测收口发现
- `/lakehouse/sql` 表树已移除 Catalog 加载失败时的 `lakehouseAssets` mock fallback；失败时显示真实错误和重试，真实返回空数组时显示“Catalog 暂无可查询表”。
- `SqlExecuteResultDTO` 与 `SqlQueryHistoryDTO` 已增加 `trinoQueryId`，前端类型同步补齐；页面结构和视觉布局未调整。
- `catalog.sql_query_history` 新增 `trino_query_id` 字段与索引，后续可按 Trino query id 追踪执行引擎日志和 stats。
- `SqlWorkbenchService` 已接入 Trino JDBC `setProgressMonitor`，运行中和结束态会尽量采集 `queryId`、`processedBytes/physicalInputBytes`，并写回 `scanBytes`。
- 查询成功、失败、取消路径都会保留已采集到的 Trino query id 和扫描量；如果 JDBC 未暴露 progress stats，则字段保持空，不再伪造扫描量。
- 当前 `estimate` 仍未接 Trino `EXPLAIN` 或 resource group 阈值控制；安全网关仍是字符串级只读校验，SQL parser、Catalog 授权和 Security 脱敏仍是下一轮 P0。

## 2026-06-21 SQL 安全网关 parser 校验底座发现
- SQL 工作台和 SQL API 调试已从字符串关键字校验切到共享 `ReadOnlySqlValidator`，底层使用 JSqlParser 解析语句结构。
- 新校验器要求解析结果恰好一条语句，并只允许 `Select`、`ShowStatement`、`DescribeStatement` 和 `ExplainStatement` 包裹的 Select。
- `CREATE TABLE AS SELECT`、`INSERT ... SELECT`、`DROP`、多语句、`SELECT INTO`、无效 SQL 都会在连接 Trino 前失败。
- SQL API 调试中的 `:param` 命名参数当前可被 JSqlParser 4.9 正常解析；已有缺参数单测仍在 bind 阶段返回业务错误。
- 本轮仍未实现执行前 Catalog 资产授权，也未接 Security 模块字段脱敏和密级策略；parser 只是 P0 安全网关底座。

## 2026-06-22 SQL 执行前 Catalog 资产授权实施发现
- `ReadOnlySqlValidator` 已基于 JSqlParser `TablesNamesFinder` 提取 SQL 引用表，CTE 名不会被误当作资产，`SHOW` 无表引用时不触发资产校验。
- `SecurityService.requireQueryAccess` 会按当前 `TenantContext` 的租户和用户读取 ACTIVE 授权，只接受未过期且 `permissions.query=true` 的授权。
- SQL 工作台执行链路现在会在连接 Trino 前校验：所有引用表必须存在于当前租户 `catalog.asset.omFqn`，资产 owner 可直接查询，非 owner 必须有 Security 查询授权。
- SQL API 草稿调试链路也接入同样的 Catalog 资产授权；顺序是只读 parser 校验、命名参数绑定、资产授权、连接 Trino。
- 未登记资产会返回“SQL 引用资产未登记到 Catalog”或“SQL API 引用资产未登记到 Catalog”；无授权会返回“无权查询资产: <fqn>”。
- 本轮仍未实现结果返回前字段脱敏和密级策略，也未对 SQL 中字段级引用做列授权；下一步 P0 应接 Security masking/classification。

## 2026-06-22 SQL 结果字段脱敏与密级策略实施发现
- SQL 工作台和 SQL API 调试现在会复用 Catalog 资产校验阶段生成的字段保护上下文，按结果列名匹配 `asset.columns` 元数据。
- 字段保护上下文会读取 `classification`、`piiType`、`suggestLevel`，并把字段映射为 `<assetFqn>.<columnName>`，用于查询 Security 脱敏策略。
- `SecurityService.maskRows` 返回新的行集合，不修改原始 ResultSet 读取结果；显式策略支持 `NULLIFY`、`HASH`、`MASK`、`PARTIAL`，按 priority 取最高优先级。
- 当前角色范围通过 Spring Security authorities 判断，`roleScope` 可写 `ADMIN` 或 `ROLE_ADMIN`；没有登录鉴权对象时仅无角色范围策略生效。
- 未配置显式策略时，PII 字段或 L3/L4 字段默认部分脱敏，例如手机号形态会返回 `138****8000`。
- 多表 join 出现同名字段时不再跳过保护，而是合并所有候选字段 FQN；任一候选字段有显式策略即可命中，任一来源敏感也会触发默认脱敏。
- 当前脱敏匹配依赖 ResultSet column label 与 Catalog 字段名一致；如果用户通过 `select phone as p` 重命名敏感列，后续仍需基于 SQL select item/lineage 做 alias 回溯。
- 当前还没有列级授权、行级策略、下载/导出脱敏、外部 API 网关运行态 AppKey 调用脱敏；这些仍属于 P1/P2 安全闭环扩展。

## 2026-06-22 SQL 安全边界前端真实表达实施发现
- SQL 工作台现在不会只显示泛化失败，而是保留后端错误文案；对“未登记到 Catalog”“无权查询资产”“只读/多语句校验失败”补充用户可理解的边界说明。
- 查询历史表已增加“失败原因”，失败状态可以看到后端记录的错误，便于区分 SQL 语法、安全授权和 Trino 执行失败。
- 结果区和 API 调试区的脱敏提示当前是前端推断式：基于敏感字段名或返回值中出现 `****`/hash 形态触发；后续更稳妥的契约是后端 DTO 显式返回 `maskedColumns` 或 `securityNotices`。
- API 详情调试区现在把 `DataserviceAPI.debugApi` 的真实错误展示成页面内 Alert，并同步给 message；本地 mock API `api-1` 没有真实后端记录时会显示“接口不存在: api/v1/dataservice/apis/api-1/debug”。
- 浏览器验证已登录本地 `dev` 用户，`/lakehouse/sql` 表树可加载真实资产，`/dataservice/apis/api-1` 可进入调试 tab；控制台中观察到的 404 属于点击 mock API 调试真实后端时的预期错误。

## 2026-06-22 SQL 工作台 P1/P2 效率、估算与联动实施发现
- `estimate` 现在会尝试执行 Trino `EXPLAIN (TYPE IO, FORMAT JSON)` 并解析 `estimatedSizeInBytes` 等字段；如果本地 Trino 或 EXPLAIN 不可用，返回空扫描量并明确“不伪造”，执行阶段仍采集真实 query stats。
- 资源控制采用前后端双层：前端在估算超阈时弹出确认，后端在 `execute/submit` 前用同一阈值二次校验 `confirmLargeQuery`，防止绕过 UI。
- P2 Spark/自动路由没有伪装完成：AUTO 继续路由 Trino；Spark batch 在缺少可执行 runtime、长查询结果落盘、成本/血缘回写前不重新暴露为可执行选项。
- SQL 工作台表树已支持 Catalog 字段层级；本地当前两张真实资产没有返回 columns，所以浏览器只显示表节点，这是数据状态而非前端 fallback。
- Monaco completion 现在来自真实 Catalog 表/字段，加上 `select-limit`、`where-date` 常用片段；格式化按钮只是轻量 SQL 换行格式化，不是完整 SQL formatter。
- 保存查询从“按名称 upsert”扩展为可显式更新、删除和切换共享范围；删除按租户查询记录后执行，避免跨租户删除。
- API 草稿新增 `requestParams` 和 `responseSchema` JSONB 字段，API 向导会保存从 SQL `:param` 提取的参数和工作台传来的返回字段。
- SQL 工作台加入流水线现在创建真实 `orchestration.dag` 草稿，`enabled=false`，definition 中包含 SQL 节点；画布可从路由 state 或后端 DAG definition 初始化显示 SQL。
- 本轮修复了 `orchestration.dag.definition` jsonb 写入映射，否则本地创建 DAG 会因 varchar 写入 jsonb 返回 500。
- 本地浏览器验证过程中发现库缺少旧迁移 `catalog/V6__sql_query_history_trino_query_id.sql` 和本轮 `dataservice/V2__api_definition_sql_contract.sql`，已手工应用以恢复运行态；后续仍需修复 Flyway 多目录迁移命令，避免手工补 DDL。

## 2026-06-22 SQL 工作台 P0.5/P1 生产化收口发现
- SQL 工作台和 SQL API 调试结果已从“前端猜测脱敏”改为后端明确返回 `maskedColumns` 与 `securityNotices`；前端仅根据这两个字段展示策略提示和列头标记。
- `SecurityService.maskRowsWithNotices` 会返回脱敏后的 rows、实际处理列和安全提示；旧 `maskRows` 保留为兼容入口。
- 简单列别名保护已接入 JSqlParser：`select phone as p from ods.orders` 会让结果列 `p` 继承 `phone` 的 Catalog/Security 保护；函数、表达式和复杂血缘仍不硬猜。
- `estimate` 的 Trino EXPLAIN 失败现在会在 message 中带上真实原因，例如 Trino 不可达、SHOW/DESCRIBE 不产生扫描量或 EXPLAIN JSON 缺少可解析字段；扫描量仍保持空值而不是 mock。
- Catalog 新增 `POST /api/v1/catalog/assets/refresh-columns`，会对当前租户字段为空的 TABLE/VIEW 资产查询 Trino `information_schema.columns` 并回写 `asset.columns`，同时保留已有 PII/密级标注。
- SQL 工作台表树加载真实 Catalog 后，如果发现资产缺字段，会自动尝试调用字段补全接口并重新读取；补全失败显示真实 warning，不回退 mock。
- 数据服务新增已发布 SQL API 的运行时入口 `GET /api/v1/dataservice/apis/runtime/**`：使用 `X-App-Key` 找到租户，再按 `tenantId + apiPath` 定位 `PUBLISHED` API，校验 APPROVED 订阅、日配额，执行参数绑定 SQL，并复用 Catalog 授权与结果脱敏。
- API 运行时调用会写入 `dataservice.api_call_log`，日配额使用 `dataservice.quota_usage`；当前是最小控制面网关，尚未接 APISIX consumer 动态注册、secret_hash 签名校验和 IP 白名单。
- Flyway 迁移从旧的“一次扫描所有 schema 目录”改为 `scripts/flyway-migrate.sh` 按 schema 顺序执行；integration 目录重复 `V2` 已消除，first iteration 迁移改为幂等 `V6`。
- 实际执行 `make migrate` 后继续发现并修复了 seed 迁移兼容问题：integration/security seed 中的 psql `\set` 变量不被 Flyway 支持，integration demo sync_task/run 使用了非法 UUID，security V2 索引重复缺少 `IF NOT EXISTS`。
- 修复后 `make migrate` 已按 common、integration、orchestration、catalog、modeling、quality、security、dataservice 顺序完整执行通过。
- 检查 Dagster code location 后确认当前只有 `onelake_sync_task_schedule_reconcile`，没有真实 SQL node job；因此本轮没有把 SQL 工作台 DAG 草稿伪装成可执行流水线，后续需要先补 Dagster SQL job 与 run config 契约。

## 2026-06-22 ODS 到 DWD 标准闭环方案发现
- 当前 OneLake 已具备 ODS 入湖、Airbyte run/reconcile、`integration.table.loaded`、Catalog 建档、字段 PII、质量最小闭环、SQL 查询安全和 dbt 示例模型，但还缺少“DWD 模型定义 -> dbt 生成 -> Dagster 执行 -> 质量门禁 -> Catalog/血缘回写”的贯通链路。
- 标准链路应把 ODS 作为 Airbyte 默认落点，DWD 由 ODS 派生；DWD 直写不能作为主链路，否则会绕过原始保真、schema 漂移隔离、质量门禁和重跑审计。
- DWD 写入不应由 SQL 工作台承担；SQL 工作台保持查询、诊断、草稿和 API/流水线入口，生产加工由 Modeling + dbt + Dagster 负责。
- 首批可执行闭环应控制为“单 ODS 表 -> 单 DWD 表”，先验证样例链路：源端 `codex_orders` -> `ods.ods_codex_orders` -> `dwd.dwd_trade_order_df`。
- 需要新增 DWD 模型持久化能力，建议落在 `module-modeling`，最小实体包含 source_fqn、target_fqn、materialization、unique_key、incremental_column、partition_expr、sql_text、dbt_model_name、status 和 owner 信息。
- dbt 产物生成应包括 `sources.yml`、`models/intermediate/<model>.sql`、`models/intermediate/schema.yml`，并以 `dbt parse`/`dbt build --select <model>` 作为数据面验证。
- Dagster 当前只有 schedule reconciliation job，下一步必须补 `dagster-dbt` asset/job 和 run config 契约，不能把 DWD 模型标记为可执行后只停留在 Java DAG 草稿。
- `integration.table.loaded` 自动触发 DWD 时必须做幂等控制，建议以 `integrationRunId + modelId` 限制重复触发；缺少 ODS 字段 schema 时记录 warning，不硬跑。
- DWD 成功后需要同时更新 Catalog 资产、表级/字段级血缘、新鲜度和质量状态；质量失败时保留失败 run 和告警，不把 DWD 资产伪装成可用。
- 第一批实施建议做到样例基线、DWD 模型草稿、dbt 生成/校验、Dagster dbt 执行、ODS 事件自动触发、质量和 Catalog 回写；多表 Join、Backfill、Schema 变更审批、Iceberg compaction 放到后续生产化迭代。

## 2026-06-22 ODS 到 DWD 方案链路匹配评审发现
- 方案主线与整体设计匹配：源端入湖归 Integration，DWD 业务定义归 Modeling，运行编排归 Orchestration/Dagster，数据转换归 dbt，治理结果归 Catalog/Quality/Security。
- 需要把 DWD 模型显式映射为数据开发编排对象：模型草稿阶段就预留 `orchestration_dag_id/dagster_job/artifact_path/last_run_id`，dbt 生成阶段同步创建 disabled 的 `orchestration.dag` 草稿。
- `integration.table.loaded` 只表达 ODS 入湖完成，不应被拿来代表 DWD 完成；DWD 终态应新增 `modeling.model.loaded` / `modeling.model.failed` 事件，再由 Catalog/Quality/通知消费。
- 质量门禁必须来自真实 dbt/Dagster 执行产物，第一批应解析 `run_results.json/manifest.json/catalog.json` 写入 `quality.run_result` 和 `quality.alert`，不能复用质量模块当前的控制面模拟试跑作为生产门禁。
- DWD 字段安全标签要沿 ODS 字段映射继承；如果 DWD 模型做了 hash/脱敏/字段降敏，需要记录转换说明和新的建议密级，避免安全标签在派生层丢失。
- 第一批应拆成 MVP-A 和 MVP-B 两个发布点：MVP-A 做手动 DWD 模型运行闭环，MVP-B 再做 ODS 事件自动触发和治理回写；没有到 MVP-B 前不能宣称标准 ODS->DWD 闭环完成。
- 原方案中的迭代 6 前端可观测仍合理，但首批也需要最小前端入口与状态展示；完整模型详情、拖拽画布、复杂 DAG 拓扑仍放后续，避免主链路被 UI 工程拖散。

## 2026-06-22 ODS 到 DWD 加工治理与算力/流水线兼容评审发现
- 主流产品普遍把加工逻辑、质量治理、编排任务和计算资源绑定建模：Databricks Jobs/Pipelines 绑定任务、compute、expectations 和 Unity Catalog lineage；Dagster 将 dbt model 表示为 asset 并把 dbt tests 接为 asset checks；Airflow Pools 用于任务并发资源控制；ADF/Fabric data flow 是可视化加工逻辑但作为 pipeline activity 运行；AWS Glue Data Quality 支持在 Catalog 和 ETL pipeline 中执行质量规则。
- 因此 ODS->DWD 不能只做“DWD SQL/dbt 生成”，还必须明确加工治理图和算力资源契约：清洗、标准化、去重、脱敏/加密、质量门禁、异常处理、DWD 输出、血缘回写都应是可持久化节点或策略。
- OneLake 当前前端存在 `OperatorMarket`，但它是“算子市场”而不是完整“算力市场”；SQL 工作台已有 `resourceGroup` 选择，代表资源组/计算画像的雏形。方案应把算子市场和算力资源拆开设计，避免术语混淆。
- 当前 `DagNode` 类型已支持 `INPUT/GOVERN/MASK/ENCRYPT/OUTPUT/QUALITY_GATE/SQL` 等节点，`SqlWorkbench` 已能创建真实 `orchestration.dag` 草稿并带 `resourceGroup`，说明 DWD 方案与流水线/算子市场方向兼容。
- 兼容性缺口在后端契约：`orchestration.dag` 当前只有 `definition/schedule/enabled` 等字段，`job_run` 没有 `resource_group/compute_profile/cost/queue` 观测字段；DWD 模型方案也还需要补 `operator_graph_version/resource_group/compute_profile/engine/cost_policy`。
- 方案已补充 `3.3 加工治理、流水线与算力资源兼容评审` 和 `6.5 迭代 2.5`：DWD 模型保存时生成默认 operator graph，编译为 dbt SQL/tests 与 orchestration DAG，资源组写入模型、DAG、run，首批固定 Trino/dbt/Dagster，不建设完整算力市场。
- 评审结论：原方案主线兼容流水线与算子市场，但如果不执行迭代 2.5，会出现 DWD 模型、流水线画布、算子市场和资源控制四套定义漂移；因此迭代 2.5 应进入 MVP-A，不能推迟到生产化阶段。

## 2026-06-22 ODS 到 DWD 迭代 0 样例基线实施发现
- 新增 `onelake-app/scripts/ods-dwd-baseline.sh` 和 Makefile 入口 `ods-dwd-baseline`、`ods-dwd-verify`，用于幂等准备并验证固定样例链路。
- 样例源表固定为 `onelake_src.public.codex_orders`，字段为 `order_id/user_id/amount/status/order_time/updated_at`，数据为 10 行，其中 3 行脏数据：负金额、空状态、空下单时间。
- 样例 ODS 表固定为 Trino/Iceberg `iceberg.ods.ods_codex_orders`，脚本会重建该样例表并写入同样 10 行数据；DWD 目标表名预留为 `dwd.dwd_trade_order_df`，本轮不创建 DWD 数据。
- 运行态验证写入了一条标准 `integration.table.loaded` Outbox 事件，事件进入 Redis Stream 后被 `orchestration` 和 `catalog` 两个 consumer 消费。
- Catalog 侧已生成 `ods.ods_codex_orders` 资产：`layer=ODS`、`row_count=10`、`columns=6`；同时写入 `public.codex_orders -> ods.ods_codex_orders` 血缘，字段级映射数为 6。
- Airbyte OSS API 当前通过后端查询 connector definitions 返回 Unauthorized，说明后端 Airbyte auth 配置仍未接入当前运行态；迭代 0 没有把真实 Airbyte 新建 connection 作为继续推进的阻塞项，后续 DWD 模型以稳定 ODS Iceberg 表和 `integration.table.loaded` 事件契约作为基线。
- 迭代 0 验证命令通过：`make ods-dwd-baseline`、`make ods-dwd-verify`、`mvn -q -pl module-catalog -am test -Djacoco.skip=true`、Trino 行数/脏数据查询、`git diff --check`。

## 2026-06-22 ODS 到 DWD 迭代 1 派生入口与模型草稿实施发现
- DWD 模型草稿已落到 `module-modeling`，新增 `data_model/source/column_mapping` 三张表；模型定义保留 `dbtModelName`、`dagsterJob`、`orchestrationDagId`、`artifactPath`、`lastRunId` 等编排占位字段，满足后续迭代 2/3 继续接 dbt 和 Dagster。
- `DwdModelService` 不直接依赖 `module-catalog` Java API，而是通过 `JdbcTemplate` 读取 `catalog.asset`，避免模块依赖倒挂；服务层校验 ODS 资产存在、layer 为 ODS、字段非空、DWD 表名符合 `layer_domain_business_granularity`。
- 字段映射会继承 ODS 字段的 `classification/piiType/suggestLevel`；如果字段表达式包含 mask/hash/sha/encrypt，validate 不提示敏感字段直通，否则 L3/L4 直通会产生 warning，为后续加工治理节点接入保留抓手。
- 前端复用现有建表/建模向导，但在 `derive=dwd` 模式下锁定 DWD 分层、预填源 ODS 字段、保留 `sourceName/sourceType` 映射，并提交到 `ModelingAPI.createDwdDraft`；这避免把 DWD 草稿误发布成 Catalog 物理表。
- 浏览器真实路径验证通过：ODS 详情页展示“派生 DWD”，向导自动带入 `ods.ods_codex_orders`、6 个字段和 `days(order_time)` 分区建议，提交后落库模型 `346b0f13-712b-41a2-8749-a25f96c19924`，状态 `DRAFT`、字段映射 6 条。
- 后端 API 实测创建的随机模型 validate 返回 `ok=true`、依赖 `ods.ods_codex_orders`、输出 6 列；浏览器提交模型再次 validate 也通过。
- 迭代 1 的明确边界：仍不生成 dbt 文件、不创建真实 DWD Iceberg 表、不触发 Dagster；下一轮必须实现 dbt SQL/YAML 产物、静态校验和 disabled orchestration DAG 草稿，否则 DWD 模型会停留在控制面。
- 浏览器控制台仅出现既有 React Router v7 future flag warning 和 Ant Design 静态 `message` 上下文 warning；没有 ODS->DWD 接口失败。AntD warning 来自现有 `useAsyncAction` 全局 message 用法，建议后续统一接 `App.useApp`。

## 2026-06-22 ODS 到 DWD 迭代 2 dbt 生成与静态校验实施发现
- DWD 模型 compile 已从“返回预览 SQL”推进到真实写 dbt 产物：生成 source YAML、intermediate SQL 和模型 schema YAML；样例模型 `dwd_trade_codex_orders_df` 可被 dbt parse 识别。
- 为避免覆盖手写示例 `models/sources.yml`，生成源定义放在 `models/generated/sources.yml`；这是单源单目标阶段的稳妥选择，后续多模型需要演进为集中合并生成或模型注册表。
- compile 会创建或更新 disabled `orchestration.dag`，而不是直接启用可运行任务；这保持了“迭代 2 只静态校验，不触发 Dagster”的边界。
- 后端默认工作目录是 `onelake-app/bootstrap`，所以 dbt project 默认路径必须是 `../dbt`；如果部署形态变化，可以通过 `ONELAKE_DBT_PROJECT_DIR` 或 `onelake.dbt.projectDir` 覆盖。
- `make backend` 原本只跑 `-pl bootstrap spring-boot:run`，子模块改动后可能继续使用旧 jar。已改为先 `-pl bootstrap -am compile`，再运行 bootstrap，避免新增 controller/DTO 后 OpenAPI 仍是旧 schema。
- `uvx --from dbt-trino dbt parse --profiles-dir .` 可作为本地无 dbt 安装时的轻量验证方式；parse 通过，仅剩既有 `models.onelake.staging` 未命中配置提示。

## 2026-06-22 ODS 到 DWD 迭代 2.5 加工治理与算力资源契约实施发现
- `modeling.data_model` 已有稳定的加工治理和资源契约字段：`pipeline_mode/operator_graph_version/operator_graph/resource_group/compute_profile/engine/cost_policy`。
- compile 阶段会生成系统默认 operator graph，并同时写到模型和 orchestration DAG；当前样例链路节点为 `INPUT/TRANSFORM/GOVERN/QUALITY_GATE/DBT_MODEL/OUTPUT`，敏感字段存在时会插入 `MASK` 节点。
- 默认资源画像是 `TRINO_DBT + default + trino-small`，cost policy 固定 1TB 扫描阈值、30 分钟超时、0 次重试和大扫描确认。这样后续算力市场/资源组功能可以接模型字段，不必反解析页面或 DAG。
- 迭代 2.5 仍不做完整算子市场注册、不开放拖拽编辑，也不启用任意引擎路由；它只把 DWD 模型、DAG 和 dbt 产物之间的加工/资源契约固定住。
- 真实模型 `346b0f13-712b-41a2-8749-a25f96c19924` compile 后状态为 `VALIDATED`，operator graph 节点数 6，DAG `6c0560c0-627c-483e-9072-088a96e614e0` 仍 disabled，符合“生成可编排草稿但不运行”的边界。

## 2026-06-22 全局任务条实施方案发现
- `docs/数据平台 · 原型设计与交互说明文档.md` 对全局任务条的定位是底部/右下角常驻长任务进度，覆盖采集、稽核、Compaction 等长任务；通用交互要求触发运行后 Toast、任务条实时反馈、完成后 Toast 和通知中心红点。
- `docs/FRONTEND_VERIFICATION.md` 将全局任务条标记为已完成，但这是前端原型完成，不等于真实数据闭环完成。
- 当前 `TaskProgressBar.tsx` 已实现折叠触发器、展开面板、状态图标、进度条、平均进度和空态；`App.tsx` 已在 TopBar 和底部 fixed 位置接入。
- 当前 `stores/app.ts` 的 `tasks` 来源是 `mock/index.ts` 的 `runningTasks`，没有真实 API；`RunningTask` 类型也过窄，缺少 queued/cancelled、link、cancelEndpoint、sourceModule、refId、errorMessage、时间戳等运行追踪字段。
- 后端已有多个可接入来源：`integration.sync_run` 支持 run 详情/日志/取消/reconcile；`catalog.sql_query_history` 支持 SQL 异步查询历史；`orchestration.job_run` 记录 DAG run；`quality.run_result` 和 `quality.alert` 表达质量结果和门禁失败。
- 公共层已有 Outbox、Redis Stream 消费、TenantContext、common.alert 和 common.notification 表结构，但没有统一 running task 投影、Task Controller 或 Notification Service。
- 推荐把全局任务条设计为 `common.running_task` 只读投影，而不是直接让前端分别查询每个业务模块。P0 使用轮询即可，后续再增强 SSE/WebSocket。
- 任务条和通知中心应分工：任务条展示当前/近期运行状态，通知中心展示失败、审批、告警、需要处理的结果消息；两者通过任务失败或终态事件联动。
- 第一轮最小实施切片建议只覆盖采集 run：新增 common 任务投影和 API，从 `integration.sync_run` 聚合真实任务，前端移除 `runningTasks` mock 并接入 `TaskAPI.listRunning`。

## 2026-06-22 全局任务条 P0 实施发现
- 已新增 `common.running_task` 统一任务投影和 `/api/v1/tasks/running`、`/api/v1/tasks/{id}/dismiss` 后端 API；第一轮由 `RunningTaskService` 在查询时同步采集 `integration.sync_run` 状态，后续 SQL、编排、质量可以复用同一投影。
- 采集运行映射为 `sourceModule=INTEGRATION`、`taskType=COLLECT`、`refType=sync_run`，状态统一为 `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED`；失败任务保留至用户忽略，成功/取消任务进入近期完成窗口。
- 任务详情跳转统一使用 `link`，采集 run 指向 `/integration/sync-tasks/{taskId}/runs/{runId}`；运行中任务暴露 `cancelEndpoint=/integration/sync-tasks/runs/{runId}/cancel`，前端取消动作不再硬编码业务路径。
- 前端 `RunningTask` 类型已扩展 source module、引用 id、错误码/文案、时间戳、跳转和取消端点；`TaskProgressBar` 只消费统一任务契约，不再依赖采集模块内部类型。
- `useGlobalTasks` 根据展开态和页面可见性调整轮询频率：展开 2 秒、折叠 5 秒、后台 30 秒；当前 P0 仍使用轮询，未引入 SSE/WebSocket。
- 前端已移除 `runningTasks` mock 初始化，`App.tsx` 通过真实 `TaskAPI` 驱动全局任务条，支持查看、取消、忽略和加载失败提示。
- API 实测返回两条真实任务：失败的 `orders_sync -> ods.orders` 和运行中的 `user_cdc -> ods.users`；浏览器展开全局任务条可看到这两条任务，控制台无 error。

## 2026-06-22 全局任务条 P1 多来源接入实施发现
- `RunningTaskService` 已把 SQL 查询、编排 DAG run 和质量稽核结果纳入同一个 `common.running_task` 投影，前端任务条无需了解各业务模块表结构。
- SQL 查询映射为 `LAKEHOUSE/SQL/sql_query`，标题使用压缩后的 SQL 文本；运行中查询可通过统一 `cancelEndpoint` 取消，终态跳转到 `/lakehouse/sql`。
- 编排 run 映射为 `ORCHESTRATION/DAG/job_run`，使用 DAG 名称和 Dagster run id 展示进度，跳转到 `/orchestration/pipelines/{dagId}`；当前没有全局取消能力，避免承诺 Dagster 取消闭环。
- 质量稽核映射为 `QUALITY/QUALITY/quality_run_result`，失败结果包含通过率、异常行数和 `QUALITY_CHECK_FAILED`，用于任务条近期可见和后续通知中心联动。
- SQL/编排/质量源同步只拉取运行中或最近 10 分钟结果，解决第一次接入时历史失败 SQL/质量结果批量占满任务条的问题；进入投影后的失败仍保留到用户忽略，符合“失败需要被看见”的交互语义。
- 本地 runtime 需要先 `mvn -q install -DskipTests -Djacoco.skip=true` 刷新 installed SNAPSHOT，再启动后端；否则 `make backend` 分段运行时可能继续加载旧 `module-common` jar。
- API 验证结果：清理 SQL/质量/编排投影后，`/api/v1/tasks/running?includeRecent=true&limit=50` 返回质量失败、`SQL 查询 SHOW SCHEMAS`、`orders_sync` 失败、`user_cdc` 运行中 4 条任务；临时 `orchestration.job_run` 也能映射为 `ORCHESTRATION/DAG` 并在验证后清理。
- 浏览器验证结果：`/dashboard` 折叠态显示 `任务 4 / 1 运行中`，展开面板可见质量稽核、SQL、采集失败和采集运行中任务，控制台无 error。

## 2026-06-22 全局任务条 P2 通知中心真实化实施发现
- `common.notification` 已从只有基础表结构推进到可用契约：新增 `content`、`level`、`source_ref_type`、`source_ref_id`，用 `tenant_id + receiver_id + source_ref_type + source_ref_id` 保证同一任务失败不会因轮询重复生成通知。
- 通知中心真实化的第一刀只接失败任务：任务条负责运行态和近期终态，通知中心负责需要用户感知/处理的失败结果；成功任务仍停留在任务条近期窗口，避免未读红点成为噪音。
- `NotificationService.notifyTaskIfNeeded` 会优先使用任务自己的 `userId`，没有任务用户时退回当前 `TenantContext.userId`，因此采集这类租户级任务会给当前查看者生成个人通知。
- 新 API 为 `GET /api/v1/notifications`、`POST /api/v1/notifications/{id}/read`、`POST /api/v1/notifications/read-all`，均按当前租户和当前用户过滤，避免跨用户读取通知。
- 前端通知中心保持原抽屉、分类和视觉样式，只把数据源从 mock 切到 `NotificationAPI`；顶部铃铛未读数来自 `useNotifications` 的真实轮询结果。
- 浏览器验证中，任务条刷新后生成 `任务失败：采集任务 orders_sync -> ods.orders` 未读通知，顶部铃铛显示 1，抽屉展示 `CRITICAL`、失败标题和 `账号密码过期`，控制台无 error。
- 这仍不是完整通知体系：审批、系统消息、质量告警单独入通知、静默规则、通知渠道和 SSE/WebSocket 推送仍是后续 P3/P4；本轮只完成任务条失败结果到通知中心的最小真实闭环。

## 技术决策
| 决策 | 理由 |
|------|------|
| 以当前代码校准文档 | `RTK.md` 明确要求 docs 和 code 不一致时先信当前代码 |
| Airbyte/Dagster 本轮推进到真实数据面实证 | Airbyte 已通过 abctl 运行，Dagster 本地 compose 可用，Postgres 源表到目标库的真实同步已完成 |
| Airbyte 不再放入 `docker-compose.yml` | 官方本地部署已转向 `abctl`，保留无效 compose 服务会持续制造误导和启动失败 |
| 全局任务条先做统一任务投影而不是前端多源拼接 | OneLake 的长任务跨采集、SQL、编排、质量和数据服务，统一投影能保证状态语义、权限、跳转和后续通知联动一致 |

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

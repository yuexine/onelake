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
- `AirbyteSyncDriver` 当前已有 `ensureConnection(sourceId, destinationId, name)`、`triggerSync`、`getJobStatus`、`cancel`，但 `SyncTaskServiceImpl` 尚未在任务发布/触发流程中使用 `ensureConnection` 和 `cancel`。
- 前端 `SyncTaskWizard` 已把“发布”串为 `createSyncTask -> enableSyncTask -> navigate('/integration/sync-tasks')`，但选表、字段映射、cron、限流仍主要是样例/默认值；“保存草稿”和“试跑”只是消息提示，尚未接真实后端。
- `SourceSchemaSnapshotServiceImpl` 已有快照和漂移检测 API，但 `captureColumns` 明确是离线 stub；`DatabaseDiscoveryClient` 只支持 MySQL/Postgres 库列表探查，尚未提供表/字段 discovery。
- 任务运行闭环已有 `sync_run` 落库、reconcile、成功/失败事件发布和下游消费者雏形；但 Airbyte 状态映射只做 `RunStatus.valueOf(s.toUpperCase())`，无法处理 `pending/cancelled/unknown`，也没有 rows/error/checkpoint/logs 采集。
- 当前闭环状态可概括为：前端创建可达、后端任务可落库、列表详情可读、事件契约已有；但“创建任务自动生成 Airbyte connection -> 触发真实入湖 -> 回写指标/日志 -> 前端诊断/血缘展示”还未闭合。
- 下一轮实现已补齐 MySQL/Postgres 的 schema/table/column discovery：`GET /api/v1/integration/datasources/{id}/schemas`、`/tables?schema=`、`/tables/{objectName}/columns`。
- `SourceSchemaSnapshotServiceImpl#captureColumns` 已改为调用 `DatabaseDiscoveryClient.describeTable`，不再写占位 columns；CDC/file schema 仍未接入。
- `SyncTaskServiceImpl#enable` 已在缺少 `airbyteConnectionId` 时调用 `AirbyteSyncDriver.ensureConnection`，读取数据源 config 中的 `airbyteSourceId`/`externalSourceId` 和 `airbyteDestinationId`/`externalDestinationId`，或配置项 `onelake.dataplane.airbyte.destination-id`。
- `SyncTaskWizard` 已从真实接口加载 schema/table/columns 并自动生成字段映射；“保存草稿”已调用真实 `createSyncTask`，“发布”仍是 `createSyncTask -> enableSyncTask`。
- 为避免误承诺完整数据面，本轮没有做 Airbyte source/destination 动态创建、dry-run、日志、取消、Dagster 调度和运行指标回写。
- `DatabaseDiscoveryClient` 已从集中 `switch` 改为策略模式：门面按 `DataSourceType` 分发，MySQL/Postgres 分别由独立策略承载 SQL 和默认 schema 规则，后续新增数据源只需增加策略实现。
- 阶段 A 创建表单真实化收口后，`SyncTaskWizard` 不再使用 mock 数据源和样例字段映射兜底；真实接口失败会展示错误和重试，并阻止保存/发布，避免创建看似成功但来源结构不真实的任务。
- 当前创建向导收敛为单表任务创建；批量选表/批量建任务与模板生成留到后续阶段，避免一个任务 payload 只含单个 `targetTable` 但 UI 允许多表造成误导。

## 技术决策
| 决策 | 理由 |
|------|------|
| 以当前代码校准文档 | `RTK.md` 明确要求 docs 和 code 不一致时先信当前代码 |

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

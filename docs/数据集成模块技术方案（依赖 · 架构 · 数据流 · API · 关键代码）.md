# 数据集成模块技术方案：当前实现版

> 修订日期：2026-07-09
>
> 旧方案中的 `SyncDriver SPI`、`SyncRunController`、`ConnectorController`、`/api/integration` 前缀属于目标设计或历史口径。当前实现以 `module-integration` 的 Controller/Service/Airbyte client 为事实来源。

## 1. 模块职责

`module-integration` 负责把外部数据源接入 OneLake 控制面：

1. 管理数据源元数据和连通性。
2. 探查 MySQL/Postgres schema/table/columns。
3. 管理采集任务和运行实例。
4. 调用 Airbyte 创建 source/destination/connection 并触发 job。
5. 记录运行、日志、checkpoint、错误和事件。
6. 通过 Outbox 通知 Catalog/Security/Orchestration/DataService。

控制面不搬运业务数据，数据搬运交给 Airbyte 或后续 CDC/file collector。

## 2. 代码结构

```text
module-integration/src/main/java/com/onelake/integration/
  api/
  api/vo/
  client/
  client/discovery/
  domain/entity/
  domain/enums/
  dto/
  mapper/
  repository/
  service/
  service/impl/
  service/validation/
```

## 3. 数据模型

| 表 | 用途 |
| --- | --- |
| `integration.datasource` | 数据源连接、Airbyte resource 元信息、健康状态 |
| `integration.sync_task` | 采集任务定义、字段映射、source/target 表、状态 |
| `integration.sync_run` | 运行实例、外部 job id、状态、行数、checkpoint、错误 |
| `integration.source_schema_snapshot` | 源端 schema 快照和 drift |
| `integration.cdc_task` | CDC 任务控制面 |
| `integration.file_source` | 文件源控制面 |

## 4. Airbyte 集成

`AirbyteSyncDriver` 当前覆盖：

- OAuth client credentials token。
- source/destination 创建或复用。
- connection 创建和 catalog discovery。
- sync 触发。
- job snapshot、attempt 统计、日志、取消。

后端默认 `AIRBYTE_URL=http://localhost:18001/api/v1`，需要 `kubectl port-forward`；Airbyte UI `8000` 不等于后端 API 一定可用。

## 5. API

统一前缀：`/api/v1/integration`

| Controller | 代表能力 |
| --- | --- |
| `DataSourceController` | 数据源 CRUD、测连、probe、Airbyte definitions/spec、schema/table/column discovery |
| `SyncTaskController` | 任务 CRUD、enable/disable、dry-run、trigger、run detail/logs/cancel/reconcile |
| `SourceSchemaSnapshotController` | schema snapshot 和 drift |
| `CdcTaskController` | CDC 任务管理 |
| `FileSourceController` | 文件源管理 |
| `IntegrationMonitorController` | 健康摘要、失败 Top |

## 6. 事件

| 事件 | 当前生产/消费 |
| --- | --- |
| `integration.sync_task.created` | 生产端已实现；Security/DataService 消费 |
| `integration.table.loaded` | 生产端已实现；Catalog/Orchestration 消费 |
| `integration.sync.failed` | 生产端已实现；Catalog/Common alert 消费 |
| `integration.schema.drift` | 生产端已实现；下游治理继续增强 |
| `integration.datasource.*` | 生产端存在，消费侧按场景扩展 |

## 7. 后续设计保留项

| 目标 | 当前处理 |
| --- | --- |
| `SyncDriver` SPI | 可作为下一步抽象；当前直接使用 Airbyte driver |
| 多引擎采集 | CDC/file 控制面已存在，真实数据面联调待推进 |
| Connector 表单 schema | Airbyte definition/spec API 已有，前端体验可继续增强 |
| 采集调度 | Dagster schedule reconcile 已有 client/job，默认关闭 |
| 密钥托管 | 需和 `module-security.secret` 深度打通 |

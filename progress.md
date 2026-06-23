# 进度日志

## 会话：2026-06-15

### 阶段 1：上下文定位与范围确认
- **状态：** complete
- **开始时间：** 2026-06-15 16:07:11 CST
- 执行的操作：
  - 读取文件规划技能说明。
  - 读取项目入口 `RTK.md`。
  - 创建 `task_plan.md`、`findings.md`、`progress.md`。
  - 定位数据集成技术方案、数据面开发指南、后端模块、前端页面和 mock 数据。
- 创建/修改的文件：
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 2：后端现状调研
- **状态：** complete
- 执行的操作：
  - 阅读 `module-integration` Controller、Service、Client、Entity、Repository、Mapper、DTO、VO。
  - 阅读 `integration/V1__integration.sql`。
  - 确认现状为基础 CRUD/测连/触发/reconcile 骨架，尚缺任务生命周期、Airbyte 动态 connection、schema drift 和运行日志等闭环。

### 阶段 3：数据面与技术方案调研
- **状态：** complete
- 执行的操作：
  - 阅读数据集成模块技术方案和数据面开发指南。
  - 对照 `docker-compose.yml`、Trino Iceberg 配置和 APISIX 配置。
  - 确认实施边界为控制面驱动 Airbyte/Dagster，不在 Java 后端搬运业务数据。

### 阶段 4：前端契约与页面流程调研
- **状态：** complete
- 执行的操作：
  - 阅读 `web-console/src/api/index.ts`、`types/index.ts`、`mock/l1-integration.ts`。
  - 阅读数据源、采集任务、向导、详情、失败诊断、CDC、schema change、采集监控、文件采集、模板页面。
  - 梳理出后端 DTO 与前端展示字段差距。

### 阶段 5：制定计划与可行性评估
- **状态：** complete
- 执行的操作：
  - 新增 `docs/数据集成模块后端迭代开发计划.md`。
  - 输出分阶段计划、API 优先级、数据模型建议、验证策略和风险评估。
- 创建/修改的文件：
  - `docs/数据集成模块后端迭代开发计划.md`

### 阶段 6：第一轮迭代实现
- **状态：** complete
- 执行的操作：
  - 用户确认开启第一轮迭代，并要求前端不改样式、仅做接口集成。
  - 读取规划文件和当前 git 状态，确认避开既有 `App.tsx`、`OneLakeLogo.tsx` 修改。
  - 后端补齐数据源/任务/run DTO 字段、数据源筛选、任务列表/更新/删除/启用/停用/trigger 接口。
  - 新增 `integration` Flyway V2 迁移，增加任务名唯一约束和查询索引。
  - 前端 `IntegrationAPI` 增加解包类型和数据集成接口方法。
  - `DatasourceList`、`DatasourceDetail`、`SyncTaskList`、`SyncTaskDetail`、`SyncTaskWizard` 接入真实接口；未修改前端样式。
  - 使用浏览器打开 `/integration/datasources`、`/integration/sync-tasks`、`/integration/sync-tasks/new` 做冒烟。
- 创建/修改的文件：
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/DataSourceDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/SyncTaskDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/SyncRunDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/api/SyncTaskController.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/api/DataSourceController.java`
  - `onelake-app/bootstrap/src/main/resources/db/migration/integration/V2__integration_first_iteration.sql`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/integration/DatasourceList.tsx`
  - `onelake-app/web-console/src/pages/integration/DatasourceDetail.tsx`
  - `onelake-app/web-console/src/pages/integration/SyncTaskList.tsx`
  - `onelake-app/web-console/src/pages/integration/SyncTaskDetail.tsx`
  - `onelake-app/web-console/src/pages/integration/SyncTaskWizard.tsx`

### 阶段 7：采集任务创建闭环现状检查与迭代计划
- **状态：** complete
- **开始时间：** 2026-06-15 23:44 CST
- 执行的操作：
  - 读取 `RTK.md`、既有规划文件、数据集成后端计划和采集任务前后端代码。
  - 检查 `SyncTaskController`、`SyncTaskServiceImpl`、`AirbyteSyncDriver`、`SourceSchemaSnapshotServiceImpl`、前端 `SyncTaskWizard`、`SyncTaskList`、`SyncTaskDetail` 与 `IntegrationAPI`。
  - 运行 `mvn -q -pl module-integration -am test` 与 `pnpm build`。
  - 新增聚焦文档，规划采集任务创建到运行回写的闭环迭代路线。
- 创建/修改的文件：
  - `task_plan.md`
  - `findings.md`
  - `progress.md`
  - `docs/采集任务创建流程闭环迭代实施计划.md`

### 阶段 8：采集任务创建闭环下一轮实现
- **状态：** complete
- **开始时间：** 2026-06-15 23:52 CST
- 执行的操作：
  - 新增 `DiscoveredColumnDTO`。
  - 扩展 `DatabaseDiscoveryClient`，支持 MySQL/Postgres schema/table/column discovery。
  - 扩展 `DataSourceController` 和 `DataSourceService`，暴露 schemas/tables/columns 接口。
  - `SourceSchemaSnapshotServiceImpl` 改为使用真实 discovery columns。
  - `SyncTaskServiceImpl#enable` 补齐 Airbyte `ensureConnection`，并收紧任务创建时的数据源租户归属校验。
  - `SyncTaskWizard` 接入真实 schema/table/columns，保存草稿和发布都走真实接口。
  - 修复已有 `FileCollect.tsx` 构建问题，改用 `IntegrationAPI.listFileSourceFiles`。
  - 更新 `docs/IMPLEMENTATION_STATUS.md` 和闭环计划文档。
  - 使用内置浏览器打开 `/integration/sync-tasks/new` 做冒烟，因 SSO 跳转到 Keycloak 登录页，未能查看向导内部布局；浏览器控制台无错误。
- 创建/修改的文件：
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/DiscoveredColumnDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/api/DataSourceController.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/discovery/DatabaseDiscoveryClient.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/DataSourceService.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/DataSourceServiceImpl.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/SourceSchemaSnapshotServiceImpl.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/SyncTaskServiceImpl.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/integration/SyncTaskWizard.tsx`
  - `onelake-app/web-console/src/pages/integration/FileCollect.tsx`
  - `docs/IMPLEMENTATION_STATUS.md`
  - `docs/采集任务创建流程闭环迭代实施计划.md`

### 阶段 9：数据源探查策略化重构
- **状态：** complete
- **开始时间：** 2026-06-16 CST
- 执行的操作：
  - 新增 `DataSourceDiscoveryStrategy`，统一库/schema/table/column 探查能力契约。
  - 新增 `AbstractJdbcDiscoveryStrategy`，沉淀 JDBC 连接、查询、字段映射和表名解析通用逻辑。
  - 新增 `MySqlDiscoveryStrategy` 与 `PostgresDiscoveryStrategy`，把数据源差异从 `DatabaseDiscoveryClient` 的集中分支中拆出。
  - `DatabaseDiscoveryClient` 改为策略分发门面，保持原有 public API 与未支持类型的业务异常提示。
  - 补充 `DatabaseDiscoveryClientTest`，覆盖策略分发和未支持类型错误语义。
- 创建/修改的文件：
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/discovery/DatabaseDiscoveryClient.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/discovery/DataSourceDiscoveryStrategy.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/discovery/AbstractJdbcDiscoveryStrategy.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/discovery/MySqlDiscoveryStrategy.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/discovery/PostgresDiscoveryStrategy.java`
  - `onelake-app/module-integration/src/test/java/com/onelake/integration/client/discovery/DatabaseDiscoveryClientTest.java`

### 阶段 10：阶段 A 创建表单真实化收口
- **状态：** complete
- **开始时间：** 2026-06-16 CST
- 执行的操作：
  - `SyncTaskWizard` 移除 `mockDataSources` 和 `sampleMapping` 初始化/兜底。
  - 数据源列表、schema、table、columns 均改为真实接口状态驱动，失败展示错误和重试。
  - 字段探查失败时不再生成样例映射；保存草稿和发布要求已生成真实字段映射。
  - 来源表选择从多选收敛为单选，匹配当前 `CreateSyncTaskVO` 的单任务 payload。
  - 浏览器打开 `/integration/sync-tasks/new` 时被 Keycloak 登录页接管，未进入向导内部；控制台无 error。
- 创建/修改的文件：
  - `onelake-app/web-console/src/pages/integration/SyncTaskWizard.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`
  - `docs/FRONTEND_VERIFICATION.md`

### 阶段 11：创建表单错误提示视觉优化
- **状态：** complete
- **开始时间：** 2026-06-16 CST
- 执行的操作：
  - 基于用户截图确认默认 AntD warning Alert 面积过大、黄色过重，与向导表单层级不匹配。
  - 按用户反馈撤销页面内错误框方案，Schema/Table/DataSource 探查错误统一使用全局 `message` 提示。
  - 表单区域不再渲染 `DiscoveryNotice` 或错误框；失败后的流程阻断由按钮禁用和空状态承担。
  - `Request failed with status code 500` 和 timeout 文案转为面向用户的恢复建议。
  - 浏览器打开 `/integration/sync-tasks/new` 可进入向导页面，页面内未出现 `Schema 探查失败` 错误框，控制台无 error。
- 创建/修改的文件：
  - `onelake-app/web-console/src/pages/integration/SyncTaskWizard.tsx`
  - `task_plan.md`
  - `progress.md`

### 阶段 12：Schema 探查接口调用错误排查与修复
- **状态：** complete
- **开始时间：** 2026-06-16 07:00 CST
- 执行的操作：
  - 沿真实请求链路检查 Vite 代理目标、8080 后端进程与 `.run-logs/backend.log`。
  - 定位到前端 500 的直接原因：运行中的后端没有加载 `/api/v1/integration/datasources/{id}/schemas` 映射，日志报 `No static resource .../schemas`。
  - 执行 `mvn -q install -DskipTests -Djacoco.skip=true` 刷新本地 Maven SNAPSHOT，并停止旧后端进程。
  - 修复刷新模块后暴露的启动问题：质量模块 `AlertRepository` 与通用告警仓储 Bean 名冲突、两个 `Alert` Entity 默认实体名冲突。
  - 重新以 `screen` 启动 `onelake-backend`，后端健康检查恢复。
  - 验证 OpenAPI 已包含 `/api/v1/integration/datasources/{id}/schemas`；经 Vite 代理访问该接口返回 `401 Bearer`，不再是缺路由导致的 500。
  - 浏览器访问 `/integration/sync-tasks/new` 被 Keycloak 登录页接管，控制台无 error；需要有效登录态后才能继续验证向导内真实业务请求。
- 创建/修改的文件：
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/repository/QualityAlertRepository.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/service/QualityService.java`
  - `onelake-app/module-common/src/main/java/com/onelake/common/alert/Alert.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/domain/entity/Alert.java`
  - `task_plan.md`
  - `progress.md`

### 阶段 13：发布按钮 500 与试跑功能状态检查
- **状态：** complete
- **开始时间：** 2026-06-16 10:08 CST
- 执行的操作：
  - 检查 `SyncTaskWizard` 发布按钮链路，确认前端调用顺序为 `createSyncTask` 后接 `enableSyncTask`。
  - 从 `.run-logs/backend.log` 定位发布按钮 500 直接根因：`integration.sync_task.field_mapping` 是 `jsonb`，但 `SyncTask.fieldMapping` 以普通 varchar 写入。
  - 为 `SyncTask.fieldMapping` 增加 PostgreSQL `jsonb` cast；同模块 `SourceSchemaSnapshot.columns`、`SyncRun.checkpoint` 同步补齐相同映射，避免后续链路同类报错。
  - 检查 `common.outbox_event` 实际表结构，发现缺少 `tenant_id`、`aggregate_type`、`retry_count`、`published_at`，导致 Outbox 定时任务报 `aggregate_type` 缺列。
  - 由于 `make migrate` 当前受 `PG_HOST` 解析和多目录重复 `V1` 版本影响不可用，本轮手工执行 `common/V4__outbox_stream_contract.sql` 补齐本地表结构。
  - 修复 Redis Stream 领域事件消费线程缺少 `TenantContext` 的问题，保证异步处理器调用 `AuditLogger` 时可写入 `tenant_id`。
  - 补齐本地 `security.pii_scan_record` 表，并修复 `security/V3__security_seed.sql` 中非法 UUID 种子数据。
  - 重启后端，使用 Keycloak 本地开发用户获取 JWT，直接验证创建任务接口返回 200。
  - 发布第二段 `enable` 现在返回业务错误 `40032 数据源未配置 airbyteSourceId，无法发布采集任务`，不再是 500；本地 `local-test` 数据源没有 Airbyte source id，不能完成真实启用。
  - 重新触发临时采集任务创建事件，`SyncTaskCreatedEventHandler` 可完成 PII 扫描，审计日志 `tenant_id` 有值。
  - 检查试跑功能状态：后端已有 `POST /api/v1/integration/sync-tasks/{id}/run` 与 `/trigger`，服务层已有 `trigger` 创建 `sync_run`；前端 API 有 `triggerSyncTask`；但新建向导的“试跑”按钮仍是静态 `message.warning`，尚未接入真实 API。
- 创建/修改的文件：
  - `onelake-app/module-common/src/main/java/com/onelake/common/outbox/RedisStreamDomainEventConsumer.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/domain/entity/SyncTask.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/domain/entity/SourceSchemaSnapshot.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/domain/entity/SyncRun.java`
  - `onelake-app/bootstrap/src/main/resources/db/migration/security/V3__security_seed.sql`
  - `task_plan.md`
  - `progress.md`

### 阶段 14：发布错误提示复测与前端错误解包
- **状态：** complete
- **开始时间：** 2026-06-16 10:37 CST
- 执行的操作：
  - 使用内置浏览器登录 Keycloak 本地开发账号 `dev`，进入 `/integration/sync-tasks/new`。
  - 完成新建采集任务向导到第 4 步，点击发布按钮。
  - 复现前端 toast 显示 `Request failed with status code 400`，但后端日志实际是 `BizException: 数据源未配置 airbyteSourceId，无法发布采集任务`。
  - 修复 `web-console/src/api/http.ts`，在 axios 非 2xx 响应里提取 `error.response.data.message`，统一抛出后端业务文案。
  - 重新构建前端并刷新浏览器，复测发布按钮 toast 已显示 `数据源未配置 airbyteSourceId，无法发布采集任务`。
  - 清理复测过程中创建的 `ods_customers_100k_incremental` 草稿任务，避免影响后续测试。
- 创建/修改的文件：
  - `onelake-app/web-console/src/api/http.ts`
  - `task_plan.md`
  - `progress.md`

### 阶段 15：数据面执行闭环第一批实现
- **状态：** complete
- **开始时间：** 2026-06-16 CST
- 执行的操作：
  - 读取当前计划、发现、进度、Airbyte 驱动、采集任务服务、Controller、前端向导和详情页。
  - 新增 `SyncTaskDryRunDTO`、`SyncRunLogDTO`，扩展 `RunStatus.CANCELLED`。
  - `AirbyteSyncDriver` 增加 source/destination 动态创建、connection 检查、job 快照解析和日志提取。
  - `SyncTaskServiceImpl` 增加 dry-run、run 详情、run logs、run cancel；发布时可动态准备 Airbyte source/destination/connection；触发失败会落失败 run；reconcile 回写 rows/error/checkpoint。
  - `SyncTaskController` 暴露 `/dry-run`、`/{id}/dry-run`、`/runs/{runId}`、`/logs`、`/cancel`。
  - 前端 `IntegrationAPI`、`SyncTaskWizard`、`SyncTaskDetail`、`FailureDiagnose` 接入试跑、真实 run、日志和取消。
  - 运行 `mvn -q -pl module-integration -am test` 和 `pnpm build`。
- 创建/修改的文件：
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/AirbyteSyncDriver.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/SyncTaskServiceImpl.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/api/SyncTaskController.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/SyncTaskDryRunDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/SyncRunLogDTO.java`
  - `onelake-app/bootstrap/src/main/resources/application.yml`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/integration/SyncTaskWizard.tsx`
  - `onelake-app/web-console/src/pages/integration/SyncTaskDetail.tsx`
  - `onelake-app/web-console/src/pages/integration/FailureDiagnose.tsx`

### 阶段 16：调度与 Connector 配置闭环推进
- **状态：** complete
- **开始时间：** 2026-06-16 CST
- 执行的操作：
  - 新增 `AirbyteConnectorDefinitionDTO`、`AirbyteConnectorSpecDTO`。
  - `AirbyteSyncDriver` 增加 source/destination connector definition 列表和 spec 查询。
  - `DataSourceController` 暴露 `/airbyte/source-definitions`、`/airbyte/destination-definitions` 及对应 spec 接口。
  - `DatasourceList` 新建抽屉增加 Airbyte 数据面配置区，可加载 source definition 并保存 workspace/source/destination 元信息。
  - 新增 `DagsterScheduleClient`，任务启用/暂停时在 `DAGSTER_SCHEDULE_ENABLED=true` 后向 Dagster reconciliation job 传递 `UPSERT/DISABLE` 意图；默认关闭且不阻断发布。
  - 尝试启动本地 Airbyte/Dagster 数据面，发现 compose 使用的 `airbyte/airbyte:latest` 与 `dagster/dagster:latest` 均拉取失败。
  - 已启动并验证基础依赖 `postgres`、`redis`、`keycloak`、`minio`；未启动 Trino，避免占用后端 `8080`。
- 创建/修改的文件：
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/AirbyteSyncDriver.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/DagsterScheduleClient.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/AirbyteConnectorDefinitionDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/AirbyteConnectorSpecDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/api/DataSourceController.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/DataSourceService.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/DataSourceServiceImpl.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/SyncTaskServiceImpl.java`
  - `onelake-app/bootstrap/src/main/resources/application.yml`

## 会话：2026-06-20

### 阶段 20：Integration → Catalog 联动进展检查与下一步计划
- **状态：** in_progress
- 执行的操作：
  - 读取既有 `task_plan.md`、`findings.md`、`progress.md`，延续数据集成闭环规划上下文。
  - 检查 `module-catalog` 的 Controller、Service、Entity、Repository、OpenMetadata 同步服务与事件 handler。
  - 检查前端 `CatalogAPI`、`CatalogSearch`、`AssetDetail`、`LineageGraph` 的真实 API 与 mock 使用状态。
  - 查询本地 Postgres：`integration.table.loaded` 事件已发布并被 `catalog` consumer 消费，但 `catalog.asset` 为 0 条。
  - 确认当前缺口是 Catalog handler 只刷新已存在资产，不会在采集成功后自动 upsert 新资产。
- 创建/修改的文件：
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 21：Integration → Catalog 最小可见闭环实现
- **状态：** complete
- 执行的操作：
  - 新增 `AssetDTO`，Catalog API 改为返回前端可用 DTO，不再直接返回 JPA Entity。
  - 改造 `SyncRunEventHandler`，消费 `integration.table.loaded` 时自动 upsert `catalog.asset`；失败事件仍不改资产新鲜度。
  - 为 `catalog.asset.tags` 增加 jsonb 写入 cast，避免字符串写入 jsonb 报错。
  - `CatalogSearch` 从 mock 资产数组切换到 `CatalogAPI.listAssets()`；未调整页面样式和布局。
  - 新增 `SyncRunEventHandlerTest` 覆盖首次建档、重复刷新、失败不建档。
  - 运行 `mvn -q -pl module-catalog -am test`、`pnpm --dir onelake-app/web-console exec tsc --noEmit`、`pnpm --dir onelake-app/web-console build`。
  - 浏览器打开 `/catalog/search`，登录后页面进入真实 API 数据状态；当前本地 `catalog.asset` 为空，因此显示空态。
- 遇到的错误：
  - `mvn -q -pl module-catalog -am test -Dtest=SyncRunEventHandlerTest` 首次因 `-am` 上游 `module-common` 无同名测试失败；改用 `-Dsurefire.failIfNoSpecifiedTests=false` 后通过。
- 创建/修改的文件：
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/dto/AssetDTO.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/api/CatalogController.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/domain/entity/Asset.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/event/SyncRunEventHandler.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/CatalogService.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/event/SyncRunEventHandlerTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/catalog/CatalogSearch.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/integration/DatasourceList.tsx`

### 阶段 17：数据面阻断项修正
- **状态：** complete
- **开始时间：** 2026-06-17 CST
- 执行的操作：
  - 查阅 Airbyte 官方文档，确认本地 Airbyte 应通过 `abctl local install --port 8000` 部署，Docker Compose 已不再是支持路径。
  - 查阅 Dagster 官方 Docker Compose 部署文档，确认 Dagster 需要 webserver、daemon、code location 和 Postgres 多容器部署，不是单个 `dagster/dagster` 镜像。
  - 修改 `docker-compose.yml`：删除无效 Airbyte 单镜像服务；新增 `dagster-user-code`、`dagster-webserver`、`dagster-daemon` 和 Dagster Postgres healthcheck。
  - 新增 `onelake-app/dagster/` 最小 Dagster repo，包含后端默认调用的 `onelake_sync_task_schedule_reconcile` job。
  - 新增 `scripts/airbyte-local.sh`，通过 `abctl` 管理 Airbyte install/status/credentials/uninstall。
  - 更新 `Makefile`，新增 `up-core`、`dagster-up`、`airbyte-up` 等数据面入口。
  - 更新 `RTK.md`，明确 Airbyte 不再由 Compose 管理。
  - 使用 Homebrew 安装 `abctl v0.30.4`，并信任 Airbyte 官方 tap。
  - 执行 `make dagster-up`，本地构建并启动 Dagster webserver、daemon、code location 和 Postgres；GraphQL 可查询到 `onelake` repository 与 `onelake-loc` location。
  - 通过 GraphQL 提交 `onelake_sync_task_schedule_reconcile` smoke run，run 从 `QUEUED` 被 daemon 接走并最终 `SUCCESS`。
  - 执行 `make airbyte-up`，`abctl` 成功创建 kind 集群，但下载 Airbyte Helm chart index 时失败：`https://airbytehq.github.io/charts/index.yaml` TLS 连接 `SSL_ERROR_SYSCALL` / `EOF`。
  - 已执行 `make airbyte-down` 清理失败后的 kind 集群，并为 `scripts/airbyte-local.sh` 增加 chart index 预检，避免后续在网络不通时先创建半成品集群。
  - 2026-06-17 追加复核时网络已恢复，Airbyte 已通过 `abctl` 完成部署；`make airbyte-status` 显示 Helm release 已 deployed，`http://localhost:8000` 返回 200。
- 创建/修改的文件：
  - `RTK.md`
  - `onelake-app/docker-compose.yml`
  - `onelake-app/Makefile`
  - `onelake-app/dagster/Dockerfile_dagster`
  - `onelake-app/dagster/Dockerfile_user_code`
  - `onelake-app/dagster/dagster.yaml`
  - `onelake-app/dagster/workspace.yaml`
  - `onelake-app/dagster/definitions.py`
  - `onelake-app/scripts/airbyte-local.sh`

### 阶段 18：数据集成全链路实施现状复核
- **状态：** complete
- **开始时间：** 2026-06-17 CST
- 执行的操作：
  - 复核 `SyncTaskController`、`SyncTaskServiceImpl`、`AirbyteSyncDriver`、`DagsterScheduleClient`、`DataSourceController` 和 `DataSourceServiceImpl`。
  - 确认采集任务控制面已覆盖 create/list/get/update/delete/enable/disable/dry-run/trigger/reconcile/run detail/logs/cancel。
  - 复核前端 `IntegrationAPI`、`SyncTaskWizard`、`SyncTaskDetail`、`DatasourceList` 调用点，确认创建、发布、试跑、触发、日志、取消和 Airbyte 配置区已接真实接口。
  - 执行 `make airbyte-status`、`kubectl get pods -n airbyte-abctl`、`curl -I http://localhost:8000/`，确认 Airbyte 本地入口可访问。
  - 执行 `docker compose ps dagster-*`、`curl http://localhost:3000/server_info`，确认 Dagster webserver/daemon/code-location 可运行。
  - 检查 `8080`/`5173`/`3000` 监听状态，确认当前只有 Dagster 3000 监听，后端和前端未启动。
  - 运行 `mvn -q -pl module-integration -am test`。
- 创建/修改的文件：
  - `task_plan.md`
  - `findings.md`
  - `progress.md`
  - `docs/采集任务创建流程闭环迭代实施计划.md`
  - `docs/IMPLEMENTATION_STATUS.md`

### 阶段 19：真实端到端联调闭环收口
- **状态：** complete
- **开始时间：** 2026-06-17 CST
- 执行的操作：
  - 为本地源库创建 `onelake_src.public.codex_orders` 测试表和 3 行数据，为目标库准备 `onelake_lake.ods_airbyte` schema。
  - 接入 Airbyte 2.1 OAuth client credentials，后端运行时通过 `AIRBYTE_CLIENT_ID`/`AIRBYTE_CLIENT_SECRET` 获取 Bearer token。
  - 修复 Airbyte workspace-scoped API：definition spec 与 connection list 请求带 `workspaceId`。
  - 新增 `sync_task.source_table`，并贯穿后端 VO/entity/DTO/mapper/service、Flyway V5、前端任务类型和创建向导 payload。
  - 修复发布阶段 Airbyte connection 创建：优先使用 `/sources/discover_schema` 返回的 catalog，并按 `targetTable` 设置目标 namespace 与 alias。
  - 修复 Airbyte 2.x nested attempt 统计解析，reconcile 可回写 `rowsRead/rowsWritten`。
  - 重启后端并执行真实 API 链路：创建数据源 -> 探查 columns -> dry-run -> 创建任务 -> enable -> trigger -> reconcile -> 目标库查数。
- 端到端验证证据：
  - 任务 `5bad1992-f737-4e51-a794-60962d041eed` 发布后绑定 Airbyte connection `de595738-f95a-49fd-a3fc-c38fc181f6f8`。
  - run `b582f99c-f602-4dac-b2b1-72abd7e9c3a7` 触发 Airbyte job `2`，最终 `SUCCEEDED`。
  - `onelake_lake.ods_airbyte.codex_orders` 查询到 3 行：Alice、Bob、Carol。
  - 重新 reconcile 后 OneLake run 返回 `rowsRead=3`、`rowsWritten=3`。
- 创建/修改的文件：
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/client/AirbyteSyncDriver.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/api/vo/CreateSyncTaskVO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/api/vo/UpdateSyncTaskVO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/domain/entity/SyncTask.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/dto/SyncTaskDTO.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/mapper/SyncTaskMapper.java`
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/SyncTaskServiceImpl.java`
  - `onelake-app/bootstrap/src/main/resources/application.yml`
  - `onelake-app/bootstrap/src/main/resources/db/migration/integration/V5__sync_task_source_table.sql`
  - `onelake-app/web-console/src/pages/integration/SyncTaskWizard.tsx`
  - `onelake-app/web-console/src/types/index.ts`
  - `onelake-app/module-integration/src/test/java/com/onelake/integration/client/AirbyteSyncDriverTest.java`

## 测试结果
| 测试 | 输入 | 预期结果 | 实际结果 | 状态 |
|------|------|---------|---------|------|
| module-integration 测试 | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 无错误输出，退出码 0 | 通过 |
| module-integration 测试（第一轮后端改动后） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 无错误输出，退出码 0 | 通过 |
| 全工程跳测编译 | `mvn -q install -DskipTests` | 全模块编译通过 | 无错误输出，退出码 0 | 通过 |
| 前端构建 | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仅有 chunk size 警告 | 通过 |
| diff 空白检查 | `git diff --check` | 无尾随空白/补丁格式问题 | 无输出，退出码 0 | 通过 |
| 浏览器冒烟 | `http://127.0.0.1:5174/integration/...` | 关键路由可渲染 | 数据源、任务列表、任务向导均可打开；API 代理因后端未启动 ECONNREFUSED | 部分通过 |
| module-integration 测试（闭环现状检查） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；仅有预期校验/容错测试日志 | 通过 |
| 前端构建（闭环现状检查） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| module-integration 测试（下一轮实现） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；仅有预期校验/容错测试日志 | 通过 |
| 前端构建（下一轮实现） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| 浏览器冒烟（下一轮实现） | `http://localhost:5173/integration/sync-tasks/new` | 采集任务向导可渲染 | 被 SSO 重定向到 Keycloak 登录页；控制台无错误 | 受阻 |
| module-integration 测试（数据源探查策略化重构） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；仅有预期校验/容错测试日志 | 通过 |
| 前端构建（阶段 A 创建表单真实化） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| module-integration 测试（阶段 A 创建表单真实化） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；仅有预期校验/容错测试日志 | 通过 |
| 浏览器冒烟（阶段 A 创建表单真实化） | `http://localhost:5173/integration/sync-tasks/new` | 可进入采集任务向导 | 被 Keycloak 登录页接管；Vite 页面无 error，仅 React Router future flag warning | 受阻 |
| 前端构建（创建表单错误提示视觉优化） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| diff 空白检查（创建表单错误提示视觉优化） | `git diff --check` | 无尾随空白/补丁格式问题 | 无输出，退出码 0 | 通过 |
| 浏览器冒烟（创建表单错误提示视觉优化） | `http://localhost:5173/integration/sync-tasks/new` | 向导可渲染且不出现页面内错误框 | 页面进入新建采集任务向导；无 `Schema 探查失败` 内联错误框；控制台无 error | 通过 |
| module-integration 测试（阶段 16） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；仅有预期校验/容错测试日志 | 通过 |
| 全工程跳测编译（阶段 16） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译通过 | 无错误输出，退出码 0 | 通过 |
| 前端构建（阶段 16） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| diff 空白检查（阶段 16） | `git diff --check` | 无尾随空白/补丁格式问题 | 无输出，退出码 0 | 通过 |
| 数据面基础依赖启动 | `docker compose up -d postgres redis keycloak minio` | 基础依赖可运行 | Postgres/Redis/MinIO healthy；Keycloak running | 通过 |
| Airbyte/Dagster 数据面启动 | `docker compose up -d airbyte dagster` | Airbyte/Dagster 可运行 | 镜像 `airbyte/airbyte:latest`、`dagster/dagster:latest` 拉取失败 | 阻塞 |
| Dagster 数据面启动（阶段 17） | `make dagster-up` | Dagster webserver/daemon/code-location 可运行 | 四个 Dagster 容器运行；`/server_info` 返回 1.13.9 | 通过 |
| Dagster GraphQL repository 验证（阶段 17） | `curl -X POST http://localhost:3000/graphql ...` | 可看到后端默认 repo/location | 返回 `onelake` / `onelake-loc` | 通过 |
| Dagster reconciliation job 触发（阶段 17） | `launchRun(onelake_sync_task_schedule_reconcile)` | run 可被 daemon 执行 | runId `0f606866-e7f7-4d19-aae4-32a83c142635` 最终 `SUCCESS` | 通过 |
| abctl 安装（阶段 17） | `brew tap airbytehq/tap && brew trust airbytehq/tap && brew install abctl` | abctl 可用 | `abctl version` 返回 `v0.30.4` | 通过 |
| Airbyte 数据面入口（阶段 17） | `AIRBYTE_LOW_RESOURCE_MODE=true make airbyte-up` | Airbyte 安装或给出明确阻塞 | chart index `https://airbytehq.github.io/charts/index.yaml` TLS 连接失败；脚本预检阻止创建半成品集群 | 阻塞 |
| Airbyte 半成品清理（阶段 17） | `make airbyte-down` + 端口/容器检查 | 无残留 Airbyte 容器或 8000 监听 | `docker ps --filter name=airbyte` 为空，`lsof :8000` 无监听 | 通过 |
| Airbyte 数据面复核（阶段 18） | `make airbyte-status` + `kubectl get pods -n airbyte-abctl` + `curl -I http://localhost:8000/` | Airbyte 本地入口可访问 | Helm release `airbyte-abctl` 2.1.0 / `ingress-nginx` 4.15.1 deployed；核心 pod Ready；HTTP 200 | 通过 |
| Dagster 数据面复核（阶段 18） | `docker compose ps dagster-*` + `curl http://localhost:3000/server_info` | Dagster webserver/daemon/code-location 可运行 | 4 个 Dagster 容器 Up；server_info 返回 `1.13.9` | 通过 |
| 控制面/前端端口复核（阶段 18） | `lsof -nP -iTCP:8080 -sTCP:LISTEN` / `lsof -nP -iTCP:5173 -sTCP:LISTEN` | 确认是否具备浏览器全链路验证条件 | 8080/5173 当前无监听；3000 为 Dagster | 待启动 |
| module-integration 测试（阶段 18） | `mvn -q -pl module-integration -am test` | 后端集成模块测试通过 | 退出码 0；仅预期校验/容错日志 | 通过 |
| 全工程跳测编译（Schema 探查接口修复） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 无错误输出，退出码 0 | 通过 |
| module-quality 测试（告警命名冲突修复） | `mvn -q -pl module-quality -am test` | common/quality 依赖链编译和测试通过 | 退出码 0；仅 JVM CDS warning | 通过 |
| 后端健康检查（Schema 探查接口修复） | `curl -sf http://localhost:8080/actuator/health` | 后端可用 | 返回 `status: UP`，db/redis 均 UP | 通过 |
| OpenAPI mapping 验证（Schema 探查接口修复） | `curl -s http://localhost:8080/v3/api-docs` | 包含 schemas 探查路径 | 路径列表包含 `/api/v1/integration/datasources/{id}/schemas` | 通过 |
| 前端代理路径验证（Schema 探查接口修复） | `curl -i http://localhost:5173/api/v1/integration/datasources/.../schemas` | 不再返回缺路由 500 | 返回 `401 Unauthorized` / `WWW-Authenticate: Bearer`，说明已进入安全链路 | 通过 |
| module-integration 测试（发布 500 修复） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；仅预期校验/容错日志 | 通过 |
| module-common 测试（事件消费 TenantContext 修复） | `mvn -q -pl module-common -am test` | 编译和测试通过 | 退出码 0；仅 JVM CDS warning | 通过 |
| Outbox 本地表结构修复 | `psql ... -f common/V4__outbox_stream_contract.sql` | 补齐缺失列和索引 | `ALTER TABLE` / `CREATE INDEX` / `CREATE TABLE` 成功 | 通过 |
| 安全 PII 表结构修复 | `psql ... -f security/V2__pii_scan_record.sql -f security/V3__security_seed.sql` | 表和种子可应用 | V2 成功；V3 修复非法 UUID 后成功插入 4 条 | 通过 |
| 后端健康检查（发布 500 修复后） | `curl -sf http://localhost:8080/actuator/health` | 后端可用 | 返回 `status: UP`，db/redis 均 UP | 通过 |
| 创建任务接口验证（发布 500 修复） | `POST /api/v1/integration/sync-tasks` | `field_mapping` 可写入 jsonb，不再 500 | 返回 200；响应包含 DRAFT 任务和字段映射 | 通过 |
| 发布 enable 阶段验证 | `POST /api/v1/integration/sync-tasks/{id}/enable` | 不再受创建阶段 SQL 500 阻断 | 返回 40032：本地数据源缺少 `airbyteSourceId` | 部分通过 |
| 异步创建事件消费验证 | 创建临时采集任务后等待 Redis Stream 消费 | 事件处理器可完成 PII 扫描并写入带租户审计 | 日志显示 `SyncTaskCreatedEventHandler` 完成，审计 `tenant_id` 有值 | 通过 |
| 前端构建（发布错误提示修复） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| 浏览器复测（发布错误提示修复） | `/integration/sync-tasks/new` 点击发布 | toast 显示后端业务错误 | 显示 `数据源未配置 airbyteSourceId，无法发布采集任务`，不再显示 `Request failed with status code 400` | 通过 |
| 测试数据清理 | 删除 `ods_customers_100k_incremental` 草稿任务 | 清理测试副作用 | 数据库查询无剩余 sync_task | 通过 |
| module-integration 测试（数据面执行闭环第一批） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；仅有预期校验/容错日志 | 通过 |
| 前端构建（数据面执行闭环第一批） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| module-integration 测试（真实端到端收口） | `mvn -q -pl module-integration -am test` | 编译和测试通过 | 退出码 0；新增 Airbyte OAuth/workspace/catalog/stats 单测通过 | 通过 |
| 前端构建（真实端到端收口） | `pnpm --dir web-console build` | TypeScript 与 Vite 构建通过 | 构建成功；仍有大 chunk 警告 | 通过 |
| diff 空白检查（真实端到端收口） | `git diff --check` | 无尾随空白/补丁格式问题 | 无输出，退出码 0 | 通过 |
| 后端健康检查（真实端到端收口） | `curl -sS http://localhost:8080/actuator/health` | 后端可用 | 返回 `status: UP`，db/redis 均 UP | 通过 |
| 数据集成真实 E2E | 创建数据源 -> 探查 -> dry-run -> 创建任务 -> enable -> trigger -> reconcile | 源表 3 行同步到目标库，run 终态成功且行数回写 | Airbyte job `2` 成功；目标 `ods_airbyte.codex_orders` 3 行；run `rowsRead=3`、`rowsWritten=3` | 通过 |
| Integration -> Catalog 最小闭环 E2E | MySQL 源表 `ods.ods_customers_100k` -> Airbyte job 6 -> Catalog | run 成功、目标库有数据、Catalog 自动建档 | run `b1dbe486-3ec3-4e01-9623-22d77e76d959` 成功，`rowsRead=9`、`rowsWritten=9`；目标库 9 行；Catalog 资产 `ods.ods_customers_100k` 自动创建并在目录搜索页显示 | 通过 |
| Integration -> Catalog 第二轮 E2E | 同一任务进程级重启后触发 Airbyte job 8 | 事件携带字段映射，Catalog 写入 schema 与血缘 | run `fa739e86-10d0-44c1-9461-b120a74c363c` 成功，`rowsRead=10`、`rowsWritten=10`；事件 payload `fieldMapping=20`；Catalog asset `columns=20`；`lineage_edge.column_level=20` | 通过 |
| Catalog API schema 验证 | `GET /api/v1/catalog/assets/{id}` | 返回资产字段 schema | 返回 `ods.ods_customers_100k`，`columns.length=20`，包含 `id/customer_no/full_name/...` | 通过 |
| Catalog 详情页 Schema 验证 | `/catalog/assets/79188368-ceea-42c9-8235-2f8212646d0e` 点击 `Schema` tab | 页面展示真实字段表 | 页面展示 20 个字段；修复 `DetailPageLayout` 后 tab 可切换 | 通过 |
| 低数据量吞吐展示修复 | `GET /api/v1/integration/sync-tasks/runs/fa739e86-10d0-44c1-9461-b120a74c363c` + 任务详情页 | 10 行 / 约 30 秒不再显示 0/s | API 返回 `throughputRows=0.3248...`；任务详情页显示 `0.32/s` | 通过 |
| module-security 测试（创建任务触发 PII） | `mvn -q -pl module-security -am test` | 事件处理和字段驱动 PII 扫描测试通过 | 退出码 0；新增 handler/service 单测通过 | 通过 |
| module-integration 测试（创建事件 payload） | `mvn -q -pl module-integration -am test` | 创建任务事件 payload 测试通过 | 退出码 0；新增 fieldMapping 断言通过 | 通过 |
| 全工程跳测编译（创建任务触发 PII） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 无错误输出，退出码 0 | 通过 |
| security V4 本地迁移 | `psql < security/V4__pii_scan_record_unique_fqn.sql` | 清理重复记录并创建唯一索引 | `DELETE 10`；`CREATE INDEX` | 通过 |
| 后端健康检查（创建任务触发 PII） | `curl -sf http://localhost:8080/actuator/health` | 后端重启后可用 | 返回 `status: UP`，db/redis 均 UP | 通过 |
| 创建任务自动触发 PII E2E | `POST /api/v1/integration/sync-tasks` 创建 `codex_pii_scan_20260620223345` | 事件发布并由 Security 消费，按 fieldMapping 写入 PII 记录 | 事件 `PUBLISHED`，`fieldMapping=5`；`security` consumer 已消费；生成 `phone_hash/email_hash/id_card_hash/full_name` 4 条 PII 记录 | 通过 |
| Security/Catalog 测试（PII 反哺 Catalog） | `mvn -q -pl module-security,module-catalog -am test` | Security 发布 PII 事件、Catalog 合并字段标签测试通过 | 退出码 0；新增 `PiiDetectedEventHandlerTest` 与 table.loaded 保留 PII 标签测试通过 | 通过 |
| 全工程跳测编译（PII 反哺 Catalog） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 无错误输出，退出码 0 | 通过 |
| 后端干净重启（PII 反哺 Catalog） | 停止旧 8080 进程后启动 `screen onelake-backend` | 新代码进入运行进程 | 新 PID `71313`，健康检查 `UP` | 通过 |
| 创建任务 -> Security -> Catalog E2E | `POST /api/v1/integration/sync-tasks` 创建 `codex_pii_catalog_20260620224545` | PII detected 事件发布并被 Catalog 消费，资产字段带 PII 标签 | `security.pii.detected` 为 `PUBLISHED`，`detectionCount=4`；`catalog` consumer 已消费；Catalog 资产表级 `classification=L4`，字段含 `piiType/suggestLevel` | 通过 |
| Catalog API 字段安全标签验证 | `GET /api/v1/catalog/assets/127801dd-d90f-4a5c-9e21-ce19f0d52527` | API 返回字段级 PII 标签 | 返回 `phone_hash/email_hash/id_card_hash/full_name`，分别带 `piiType` 与 `suggestLevel` | 通过 |
| 前端构建（Catalog 字段级 PII 标签） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仅有既有 chunk size warning | 通过 |
| Catalog 详情页字段级 PII 浏览器验证 | `/catalog/assets/127801dd-d90f-4a5c-9e21-ce19f0d52527` Schema tab | 前端显示 API 返回的 `piiType/suggestLevel` | 表头包含 `PII类型`、`建议密级`；行显示 `手机号/邮箱/身份证/姓名` 和 L3/L4 建议密级 | 通过 |
| 数据质量后端 API/DB 状态检查 | `GET /api/v1/quality/rules`、`GET /api/v1/quality/alerts`、查询 `quality.*` 表 | 确认真实现和数据基线 | API 返回空数组；`quality.rule/run_result/score_snapshot/alert` 均为 0 条 | 通过 |
| 数据质量页面浏览器检查 | `/quality/rules`、`/quality/results`、`/quality/gate` | 页面可进入且 UI 结构完整 | 三页均可打开，无控制台错误；规则/结果/门禁 UI 完整，但数据和动作均为 mock/本地提示 | 通过 |
| module-quality 测试（质量最小闭环） | `mvn -q -pl module-quality -am test` | 规则创建、试跑、告警和事件发布单测通过 | 退出码 0；新增 `QualityServiceTest` 通过 | 通过 |
| 前端构建（质量最小闭环） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仅有既有 chunk size warning | 通过 |
| quality V2 本地迁移 | `psql < quality/V2__quality_rule_target_column_schedule.sql` | 规则表补齐字段和索引 | `ALTER TABLE` x2；`CREATE INDEX` | 通过 |
| 全工程跳测编译（质量最小闭环） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 无错误输出，退出码 0 | 通过 |
| 后端健康检查（质量最小闭环） | `curl -sS http://localhost:8080/actuator/health` | 后端重启后可用 | 返回 `status: UP` | 通过 |
| 质量规则真实 API E2E | 创建 `RANGE` 规则并调用 `/quality/rules/{id}/run` | 规则、运行结果、告警和事件完整落地 | 规则 `0b95c483-6add-4705-9c03-2af512829d73`；结果 `passRate=96.00`、`failedRows=32`；告警 1 条；`quality.check.failed` 为 `PUBLISHED` | 通过 |
| 质量页面浏览器验证 | `/quality/rules`、`/quality/results` | 页面展示真实规则和运行结果 | 规则页显示目标资产、字段、表达式和 96% 最近通过率；结果页显示失败 32 行和 3 条异常样例；控制台无 error | 通过 |
| module-quality 测试（质量门禁处理） | `mvn -q -pl module-quality -am test` | 告警 DTO 和关闭告警租户校验相关代码编译/测试通过 | 退出码 0；新增跨租户关闭拒绝断言通过 | 通过 |
| 全工程跳测编译（质量门禁处理） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 无错误输出，退出码 0 | 通过 |
| 前端构建（质量门禁处理） | `pnpm build` | TypeScript 与 Vite 构建通过 | 构建成功；仅有既有 chunk size warning | 通过 |
| 后端健康检查（质量门禁处理） | `curl -sS http://localhost:8080/actuator/health` | 后端重启后可用 | 返回 `status: UP` | 通过 |
| 质量门禁真实 API E2E | 清理开放告警 -> 试跑质量规则 -> 查询开放告警 | 告警 DTO 带规则字段和最新失败样例 | 开放告警 `70453798-c138-4b7e-a023-3a4e69cf1790`，`targetColumn=id_card_hash`，`failedRows=32`，`sampleCount=3` | 通过 |
| 质量门禁浏览器点击验证 | `/quality/gate` 选择“降级为告警”并点击“应用” | 前端调用 closeAlert，待处理告警关闭 | 页面从真实失败告警切换为“暂无质量门禁失败”；后端 `GET /quality/alerts` 返回空数组；控制台 0 error | 通过 |
| module-catalog 测试（SQL 工作台现状检查） | `mvn -q -pl module-catalog -am test -Djacoco.skip=true` | SQL 工作台与 Catalog 相关单测通过 | 退出码 0；仅 JVM CDS warning 和预期测试日志 | 通过 |
| 前端类型检查（SQL 工作台现状检查） | `pnpm exec tsc --noEmit` | SQL 工作台前端 API/类型契约通过 TypeScript 检查 | 退出码 0 | 通过 |
| module-catalog 测试（SQL 查询生命周期） | `mvn -q -pl module-catalog -am test -Djacoco.skip=true` | SQL 工作台查询生命周期改动不破坏 Catalog 模块测试 | 退出码 0；仅 JVM CDS warning 和预期测试日志 | 通过 |
| 全工程跳测编译（SQL 查询生命周期） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 退出码 0 | 通过 |
| 前端类型检查（SQL 查询生命周期） | `pnpm exec tsc --noEmit` | 异步查询 API、状态枚举和页面状态机通过 TS 检查 | 退出码 0 | 通过 |
| 前端构建（SQL 查询生命周期） | `pnpm build` | TypeScript 与 Vite 生产构建通过 | 构建成功；仅有既有 chunk size warning | 通过 |
| diff 空白检查（SQL 查询生命周期） | `git diff --check` | 无尾随空白/补丁格式问题 | 无输出，退出码 0 | 通过 |
| module-dataservice 测试（SQL 到 API 草稿） | `mvn -q -pl module-dataservice -am test -Djacoco.skip=true` | 数据服务草稿接口相关代码编译/测试通过 | 退出码 0；仅 JVM CDS warning | 通过 |
| 全工程跳测编译（SQL 到 API 草稿） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 退出码 0 | 通过 |
| 前端类型检查（SQL 到 API 草稿） | `pnpm exec tsc --noEmit` | SQL 工作台、API 向导、API 市场/详情真实 API 接入通过 TS 检查 | 退出码 0 | 通过 |
| 前端构建（SQL 到 API 草稿） | `pnpm build` | TypeScript 与 Vite 生产构建通过 | 构建成功；仅有既有 chunk size warning | 通过 |
| module-dataservice 测试（SQL API 调试运行） | `mvn -q -pl module-dataservice -am test -Djacoco.skip=true` | Trino 调试服务安全校验单测和模块编译通过 | 退出码 0；仅 JVM CDS warning | 通过 |
| 全工程跳测编译（SQL API 调试运行） | `mvn -q install -DskipTests -Djacoco.skip=true` | 全模块编译安装通过 | 退出码 0 | 通过 |
| 前端类型检查（SQL API 调试运行） | `pnpm exec tsc --noEmit` | API 详情调试区真实请求接入通过 TS 检查 | 退出码 0 | 通过 |
| 前端构建（SQL API 调试运行） | `pnpm build` | TypeScript 与 Vite 生产构建通过 | 构建成功；仅有既有 chunk size warning | 通过 |
| diff 空白检查（SQL API 调试运行） | `git diff --check` | 无尾随空白/补丁格式问题 | 无输出，退出码 0 | 通过 |

### 阶段 24：创建采集任务后自动触发目标表 PII 扫描
- **状态：** complete
- **开始时间：** 2026-06-20 CST
- **完成时间：** 2026-06-20 22:34 CST
- 执行的操作：
  - 复核 `SyncTaskServiceImpl#create` 的 `integration.sync_task.created` 事件 payload。
  - 复核 `SyncTaskCreatedEventHandler` 与 `PiiScanServiceImpl` 当前自动扫描链路。
  - 将 `integration.sync_task.created` payload 扩展为结构化 Map，新增 `taskId` 与 `fieldMapping`，保留 `sourceTable/targetTable/tenantId`。
  - `SyncTaskCreatedEventHandler` 解析事件中的 `fieldMapping`，调用字段驱动的 PII 扫描服务。
  - `PiiScanServiceImpl` 新增字段映射扫描入口，并扩展手机号、邮箱、身份证、姓名、银行卡、客户编号等命名规则。
  - `PiiScanRecordRepository` 增加 `existsByTenantIdAndFqn` 查重；新增 security V4 迁移，清理历史重复记录并创建 `tenant_id + fqn` 唯一索引。
  - 补齐 `module-security` 事件处理与扫描服务单测，补齐 `module-integration` 创建事件 payload 测试。
  - 手工应用本地测试库 security V4 迁移；历史重复 PII 记录清理 10 条，唯一索引创建成功。
  - 全工程跳测安装后重启本地 backend，并通过真实 API 创建测试采集任务验证事件和扫描记录。
- 创建/修改的文件：
  - `onelake-app/module-integration/src/main/java/com/onelake/integration/service/impl/SyncTaskServiceImpl.java`
  - `onelake-app/module-integration/src/test/java/com/onelake/integration/service/impl/SyncTaskServiceImplTest.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/event/SyncTaskCreatedEventHandler.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/repository/PiiScanRecordRepository.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/service/PiiScanService.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/service/impl/PiiScanServiceImpl.java`
  - `onelake-app/module-security/src/test/java/com/onelake/security/event/SyncTaskCreatedEventHandlerTest.java`
  - `onelake-app/module-security/src/test/java/com/onelake/security/service/impl/PiiScanServiceImplTest.java`
  - `onelake-app/bootstrap/src/main/resources/db/migration/security/V4__pii_scan_record_unique_fqn.sql`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 26：Catalog 前端接入字段级 PII 标签
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 检查 Catalog API、前端类型和 `AssetDetail` 页面，确认 API 已返回 `piiType/suggestLevel`，但前端未完整展示。
  - 将 `AssetColumn.piiType` 调整为兼容后端中文 PII 类型的字符串，并新增 `suggestLevel` 字段。
  - 在资产详情 Schema 表格增加 `PII类型` 与 `建议密级` 列，复用现有 `ClassificationBadge` 展示建议密级。
  - 运行前端构建。
  - 浏览器登录开发账号后打开真实资产详情页，验证字段级 PII 信息可见。
- 创建/修改的文件：
  - `onelake-app/web-console/src/types/index.ts`
  - `onelake-app/web-console/src/pages/catalog/AssetDetail.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 27：数据质量模块真实化实施计划与 UI 完整性检查
- **状态：** complete
- **开始时间：** 2026-06-21 CST

### 阶段 30：SQL 工作台开发现状检查与后续计划
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 读取 `RTK.md`、规划文件、SQL 工作台后端 Controller/Service/DTO/Entity/Repository、Flyway V5、前端 `/lakehouse/sql` 页面、API 封装、类型和 Trino 配置。
  - 确认 SQL 工作台已具备 Trino JDBC 同步执行、只读校验、结果预览、查询历史和保存查询；不是纯 mock。
  - 识别当前缺口：真实扫描量估算、异步查询与取消、资产/字段权限、SQL AST 安全、表树字段层级、发布为 API/加入流水线上下文传递、Spark 文案与后端能力一致性。
  - 运行后端 `module-catalog` 测试和前端 TypeScript 检查。
- 创建/修改的文件：
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 31：SQL 工作台查询生命周期最小闭环
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - `SqlWorkbenchController` 新增异步提交、状态查询、取消查询接口，并保留同步执行接口。
  - `SqlWorkbenchService` 新增内存运行态、短期结果保留、租户隔离校验和 JDBC Statement 取消逻辑。
  - Trino 结果预览达到上限后截断返回，不再为了精确总行数消费完整结果集。
  - 前端 `SqlWorkbenchAPI` 增加 `submit/query/cancel`。
  - `SqlWorkbench` 页面运行 SQL 改为提交后轮询，查询中可取消；移除暂未支持的 Spark 选项，默认 SQL 改为 `SHOW SCHEMAS`。
  - 表树点击会生成 `SELECT * FROM <fqn> LIMIT 100`，方便从资产进入查询。
  - 运行后端模块测试、全工程跳测编译、前端类型检查和 diff 空白检查。
  - 运行前端生产构建；构建通过，仅保留既有 chunk size warning。
- 创建/修改的文件：
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/api/sql/SqlWorkbenchController.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/repository/sql/SqlQueryHistoryRepository.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlWorkbenchService.java`
  - `onelake-app/bootstrap/src/main/resources/application.yml`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/lakehouse/SqlWorkbench.tsx`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 32：SQL 工作台到 API 草稿联动
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 识别现有数据服务发布器是 PostgREST/Postgres 视图发布链路，不能直接发布 Trino SQL。
  - `DataServiceController` 新增 `/draft` 接口，`DataServicePublisher#createDraft` 保存 DRAFT API 并写审计，不触发 APISIX/PostgREST。
  - `DataserviceAPI` 增加 `createDraft`，并将 list/get/publish/offline 改为解包后的类型契约。
  - SQL 工作台“发布为 API”通过 router state 携带 SQL、来源资产和结果字段进入 API 向导。
  - API 向导接收 SQL 工作台上下文，预填 API 路径、视图名、SQL、参数和返回字段；保存草稿调用真实后端。
  - API 市场与详情页接真实 `DataserviceAPI.listApis/getApi`，失败时回退 mock。
  - 运行数据服务模块测试、全工程跳测编译、前端类型检查和前端构建。
- 创建/修改的文件：
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/api/DataServiceController.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/service/DataServicePublisher.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/lakehouse/SqlWorkbench.tsx`
  - `onelake-app/web-console/src/pages/dataservice/ApiWizard.tsx`
  - `onelake-app/web-console/src/pages/dataservice/ApiMarket.tsx`
  - `onelake-app/web-console/src/pages/dataservice/ApiDetail.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 33：SQL API 草稿 Trino 调试运行
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 为 `module-dataservice` 增加 Trino JDBC 依赖。
  - 新增 `SqlApiDebugResultDTO` 和 `SqlApiRuntimeService`。
  - `SqlApiRuntimeService` 支持按租户读取 API 草稿、只读 SQL 校验、`:param` 命名参数绑定、Trino PreparedStatement 执行和预览结果截断。
  - `ApiDefinitionRepository` 新增 `findByTenantIdAndId`，`DataServicePublisher#get` 改为租户隔离查询。
  - `DataServiceController` 新增 `POST /api/v1/dataservice/apis/{id}/debug`。
  - API 详情页调试区从 mock 响应改为 JSON 参数输入和真实调试请求。
  - 新增 `SqlApiRuntimeServiceTest`，覆盖缺少命名参数和写 SQL 在连接 Trino 前被拒绝。
  - 运行数据服务模块测试、全工程跳测编译、前端类型检查、前端构建和 diff 空白检查。
- 创建/修改的文件：
  - `onelake-app/module-dataservice/pom.xml`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/api/DataServiceController.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/dto/SqlApiDebugResultDTO.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/repository/ApiDefinitionRepository.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/service/DataServicePublisher.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/service/SqlApiRuntimeService.java`
  - `onelake-app/module-dataservice/src/test/java/com/onelake/dataservice/service/SqlApiRuntimeServiceTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/dataservice/ApiDetail.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`
- 执行的操作：
  - 读取 `module-quality` Controller、Service、Entity、Flyway 表结构。
  - 读取质量模块前端页面、路由、API 封装和类型定义。
  - 查询本地后端 API 和 PostgreSQL，确认当前质量规则、运行结果、评分快照和告警均为空。
  - 使用浏览器打开 `/quality/rules`、`/quality/results`、`/quality/gate`，检查页面结构、控件、表格、弹窗和交互。
  - 形成 UI 完整性判断和后续实施计划。
- 创建/修改的文件：
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 25：PII 扫描结果反哺 Catalog 字段安全标签
- **状态：** complete
- **开始时间：** 2026-06-20 CST
- **完成时间：** 2026-06-20 22:47 CST
- 执行的操作：
  - 新增公共事件名 `security.pii.detected`。
  - `PiiScanServiceImpl` 在新增 PII 扫描记录后发布字段检测结果事件。
  - 新增 `PiiDetectedEventHandler`，Catalog 消费 PII 检测事件后预登记或更新资产字段安全标签。
  - `SyncRunEventHandler` 在处理 `integration.table.loaded` 时合并已有 PII 标签，避免真实字段 schema 刷新覆盖安全分类。
  - Catalog `AssetColumnDTO` 增加 `piiType/suggestLevel`，后端 API 可直接返回字段级 PII 标签；未修改前端 UI。
  - 补充 Security 和 Catalog 单元测试。
  - 处理一次本地验证偏差：首次重启时旧 DevTools 进程未退出，新进程因 8080 占用启动失败；清理旧 PID 后用新进程复测通过。
- 创建/修改的文件：
  - `onelake-app/module-common/src/main/java/com/onelake/common/outbox/DomainEvents.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/service/impl/PiiScanServiceImpl.java`
  - `onelake-app/module-security/src/test/java/com/onelake/security/service/impl/PiiScanServiceImplTest.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/event/PiiDetectedEventHandler.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/event/SyncRunEventHandler.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/dto/AssetDTO.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/CatalogService.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/event/PiiDetectedEventHandlerTest.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/event/SyncRunEventHandlerTest.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 28：数据质量规则与稽核结果最小闭环实施
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 扩展质量规则实体和迁移，新增目标字段 `targetColumn` 与调度策略 `schedule`。
  - 为 `quality.run_result.sample` 补齐 `jsonb` 写入映射。
  - 新增质量规则创建 VO、规则 DTO、运行结果 DTO，Controller 返回前端友好的 DTO。
  - `QualityService` 新增规则创建校验、规则列表/详情 DTO 转换、规则试跑、结果记录、告警创建和 `quality.check.*` 事件发布。
  - `QualityAPI` 补齐规则创建、试跑、按目标查询、近期结果和告警接口。
  - `QualityRules` 从 mock 切换为真实 API，创建弹窗资产/字段来自 Catalog，试跑按钮调用后端；未修改样式。
  - `QualityResults` 从 mock 切换为真实规则与运行结果 API，展示通过率、失败行数、趋势和异常样例；未修改样式。
  - 手工应用本地 quality V2 迁移，执行后端测试、前端构建、全工程跳测编译和后端重启。
  - 使用真实 API 创建质量规则并执行试跑，验证运行结果、告警和 outbox 事件发布。
  - 使用浏览器验证 `/quality/rules` 与 `/quality/results` 已展示真实数据。
- 创建/修改的文件：
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/domain/entity/Rule.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/domain/entity/RunResult.java`
  - `onelake-app/bootstrap/src/main/resources/db/migration/quality/V2__quality_rule_target_column_schedule.sql`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/api/vo/CreateQualityRuleVO.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/dto/QualityRuleDTO.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/dto/QualityRunResultDTO.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/repository/RuleRepository.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/repository/RunResultRepository.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/service/QualityService.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/api/QualityController.java`
  - `onelake-app/module-quality/src/test/java/com/onelake/quality/service/QualityServiceTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/quality/QualityRules.tsx`
  - `onelake-app/web-console/src/pages/quality/QualityResults.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 29：数据质量门禁失败处理最小闭环实施
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 新增 `QualityAlertDTO`，开放告警接口返回规则、字段、最近结果和异常样例摘要。
  - `QualityAlertRepository` 改为按创建时间倒序查询开放告警。
  - `QualityService#closeAlert` 增加租户隔离校验。
  - `QualityController#alerts` 改为返回 `QualityAlertDTO`。
  - `QualityAPI` 增加 `getRule` 与 `closeAlert`。
  - 扩展前端 `QualityAlert` 类型，支持后端返回的质量门禁上下文字段。
  - `GateFailed` 页面移除 mock 结果和 mock 审批记录依赖，改为加载真实开放告警。
  - `GateFailed` 页面处理动作接入后端关闭告警；`block` 保持阻断状态不关闭告警。
  - 将页面内消息调用切换为 AntD `App.useApp()`，避免浏览器产生 static message context 警告。
  - 运行后端测试、全工程跳测编译、前端构建、后端重启、真实 API E2E 和浏览器点击验证。
- 创建/修改的文件：
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/dto/QualityAlertDTO.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/repository/QualityAlertRepository.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/service/QualityService.java`
  - `onelake-app/module-quality/src/main/java/com/onelake/quality/api/QualityController.java`
  - `onelake-app/module-quality/src/test/java/com/onelake/quality/service/QualityServiceTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/types/index.ts`
  - `onelake-app/web-console/src/pages/quality/GateFailed.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 34：SQL 工作台真实边界与 Trino 观测收口
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 移除 SQL 工作台表树 Catalog 加载失败时的 `lakehouseAssets` mock fallback。
  - 增加表树真实 loading/error/empty 状态，失败可重试，真实空数据不再伪装成样例表。
  - 为查询结果和查询历史 DTO 增加 `trinoQueryId`，并同步前端类型。
  - 新增 `catalog/V6__sql_query_history_trino_query_id.sql`，为历史表补 `trino_query_id` 字段和索引。
  - 在 `SqlWorkbenchService` 中接入 Trino JDBC progress monitor，采集 query id 与扫描字节数。
  - 查询成功、失败、取消均写回已采集到的 Trino query id 和 scan bytes；采集不到时保持空值。
  - 运行后端模块测试、前端 TypeScript 检查和 diff 空白检查。
- 创建/修改的文件：
  - `onelake-app/bootstrap/src/main/resources/db/migration/catalog/V6__sql_query_history_trino_query_id.sql`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/domain/entity/sql/SqlQueryHistory.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/dto/sql/SqlExecuteResultDTO.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/dto/sql/SqlQueryHistoryDTO.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlWorkbenchService.java`
  - `onelake-app/web-console/src/pages/lakehouse/SqlWorkbench.tsx`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 35：SQL 安全网关 parser 校验底座
- **状态：** complete
- **开始时间：** 2026-06-21 CST
- 执行的操作：
  - 在父 POM 增加 `jsqlparser.version` 与 dependencyManagement。
  - `module-common` 引入 JSqlParser，并新增 `ReadOnlySqlValidator`。
  - 校验器用 AST 解析替代字符串关键字匹配，要求单语句且只允许只读查询类语句。
  - 补充 `ReadOnlySqlValidatorTest` 覆盖 SELECT/WITH、SHOW、DESCRIBE、EXPLAIN SELECT、多语句、写操作、CTAS、SELECT INTO 和无效 SQL。
  - SQL 工作台和 SQL API 调试服务改为共用 `ReadOnlySqlValidator`。
  - 运行 common、catalog、dataservice 模块测试。
- 创建/修改的文件：
  - `onelake-app/pom.xml`
  - `onelake-app/module-common/pom.xml`
  - `onelake-app/module-common/src/main/java/com/onelake/common/sql/ReadOnlySqlValidator.java`
  - `onelake-app/module-common/src/test/java/com/onelake/common/sql/ReadOnlySqlValidatorTest.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlWorkbenchService.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/service/SqlApiRuntimeService.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 36：SQL 执行前 Catalog 资产授权
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - `ReadOnlySqlValidator` 增加 `referencedTables`，从 SQL AST 提取真实表引用。
  - `SecurityService` 增加 `requireQueryAccess`，校验当前用户 ACTIVE 且未过期的 `query=true` 授权。
  - `AccessGrantRepository` 增加按 `tenantId + subjectId + status` 查询。
  - `SqlWorkbenchService` 在执行、提交和异步 worker 连接 Trino 前校验 Catalog 资产存在与查询授权。
  - `SqlApiRuntimeService` 在参数绑定后、连接 Trino 前校验 Catalog 资产存在与查询授权。
  - 补充 common、security、catalog、dataservice 单测。
- 创建/修改的文件：
  - `onelake-app/module-common/src/main/java/com/onelake/common/sql/ReadOnlySqlValidator.java`
  - `onelake-app/module-common/src/test/java/com/onelake/common/sql/ReadOnlySqlValidatorTest.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/repository/AccessGrantRepository.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/service/SecurityService.java`
  - `onelake-app/module-security/src/test/java/com/onelake/security/service/SecurityServiceTest.java`
  - `onelake-app/module-catalog/pom.xml`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlWorkbenchService.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/service/sql/SqlWorkbenchServiceTest.java`
  - `onelake-app/module-dataservice/pom.xml`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/service/SqlApiRuntimeService.java`
  - `onelake-app/module-dataservice/src/test/java/com/onelake/dataservice/service/SqlApiRuntimeServiceTest.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 37：SQL 结果字段脱敏与密级策略
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 新增 `SqlAssetSecurityService.SqlAssetSecurityContext` 的字段保护上下文，读取 Catalog `asset.columns` 中的 `classification`、`piiType`、`suggestLevel`。
  - `SecurityService` 增加 `maskRows`，按字段 FQN 查询显式 `MaskingPolicy`，支持 `NULLIFY/HASH/MASK/PARTIAL`。
  - 未配置显式策略时，PII 字段或 L3/L4 字段默认执行部分脱敏。
  - join 同名列保守合并候选字段 FQN，显式策略会检查所有候选来源，避免因歧义跳过敏感字段。
  - `SqlWorkbenchService` 同步执行和异步执行结果返回前调用 `maskRows`。
  - `SqlApiRuntimeService` 调试结果返回前调用同一套 `maskRows`。
  - 串行运行 common、security、catalog、dataservice 模块测试，全量跳测安装和 diff 空白检查。
- 创建/修改的文件：
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlAssetSecurityService.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlWorkbenchService.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/service/sql/SqlAssetSecurityServiceTest.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/service/sql/SqlWorkbenchServiceTest.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/service/SecurityService.java`
  - `onelake-app/module-security/src/test/java/com/onelake/security/service/SecurityServiceTest.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/service/SqlApiRuntimeService.java`
  - `onelake-app/module-dataservice/src/test/java/com/onelake/dataservice/service/SqlApiRuntimeServiceTest.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 38：SQL 安全边界前端真实表达
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - SQL 工作台错误 Alert 保留后端真实错误，并为 Catalog 未登记、无授权、只读校验失败补充解释。
  - SQL 工作台结果区根据返回列名和值形态提示可能存在策略脱敏，避免用户误认为预览是源表明文。
  - SQL 工作台疑似敏感列列头增加“策略”标记和 tooltip。
  - SQL 工作台查询历史增加“失败原因”列，直接展示后端记录的错误信息。
  - API 详情调试区新增可见错误 Alert，透传 `DataserviceAPI.debugApi` 后端错误。
  - API 详情调试区对疑似敏感/脱敏响应显示安全策略提示。
  - 运行 `pnpm exec tsc --noEmit`、`pnpm build`、`git diff --check`。
  - 浏览器验证：登录 `dev/dev123456` 后打开 `/lakehouse/sql`，确认表树真实加载、历史表头包含“失败原因”；打开 `/dataservice/apis/api-1` 调试 tab，确认真实后端 404 错误可见。
- 创建/修改的文件：
  - `onelake-app/web-console/src/pages/lakehouse/SqlWorkbench.tsx`
  - `onelake-app/web-console/src/pages/dataservice/ApiDetail.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 39：SQL 工作台 P1/P2 效率、估算与联动
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - `SqlWorkbenchService#estimate` 接入 Trino `EXPLAIN (TYPE IO, FORMAT JSON)`，解析扫描量并返回阈值判断；EXPLAIN 不可用时不伪造扫描量。
  - `SqlExecuteRequest` 增加 `confirmLargeQuery`，同步执行和异步提交都会在后端按扫描阈值二次拦截。
  - P2 Spark/自动路由保持真实边界：AUTO 明确路由 Trino，Spark batch 未接入可执行 runtime 前不作为可选执行引擎。
  - 保存查询新增 `PUT /saved-queries/{id}` 与 `DELETE /saved-queries/{id}`；前端支持载入、更新、共享/取消共享、删除。
  - SQL 工作台表树使用 Catalog `Asset.columns` 渲染字段层级，点击字段插入当前编辑器光标位置。
  - Monaco 增加 Catalog 表/字段 completion、`select-limit`/`where-date` snippet 和格式化按钮。
  - 数据服务 API 草稿新增 `requestParams`、`responseSchema` JSONB 字段，API 向导保存 SQL 参数和返回字段 schema。
  - Orchestration DAG DTO 返回 `definition`，创建 DAG 支持 `enabled=false` 草稿；修复 `definition` jsonb 写入转换。
  - SQL 工作台“加入流水线”调用真实 DAG API 创建 SQL 节点草稿，并跳转画布展示。
  - 运行 catalog、dataservice、orchestration 模块测试，全量跳测安装、前端 TypeScript、前端 build。
  - 浏览器验证：`/lakehouse/sql` 可打开；“加入流水线”创建真实 DAG id `3dc4f1c7-29a8-43ab-996b-c5df5b1195aa` 并跳转画布显示 SQL 工作台查询节点。
- 创建/修改的文件：
  - `onelake-app/bootstrap/src/main/resources/db/migration/dataservice/V2__api_definition_sql_contract.sql`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/api/sql/SqlWorkbenchController.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/dto/sql/SqlEstimateDTO.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/dto/sql/SqlExecuteRequest.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlWorkbenchService.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/service/sql/SqlWorkbenchServiceTest.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/domain/entity/ApiDefinition.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/api/DagController.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/domain/entity/Dag.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/dto/DagDTO.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/service/OrchestrationService.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/dataservice/ApiWizard.tsx`
  - `onelake-app/web-console/src/pages/lakehouse/SqlWorkbench.tsx`
  - `onelake-app/web-console/src/pages/orchestration/DagCanvas.tsx`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 40：SQL 工作台 P0.5/P1 生产化收口
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - `SecurityService` 新增 `maskRowsWithNotices`，返回 rows、`maskedColumns`、`securityNotices`；旧 `maskRows` 保持兼容。
  - `SqlExecuteResultDTO` 与 `SqlApiDebugResultDTO` 增加结构化安全提示字段。
  - SQL 工作台和 API 详情前端改为消费后端安全提示，不再靠敏感字段正则和 `****` 猜测脱敏。
  - `SqlAssetSecurityService` 基于 JSqlParser select item 增加简单列别名保护映射，覆盖 `select phone as p`。
  - SQL estimate 在 EXPLAIN 失败时返回可观测原因，仍不伪造扫描量。
  - 新增 `CatalogColumnRefreshService` 和 `POST /api/v1/catalog/assets/refresh-columns`，从 Trino `information_schema.columns` 补全字段为空的 Catalog 资产。
  - SQL 工作台表树加载时发现字段为空会自动尝试字段补全，失败提示真实 warning。
  - 数据服务新增 AppKey 运行时：`GET /api/v1/dataservice/apis/runtime/**`，校验 AppKey、租户、PUBLISHED 状态、APPROVED 订阅、日配额，并记录调用日志。
  - `SecurityConfig` 只放行 SQL API runtime 前缀，其余数据服务接口继续要求 JWT。
  - 新增 `ApiCallLogRepository`、`QuotaUsageRepository`，扩展 API/订阅 Repository 查询方法。
  - 修复 Flyway 迁移入口：新增 `scripts/flyway-migrate.sh`，`make migrate` 改为按 schema 顺序执行；integration 重复 `V2` 改为幂等 `V6`。
  - 修复 integration/security seed 迁移中的 psql 变量、非法 demo UUID 和 security V2 重复索引问题。
  - 检查 Dagster code location，仅存在 schedule reconcile job；未伪装 SQL DAG 已可执行。
  - 运行 `bash -n onelake-app/scripts/flyway-migrate.sh`，并检查 migration 目录无重复版本。
  - 运行 `make migrate`，按 schema 顺序完整通过。
  - 运行 `mvn -q -pl module-security -am test -Djacoco.skip=true`。
  - 运行 `mvn -q -pl module-catalog -am test -Djacoco.skip=true`。
  - 运行 `mvn -q -pl module-dataservice -am test -Djacoco.skip=true`。
  - 运行 `mvn -q install -DskipTests -Djacoco.skip=true`。
  - 运行 `pnpm exec tsc --noEmit` 与 `pnpm build`。
  - 运行 `git diff --check`。
- 创建/修改的文件：
  - `onelake-app/Makefile`
  - `onelake-app/scripts/flyway-migrate.sh`
  - `onelake-app/bootstrap/src/main/resources/db/migration/integration/V6__integration_first_iteration.sql`
  - `onelake-app/bootstrap/src/main/resources/db/migration/integration/V4__integration_seed.sql`
  - `onelake-app/bootstrap/src/main/resources/db/migration/security/V2__pii_scan_record.sql`
  - `onelake-app/bootstrap/src/main/resources/db/migration/security/V3__security_seed.sql`
  - `onelake-app/module-common/src/main/java/com/onelake/common/security/SecurityConfig.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/api/CatalogController.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/dto/sql/SqlExecuteResultDTO.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/CatalogColumnRefreshService.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlAssetSecurityService.java`
  - `onelake-app/module-catalog/src/main/java/com/onelake/catalog/service/sql/SqlWorkbenchService.java`
  - `onelake-app/module-catalog/src/test/java/com/onelake/catalog/service/sql/SqlAssetSecurityServiceTest.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/api/DataServiceController.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/dto/SqlApiDebugResultDTO.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/repository/ApiCallLogRepository.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/repository/ApiDefinitionRepository.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/repository/QuotaUsageRepository.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/repository/SubscriptionRepository.java`
  - `onelake-app/module-dataservice/src/main/java/com/onelake/dataservice/service/SqlApiRuntimeService.java`
  - `onelake-app/module-dataservice/src/test/java/com/onelake/dataservice/service/SqlApiRuntimeServiceTest.java`
  - `onelake-app/module-security/src/main/java/com/onelake/security/service/SecurityService.java`
  - `onelake-app/module-security/src/test/java/com/onelake/security/service/SecurityServiceTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/dataservice/ApiDetail.tsx`
  - `onelake-app/web-console/src/pages/lakehouse/SqlWorkbench.tsx`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 41：ODS 到 DWD 标准闭环实施方案细化
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 读取既有 `task_plan.md`、`findings.md`、`progress.md`，确认当前项目计划已推进到阶段 40。
  - 复核当前 ODS 入湖、Catalog、质量、SQL 工作台、dbt 和 Dagster 的真实边界。
  - 将标准 ODS->DWD 闭环拆成 0-8 轮迭代，逐轮明确目标、前后端/数据面改动、验收和不做事项。
  - 新增 `docs/ODS到DWD标准闭环实施方案.md`，作为后续“继续实施”时的执行基准。
  - 更新 `task_plan.md` 和 `findings.md`，记录阶段 41 与关键发现。
- 创建/修改的文件：
  - `docs/ODS到DWD标准闭环实施方案.md`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 42：ODS 到 DWD 方案链路匹配评审
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 复读 `docs/ODS到DWD标准闭环实施方案.md`，按源端入湖、ODS 资产、DWD 模型、编排运行、dbt 执行、质量门禁、Catalog/血缘、前端可观测逐段检查。
  - 对照现有 `SyncRunSucceededEventHandler`、`OrchestrationService`、Catalog/Quality/SQL 工作台阶段发现，确认方案主线与整体设计一致。
  - 识别并补强关键缺口：DWD 模型需映射为编排 DAG/Asset、DWD 终态需要独立模型事件、质量门禁应解析 dbt artifacts、字段安全标签应沿 ODS->DWD 继承。
  - 在方案文档中新增整体链路匹配评审矩阵、必须补强的设计约束、MVP-A/MVP-B 发布点和更新后的首批任务清单。
  - 更新 `task_plan.md` 和 `findings.md`，记录阶段 42 与评审结论。
- 创建/修改的文件：
  - `docs/ODS到DWD标准闭环实施方案.md`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 43：ODS 到 DWD 加工治理与算力/流水线兼容评审
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 调研 Databricks Lakeflow Jobs/compute/expectations/Unity Catalog lineage、Dagster dbt assets/asset checks、dbt artifacts、Airflow Pools、Azure Data Factory mapping data flows、AWS Glue Data Quality。
  - 检查 OneLake 前端流水线、DagCanvas、OperatorMarket、SQL 工作台 `resourceGroup` 与“加入流水线”能力。
  - 检查前端 `DagNode` 类型和后端 `orchestration.dag/job_run` 当前字段边界，确认现有 DAG definition 可承载节点图，但缺少资源画像与运行成本/排队观测字段。
  - 在 ODS->DWD 方案文档中新增加工治理、流水线、算子市场、算力/资源组的边界评审。
  - 新增 `迭代 2.5：加工治理算子与算力资源契约`，要求 DWD 模型保存默认 operator graph、resource profile，并编译为 dbt 产物和 orchestration DAG。
  - 更新第一批落地顺序和首批任务清单，把迭代 2.5 纳入 MVP-A。
  - 更新 `task_plan.md` 和 `findings.md`，记录阶段 43 与评审结论。
- 创建/修改的文件：
  - `docs/ODS到DWD标准闭环实施方案.md`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 44：全局任务条开发进展检查与实施方案
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 读取 `RTK.md`、现有 `task_plan.md`、`findings.md`、`progress.md`，确认继续使用既有规划文件。
  - 检查原型设计文档和前端验证报告中关于全局任务条、通知中心、长任务反馈的要求。
  - 检查 `TaskProgressBar.tsx`、`App.tsx`、`stores/app.ts`、`mock/index.ts`、`NotificationCenter.tsx` 和 `api/index.ts`。
  - 检查后端采集 run、SQL query、DAG run、质量结果、common alert/notification、Outbox 事件面，确认全局任务条当前缺少统一任务投影。
  - 新增 `docs/全局任务条实施方案.md`，给出目标边界、数据模型、API、前端接入、通知联动、阶段计划和验证策略。
  - 更新 `task_plan.md` 与 `findings.md`，记录阶段 44 和关键结论。
- 创建/修改的文件：
  - `docs/全局任务条实施方案.md`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 45：ODS 到 DWD 迭代 0 样例数据基线实施
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 新增 `scripts/ods-dwd-baseline.sh`，准备 `onelake_src.public.codex_orders` 源表和 `iceberg.ods.ods_codex_orders` ODS 表。
  - 新增 Makefile 入口 `ods-dwd-baseline` 与 `ods-dwd-verify`，并同步 RTK 命令清单。
  - 第一次运行发现 `psql -c` 不支持脚本中的 `\gexec`，改为先查库再 `createdb`。
  - 第二次运行发现环境里已有旧版 `codex_orders` 表结构不一致，将样例源表改为显式重建。
  - 成功运行 `make ods-dwd-baseline`：源表 10 行、ODS 表 10 行、3 条脏数据、6 个字段。
  - 成功运行 `make ods-dwd-verify`，脚本确认源表、ODS 表和 Catalog 资产状态。
  - 运行 `mvn -q -pl module-catalog -am test -Djacoco.skip=true`，验证 `integration.table.loaded` 事件处理代码。
  - 用标准 `integration.table.loaded` Outbox 事件验证运行态：Redis Stream 中事件被 `orchestration` 与 `catalog` 消费，`catalog.asset` 生成 `ods.ods_codex_orders`，字段数 6，行数 10，字段级血缘数 6。
  - 查询后端 Airbyte connector definitions 时返回 Unauthorized，记录为当前运行态 Airbyte auth 配置缺口，不阻塞后续 DWD 基于稳定 ODS 表推进。
  - 运行 Trino 行数/脏数据查询和 `git diff --check`。
- 创建/修改的文件：
  - `RTK.md`
  - `onelake-app/Makefile`
  - `onelake-app/scripts/ods-dwd-baseline.sh`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 46：ODS 到 DWD 迭代 1 派生入口与模型草稿
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 新增 `modeling.data_model`、`modeling.data_model_source`、`modeling.data_model_column_mapping` 三张迁移表，保存 DWD 模型草稿、上游来源和字段映射。
  - 在 `module-modeling` 增加 DWD 模型实体、Repository、DTO、`DwdModelService` 和 API：创建草稿、读取、更新、校验。
  - 服务层强制校验 `sourceFqn` 必须是 Catalog 中的 ODS 资产且字段非空，`targetFqn` 必须位于 DWD 分层，字段映射默认继承上游密级/PII 标签。
  - 前端 ODS 表详情新增“派生 DWD”动作；建表/建模向导支持 `derive=dwd&sourceAssetId=...` 模式，自动带入源 ODS、业务域、字段映射、主键和分区建议。
  - 派生模式提交时调用 `ModelingAPI.createDwdDraft`，保存建模草稿，不直接创建物理湖仓表。
  - 通过真实 API 创建草稿并调用 validate，确认依赖 `ods.ods_codex_orders`、输出 6 列、无错误。
  - 浏览器验证 `/lakehouse/tables/a5e6f4aa-7ef7-4acd-8406-c5ebd07f6499`：ODS 详情展示“派生 DWD”，向导预填 `ods.ods_codex_orders`、6 个字段、`days(order_time)` 分区，并成功保存草稿 `346b0f13-712b-41a2-8749-a25f96c19924`。
  - 验证命令通过：`mvn -q -pl module-modeling -am test -Djacoco.skip=true`、`pnpm exec tsc --noEmit`、`pnpm build`、`make migrate`、`make ods-dwd-verify`、后端 validate API、`git diff --check`。
- 创建/修改的文件：
  - `onelake-app/bootstrap/src/main/resources/db/migration/modeling/V2__dwd_data_model.sql`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/domain/entity/DataModel.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/domain/entity/DataModelSource.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/domain/entity/DataModelColumnMapping.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/dto/`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/repository/`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/service/DwdModelService.java`
  - `onelake-app/module-modeling/src/test/java/com/onelake/modeling/service/DwdModelServiceTest.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/api/ModelingController.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/types/index.ts`
  - `onelake-app/web-console/src/pages/lakehouse/TableDetail.tsx`
  - `onelake-app/web-console/src/pages/lakehouse/TableWizard.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 47：ODS 到 DWD 迭代 2 DWD SQL/dbt 生成与静态校验
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 读取 `onelake-app/dbt` 工程结构、`dbt_project.yml`、`profiles.yml`、示例 DWD/ADS 模型和 orchestration DAG 实体/服务。
  - 新增 `DwdModelCompileDTO` 与 `POST /api/v1/modeling/models/{id}/compile`。
  - `DwdModelService.compileArtifacts` 在校验通过后生成 dbt 产物：
    - `dbt/models/generated/sources.yml`
    - `dbt/models/intermediate/dwd_trade_codex_orders_df.sql`
    - `dbt/models/intermediate/dwd_trade_codex_orders_df.yml`
  - compile 会创建或更新 disabled `orchestration.dag` 草稿，`dagsterJob=onelake_dbt_model_run`，definition 包含 ODS input、质量门禁、dbt model 和 DWD output。
  - 修复 dbt 默认输出路径：后端运行工作目录是 `bootstrap`，默认 dbt project dir 从 `dbt` 改为 `../dbt`，避免误写到 `bootstrap/dbt`。
  - 修复 Makefile 后端启动：先 `-pl bootstrap -am compile`，再 `-pl bootstrap spring-boot:run`，避免子模块改动后运行旧 jar。
  - 使用真实 API 对模型 `346b0f13-712b-41a2-8749-a25f96c19924` 执行 compile，生成 dbt 文件并更新 DAG `6c0560c0-627c-483e-9072-088a96e614e0`。
  - 使用 `uvx --from dbt-trino dbt parse --profiles-dir .` 验证 dbt 解析通过。
- 创建/修改的文件：
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/dto/DwdModelCompileDTO.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/service/DwdModelService.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/api/ModelingController.java`
  - `onelake-app/module-modeling/src/test/java/com/onelake/modeling/service/DwdModelServiceTest.java`
  - `onelake-app/dbt/models/generated/sources.yml`
  - `onelake-app/dbt/models/intermediate/dwd_trade_codex_orders_df.sql`
  - `onelake-app/dbt/models/intermediate/dwd_trade_codex_orders_df.yml`
  - `onelake-app/Makefile`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 48：ODS 到 DWD 迭代 2.5 加工治理算子与算力资源契约
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 新增迁移 `modeling/V3__dwd_operator_resource_contract.sql`，为 `modeling.data_model` 增加 `pipeline_mode/operator_graph_version/operator_graph/resource_group/compute_profile/engine/cost_policy`。
  - `DataModel`、DTO 和前端类型同步暴露资源画像与算子图字段。
  - compile 阶段生成系统默认 operator graph：`INPUT -> TRANSFORM -> GOVERN -> QUALITY_GATE -> DBT_MODEL -> OUTPUT`，敏感字段存在时插入 `MASK` 节点。
  - operator graph 同时写入 `modeling.data_model.operator_graph` 和 `orchestration.dag.definition.operatorGraph`，DAG 顶层同步 `engine/resourceGroup/computeProfile/costPolicy`。
  - 默认资源画像固定为 `engine=TRINO_DBT`、`resourceGroup=default`、`computeProfile=trino-small`，默认 cost policy 为 1TB 扫描阈值、30 分钟超时、0 次重试、大扫描需确认。
  - 真实 API compile 后确认模型状态 `VALIDATED`，operator graph 节点数 6，DAG disabled，DAG 节点类型与模型一致。
  - 验证命令通过：`make migrate`、`mvn -q -pl module-modeling -am test -Djacoco.skip=true`、`pnpm build`、`uvx --from dbt-trino dbt parse --profiles-dir .`、`make ods-dwd-verify`、`git diff --check`。
- 创建/修改的文件：
  - `onelake-app/bootstrap/src/main/resources/db/migration/modeling/V3__dwd_operator_resource_contract.sql`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/domain/entity/DataModel.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/dto/DataModelDTO.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/dto/DwdModelCompileDTO.java`
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/service/DwdModelService.java`
  - `onelake-app/module-modeling/src/test/java/com/onelake/modeling/service/DwdModelServiceTest.java`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 50：全局任务条 P0 真实任务投影与前端接入
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 按 `docs/全局任务条实施方案.md` 推荐切片，先实现统一任务投影 + 采集 run 接入 + 前端真实任务条。
  - 新增 `common.running_task` 迁移，后端提供 `GET /api/v1/tasks/running` 与 `POST /api/v1/tasks/{id}/dismiss`。
  - `RunningTaskService` 查询时同步 `integration.sync_run`，把采集 run 统一映射为全局任务条状态、进度、详情链接、错误信息和取消端点。
  - 前端新增 `TaskAPI` 与 `useGlobalTasks`，按展开态和页面可见性轮询真实任务。
  - `TaskProgressBar` 移除 mock 依赖，支持 `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED`、任务类型标签、失败提示、查看详情、取消和忽略。
  - `App.tsx` 接入真实任务 hook，并用 AntD `App.useApp()` 处理任务操作反馈。
  - 运行 `mvn -q -pl module-common -am test -Djacoco.skip=true`。
  - 运行 `mvn -q -pl module-integration -am test -Djacoco.skip=true`。
  - 运行 `pnpm exec tsc --noEmit` 与 `pnpm build`。
  - 运行 `bash -n onelake-app/scripts/flyway-migrate.sh`、`make migrate` 和 `git diff --check`。
  - 本地启动后端并验证 `http://localhost:8080/actuator/health` 为 `UP`。
  - 使用 Keycloak `dev/dev123456` token 调用 `/api/v1/tasks/running?includeRecent=true&limit=20`，返回失败 `orders_sync -> ods.orders` 与运行中 `user_cdc -> ods.users` 两条真实任务。
  - 浏览器打开 `http://localhost:5173/dashboard`，折叠态任务按钮显示真实运行计数；展开全局任务条可见 `orders_sync`、`user_cdc` 和运行中取消按钮，控制台无 error。
- 创建/修改的文件：
  - `onelake-app/bootstrap/src/main/resources/db/migration/common/V5__running_task.sql`
  - `onelake-app/module-common/src/main/java/com/onelake/common/task/RunningTask.java`
  - `onelake-app/module-common/src/main/java/com/onelake/common/task/RunningTaskRepository.java`
  - `onelake-app/module-common/src/main/java/com/onelake/common/task/RunningTaskDTO.java`
  - `onelake-app/module-common/src/main/java/com/onelake/common/task/RunningTaskService.java`
  - `onelake-app/module-common/src/main/java/com/onelake/common/task/RunningTaskController.java`
  - `onelake-app/module-common/src/test/java/com/onelake/common/task/RunningTaskServiceTest.java`
  - `onelake-app/web-console/src/hooks/useGlobalTasks.ts`
  - `onelake-app/web-console/src/components/TaskProgressBar.tsx`
  - `onelake-app/web-console/src/App.tsx`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/stores/app.ts`
  - `onelake-app/web-console/src/types/index.ts`
  - `onelake-app/web-console/src/components/index.ts`
  - `onelake-app/web-console/src/mock/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 51：全局任务条 P1 SQL/编排/质量多来源接入
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 延续 `docs/全局任务条实施方案.md` 的统一投影路线，在 `RunningTaskService` 查询时继续同步 SQL 查询、编排 run 和质量稽核结果。
  - `catalog.sql_query_history` 映射为 `sourceModule=LAKEHOUSE`、`taskType=SQL`、`refType=sql_query`；运行中 SQL 暴露 `/lakehouse/sql/queries/{id}/cancel`，终态跳转 `/lakehouse/sql`。
  - `orchestration.job_run` 映射为 `sourceModule=ORCHESTRATION`、`taskType=DAG`、`refType=job_run`；任务跳转 `/orchestration/pipelines/{dagId}`，当前不提供全局取消。
  - `quality.run_result` 映射为 `sourceModule=QUALITY`、`taskType=QUALITY`、`refType=quality_run_result`；失败结果带 `QUALITY_CHECK_FAILED` 与异常行摘要。
  - SQL、编排和质量源同步只拉取运行中或最近 10 分钟结果，避免历史失败记录批量灌入全局任务条；进入投影后的失败仍保留到用户忽略，符合告警可见性要求。
  - 补充 `RunningTaskServiceTest` 覆盖 SQL 可取消映射、编排成功状态归一和质量失败结果映射。
  - 运行 `mvn -q -pl module-common -am test -Djacoco.skip=true`。
  - 运行 `mvn -q install -DskipTests -Djacoco.skip=true`，刷新本地 Maven SNAPSHOT；验证发现只用旧进程或旧 installed jar 会导致后端仍加载旧 `module-common`。
  - 清理本地 `common.running_task` 的 SQL/质量/编排投影后调用 `/api/v1/tasks/running?includeRecent=true&limit=50`，返回质量、SQL、采集失败、采集运行中 4 条任务。
  - 临时插入并清理一条 `orchestration.job_run` 验证编排映射，接口返回 `ORCHESTRATION/DAG`、标题 `编排任务 DWD dwd_trade_codex_orders_df`、跳转到对应 pipeline。
  - 浏览器登录本地 Keycloak `dev` 用户打开 `http://localhost:5173/dashboard`，折叠态任务按钮显示 `任务 4 / 1 运行中`；展开全局任务条可见质量稽核、`SQL 查询 SHOW SCHEMAS`、`orders_sync`、`user_cdc`，控制台无 error。
- 创建/修改的文件：
  - `onelake-app/module-common/src/main/java/com/onelake/common/task/RunningTaskService.java`
  - `onelake-app/module-common/src/test/java/com/onelake/common/task/RunningTaskServiceTest.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 52：全局任务条 P2 通知中心真实化联动
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 按全局任务条方案阶段 D 推进通知中心真实化：任务条继续展示运行态和近期结果，通知中心承接失败后需要用户感知的消息。
  - 新增迁移 `common/V6__notification_contract.sql`，为 `common.notification` 补充 `content/level/source_ref_type/source_ref_id`，并增加未读查询索引和来源幂等唯一索引。
  - 新增 `Notification`、`NotificationDTO`、`NotificationRepository`、`NotificationService`、`NotificationController`，提供 `GET /api/v1/notifications`、`POST /notifications/{id}/read`、`POST /notifications/read-all`。
  - `RunningTaskService` 保存任务投影后调用 `NotificationService.notifyTaskIfNeeded`；当前只为 `FAILED` 任务生成 `TASK/CRITICAL` 通知，成功任务仍只保留在任务条近期窗口，避免通知中心噪音。
  - 前端新增 `NotificationAPI` 与 `useNotifications`，`App.tsx` 挂载通知轮询，顶部铃铛未读数来自真实 API。
  - `NotificationCenter` 保持原有抽屉布局和分类标签，查看/全部已读调用真实 API；通知按钮补充 `aria-label="打开通知中心"`。
  - 手工应用本轮 notification 迁移到本地 Postgres，重启后端并用 Keycloak `dev/dev123456` token 验证任务条刷新后生成真实通知。
  - API 验证：`/api/v1/notifications?limit=20` 返回 `任务失败：采集任务 orders_sync -> ods.orders`，内容为 `账号密码过期`，`POST /notifications/{id}/read` 返回 `isRead=true`。
  - 浏览器验证：`/dashboard` 顶部铃铛显示未读数 1，展开通知中心可见 `CRITICAL` 任务失败通知、内容和查看入口，控制台无 error。
  - 验证命令通过：`mvn -q -pl module-common -am test -Djacoco.skip=true`、`mvn -q install -DskipTests -Djacoco.skip=true`、`pnpm exec tsc --noEmit`。
- 创建/修改的文件：
  - `onelake-app/bootstrap/src/main/resources/db/migration/common/V6__notification_contract.sql`
  - `onelake-app/module-common/src/main/java/com/onelake/common/notification/`
  - `onelake-app/module-common/src/main/java/com/onelake/common/task/RunningTaskService.java`
  - `onelake-app/module-common/src/test/java/com/onelake/common/notification/NotificationServiceTest.java`
  - `onelake-app/module-common/src/test/java/com/onelake/common/task/RunningTaskServiceTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/hooks/useNotifications.ts`
  - `onelake-app/web-console/src/stores/app.ts`
  - `onelake-app/web-console/src/components/NotificationCenter.tsx`
  - `onelake-app/web-console/src/App.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

## 错误日志
| 时间戳 | 错误 | 尝试次数 | 解决方案 |
|--------|------|---------|---------|
| 2026-06-15 23:57 CST | `pnpm build` 因既有 `FileCollect.tsx` 调用不存在的 `IntegrationAPI.http` 失败 | 1 | 改为调用已有 `IntegrationAPI.listFileSourceFiles` 后构建通过 |
| 2026-06-15 23:59 CST | 浏览器访问向导被 SSO 重定向到 Keycloak 登录页 | 1 | 记录为验证受阻；需登录态或临时关闭认证后做视觉复验 |
| 2026-06-16 00:16 CST | 阶段 A 浏览器冒烟被 Keycloak 登录页接管 | 1 | 记录为验证受阻；代码构建和控制台检查已通过，需登录态后复验向导内部交互 |
| 2026-06-16 07:01 CST | schemas 接口调用 500，后端日志为 `No static resource .../schemas` | 1 | 刷新本地 SNAPSHOT 并重启后端；OpenAPI 已恢复 schemas 路径 |
| 2026-06-16 07:06 CST | 后端重启失败：`alertRepository` Bean 名冲突 | 1 | 将质量模块仓储重命名为 `QualityAlertRepository` |
| 2026-06-16 07:09 CST | 后端重启失败：两个 `Alert` 实体默认实体名冲突 | 1 | 显式设置实体名为 `CommonAlert`、`QualityAlert` |
| 2026-06-16 10:08 CST | 发布按钮创建任务阶段 500：`field_mapping` 写入 `jsonb` 被当作 varchar | 1 | 为 `SyncTask.fieldMapping` 增加 `@ColumnTransformer(write = "?::jsonb")` 后创建接口返回 200 |
| 2026-06-16 10:10 CST | Outbox 定时任务报 `aggregate_type` 缺列 | 1 | 手工应用 `common/V4__outbox_stream_contract.sql` 补齐本地表结构 |
| 2026-06-16 10:11 CST | `make migrate` 失败：`PG_HOST` 未解析；显式参数后又遇到多目录重复 `V1` 迁移版本 | 2 | 本轮绕过全量迁移，只应用缺失 V4；后续需要修复 Flyway 命令/版本策略 |
| 2026-06-16 10:23 CST | `SyncTaskCreatedEventHandler` 写审计日志时 `audit_log.tenant_id` 为空 | 1 | 事件消费者从 envelope 恢复 `TenantContext` 后审计可写入 tenant |
| 2026-06-16 10:25 CST | PII 扫描消费失败：`security.pii_scan_record` 表不存在 | 1 | 手工应用 security V2 建表 |
| 2026-06-16 10:26 CST | security V3 种子数据使用非法 UUID `pppppppp-...` | 1 | 改为合法 UUID 前缀 `99999999-...` 并重新应用 |
| 2026-06-16 10:40 CST | 发布失败 toast 显示 `Request failed with status code 400` | 1 | 全局 axios 错误拦截器读取 `ApiResponse.message` 后复测通过 |
| 2026-06-16 CST | 阶段 15 首次后端测试失败：既有测试仍假设 trigger 只 save 一次且 reconcile 只读 `getJobStatus` | 1 | 更新测试契约为 `QUEUED -> RUNNING` 双阶段落库和 `AirbyteJobSnapshot` 回写 |
| 2026-06-16 CST | dry-run 单测 payload 自带 `airbyteConnectionId` 导致进入 connection 检查并默认失败 | 1 | 改为无 connection、具备动态创建配置的 dry-run 用例 |
| 2026-06-22 CST | `make migrate` 首次复验失败：integration/security seed 使用 psql `\set`，integration demo UUID 非法，security V2 索引重复 | 3 | 移除 psql 变量、替换合法 UUID、让 seed/索引幂等后 `make migrate` 完整通过 |
| 2026-06-22 CST | `make ods-dwd-baseline` 首次失败：脚本在 `psql -c` 中使用 `\gexec` 导致语法错误 | 1 | 改为先查询数据库是否存在，不存在再调用 `createdb` |
| 2026-06-22 CST | `make ods-dwd-baseline` 第二次失败：本地已有旧版 `public.codex_orders`，缺少 `order_id` 等目标字段 | 1 | 将固定样例源表改为显式 `DROP TABLE IF EXISTS` 后重建 |
| 2026-06-22 CST | API 验证脚本用 zsh `echo "$JSON"` 解析响应时把 JSON 内 `\n` 展开为真实换行，导致 `jq` 报控制字符错误 | 1 | 改用 `printf '%s' "$JSON"` 解析；后端响应本身有效 |
| 2026-06-22 CST | 浏览器提交草稿后控制台出现 Ant Design 静态 `message` 主题上下文 warning | 1 | 判定为现有 `useAsyncAction` 全局模式触发，不阻断 ODS->DWD 链路；后续统一处理 AntD App/useApp 上下文 |
| 2026-06-22 CST | DWD compile 首次把 dbt 产物写入 `bootstrap/dbt` | 1 | 后端运行目录为 `bootstrap`，将默认 dbt project dir 改为 `../dbt` 并清理误生成目录 |
| 2026-06-22 CST | 后端重启后仍返回旧 OpenAPI schema，compile 响应缺少 2.5 字段 | 2 | 发现 `make backend` 只运行 bootstrap，没有先编译 reactor 依赖；改为先 `mvn -pl bootstrap -am compile`，再运行 bootstrap |
| 2026-06-22 CST | 通知 API 验证脚本用 Python 从环境变量读取 `TOKEN`，但 shell 未 export，导致清理 SQL receiver_id 为空 | 1 | 改为直接用接口返回的通知 id 验证已读；后续脚本用 stdin 或显式 export 传 token |

### 阶段 70：资产发现与湖仓分层表管理边界升级
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 读取现有规划文件、工作区状态、`CatalogSearch`、`Tables`、`AssetDetail`、`TableDetail`、导航和资产 DTO。
  - 确认本轮只做前端 P0 边界升级与文档同步，不改后端接口，不回滚既有编排/建模改动。
- 核对：
  - 当前工作区已有未提交改动，涉及 `App.tsx`、`api/index.ts`、`TableDetail.tsx`、`types/index.ts` 等；本轮将在当前内容上叠加，避免覆盖用户/前序改动。
  - 第 1 项导航与页面定位命名已完成：`/catalog/search` 菜单为“资产发现”，页头为“数据资产发现”；`/lakehouse/tables` 菜单为“分层表管理”，页头为“湖仓分层表”。
  - 核对命令：`rg -n "搜索浏览|资产发现|分层表浏览|分层表管理|湖仓分层表|数据资产发现" ...`、`pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false`，均通过。
  - 第 2 项资产发现页差异化已完成：筛选改为资产类型/业务标签/密级/质量/负责人/湖仓分层，结果区改为“可用资产”，主操作为申请访问与资产画像，热门资产按订阅/访问热度排序。
  - 核对命令：`pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过；`rg` 确认旧硬编码负责人、旧搜索结果文案和警示符号未残留在 `CatalogSearch.tsx`。
  - 第 3 项分层表管理页差异化已完成：接入 `CatalogAPI.listMaintenance()` 作为维护状态增强，左侧分层树显示待治理数，表格改为表格式/分区、规模、质量门禁、同步维护和治理动作。
  - 核对命令：`pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过；`rg` 确认旧“资产清单/搜表名字段”入口文案已替换。
  - 第 4 项详情页互跳已完成：资产详情面包屑改为“资产发现”并新增“湖仓治理详情”；表详情面包屑改为“分层表管理”并新增“资产画像”。
  - 核对命令：`pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过；`rg` 确认 `from=catalog/from=lakehouse` 互跳入口已存在。
  - 第 5 项文档与总体验证已完成：`docs/FRONTEND_VERIFICATION.md` 同步资产发现、分层表管理和详情页互跳连续性。
  - 核对命令：`pnpm --dir onelake-app/web-console build` 通过；`git diff --check` 通过；浏览器访问 `/catalog/search` 显示“数据资产发现/发现筛选/可用资产/热门资产”，访问 `/lakehouse/tables` 显示“湖仓分层表/表治理清单/贴源 ODS/明细 DWD/治理详情”。

## 五问重启检查
| 问题 | 答案 |
|------|------|
| 我在哪里？ | 阶段 15 已完成 |
| 我要去哪里？ | 下一步进入 Dagster 调度注册、Airbyte connector definition 自动发现/表单 schema、真实本地数据面联调 |
| 目标是什么？ | 让数据集成从控制面创建进入可发布、可试跑、可触发、可回写、可诊断的完整闭环 |
| 我学到了什么？ | 见 `findings.md` |
| 我做了什么？ | 见上方记录 |

---
*每个阶段完成后或遇到错误时更新此文件*

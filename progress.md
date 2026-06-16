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

## 五问重启检查
| 问题 | 答案 |
|------|------|
| 我在哪里？ | 阶段 8 已完成 |
| 我要去哪里？ | 可进入 dry-run、run logs/cancel、Airbyte source/destination 动态创建或 Dagster 调度注册 |
| 目标是什么？ | 推进采集任务创建流程闭环的下一轮开发 |
| 我学到了什么？ | 见 `findings.md` |
| 我做了什么？ | 见上方记录 |

---
*每个阶段完成后或遇到错误时更新此文件*

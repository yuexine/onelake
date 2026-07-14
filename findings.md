# 发现与决策：数据集成模块后端迭代调研

## 2026-07-14 算子版本锁定真实运行验证

- 待验证关键边界：生产触发应读取 `published_version_id` 对应不可变快照，而不是当前 DEV `pipeline_task`；否则算子 v2 草稿会污染未重新发布的生产运行。
- 为使“重新发布后采用 v2”可观测，算子发布 v2 后需要把 DEV 节点的 `operatorVersion` 显式升级到 v2；第一次生产复跑发生在流水线重新发布之前，第二次发生在重新发布之后。
- 当前后端 `8080` 健康为 UP，Postgres/Redis/MinIO/Keycloak/Trino 已运行；Dagster/Spark 容器当前未运行，需要在真实触发前启动。
- 代码路径已确认：`env=PROD` 触发会选取 DAG 的 `publishedVersionId`，读取 `PipelineSnapshotService.ExecutionSnapshot`，再从冻结的 task/edge 编译运行配置；不会直接读取当前 DEV `pipeline_task`。
- 测试算子采用零输入 `RAW_SQL`，v1/v2 输出不同的 `version_marker` 和数值，结果表可直接证明实际执行模板版本；目标表选用已有 Iceberg `dwd` namespace，避免把 namespace 创建能力混入本次验证。
- 流水线已是 `PUBLISHED` 且存在 `hasUnpublishedChanges=true` 时，再次请求 `PUBLISHED` 会先校验当前草稿并生成新快照；因此 v2 场景无需把主状态回退到 DRAFT/VALIDATED。
- 运行依赖的 compose 服务名已确认：`hive-metastore`、`spark-master`、`spark-worker`、`dagster-postgres`、`dagster-user-code`、`dagster-webserver`、`dagster-daemon`。
- 从算子拖入节点的实际请求 DTO 是 `OperatorTaskCreateRequest`；节点后续改绑 v2 使用完整 `PipelineTaskRequest`，其中 `operatorRef` 与 `operatorVersion` 成对提交。
- Dagster 的统一流水线 job 为 `onelake_pipeline_run`，实际 Spark 节点通过公共 `spark-submit` 组装路径执行，可在 run 日志中反查提交命令和生成 SQL。
- `OperatorTaskCreateRequest` 只接受 `operatorRef`、精确 `version` 和 `position{x,y}`；由服务端读取锁定 Manifest 并生成标准节点，符合本次从 Palette 语义验证。
- Spark master/worker、Hive Metastore 与 Dagster webserver/daemon/user-code 当前均为 Up，Dagster UI 已监听 `3000`。
- Keycloak `dev` 账号当前可获取 300 秒访问令牌，发布审批开关实时返回 `enabled=false`，本次发布会直接生成快照，不会停在审批态。
- Manifest 注册接口直接接收完整 `OperatorManifestDTO`；算子新版本接口接收 `{manifest, changelog}`。
- `RAW_SQL` 被编译器强制限制为单条 `SELECT/WITH` 查询，并物化为 `CREATE OR REPLACE TABLE <target> AS ...`；这正好让 v1/v2 常量差异落到同一结果表。
- 内置目录实际位于 `orchestration/config/BuiltInOperatorCatalog.java`，先前 service 包路径是旧假设。
- 本地后端不是由仓库日志文件托管，而是 detached screen 会话；运行态故障证据需要从 screen scrollback 或数据库查取。
- 更正：Java 进程的 fd 1/2 实际都指向 `.run-logs/backend.log`；该文件只是被 `rg --files` 的 ignore 规则隐藏，可直接 tail 获取异常栈。
- 后端日志持续显示本地库缺少 `orchestration.pipeline_subscription`，说明运行库存在 orchestration 迁移漂移；高频调度异常淹没了 HTTP 请求栈，需要直接核对数据库表/迁移而不能把 500 归因于本次 Manifest。
- 数据库实证：v1 算子 `verify.version_lock_135715` 已成功注册；但 `orchestration.dag` 仅 14 列，缺少 V12+ 调度字段及 V24 的 `published_version_id/has_unpublished_changes`，创建流水线 500 的根因是本地迁移严重滞后。
- 缺失的订阅表来自 V26；版本锁定验证本身依赖 V24 pipeline_version，因此必须先把 orchestration migration 补齐，否则无法形成发布快照证据。
- `orchestration.flyway_schema_history` 只记录 baseline + V2..V5，确认不是单表误删；仓库当前迁移已到至少 V29。
- 项目 canonical 入口是 `onelake-app/make migrate`，它按 schema 顺序调用 Flyway；先执行该入口验证 checksum/依赖，再决定是否需要项目已有的迁移恢复手段。
- 为恢复本轮所需 schema，最终只对 orchestration 运行 Flyway 且关闭 migrate 前校验；待执行迁移成功，历史 checksum 未 repair。此环境修复应在报告中说明，不把它混同为版本锁定功能结果。
- 补迁移后 orchestration history 到 V32，`pipeline_version`、`pipeline_subscription` 和 DAG 的发布指针/未发布变更字段均存在；后端健康仍为 UP。
- v1 夹具已核对：operator id `297916dc-0366-4e4c-ae07-0a2dd505610c`，ref `verify.version_lock_135715`，目标表 `onelake.dwd.codex_operator_version_lock_135715`，模板输出 `v1/101`。
- 流水线 `209e108d-e90a-4c21-bec7-4cef684edd0f` 已创建，拖入节点 key `verify_version_lock_135715`；标准字段实证为 `taskType=SPARK_SQL`、`engine=SPARK_SQL`、`category=EXEC`、`operatorVersion=1.0.0`，默认 config 带目标表及空输入契约。
- v1 校验 `valid=true`，编译预览为 `CREATE OR REPLACE TABLE onelake.dwd.codex_operator_version_lock_135715 AS SELECT 'v1'..., 101...`。
- 首次发布版本为 pipeline version `1`，version id `3c466f65-ab75-4ed5-a640-8f2a5dcff820`，checksum `ef84233e...faaef86f7`；快照 task 同时冻结 `operatorVersion=1.0.0` 和 `compiled_operator_version=1.0.0`。
- 项目 `make dagster-up` 明确带 `--build` 重建 postgres/user-code/webserver/daemon，适合修复当前旧镜像契约漂移。
- 本地没有 Python base image tag，只有 2026-06-26 的 `onelake-dagster-user-code:latest`；Dockerfile 对运行代码只做一次 `COPY definitions.py`，因此可在不联网的前提下基于现有镜像替换当前 definitions，保留原依赖层。
- 离线刷新后的 user-code 镜像 id 为 `sha256:68d0748b...`；webserver/daemon 已重启，下一次触发将验证新 definitions 的 config schema 是否生效。
- 失败 run `b2e45050-...` 已绑定 pipeline version id `3c466f65-...`，task_run 也持久化 `operator_version=1.0.0`，证明生产触发读取的是发布版本；但 Spark 尚未执行成功，不能据此报告结果复现。
- 实际失败点是旧镜像以 root 在 read-only rootfs 上运行：Dagster telemetry 写 `/root/.dagster`、Ivy 写 `/root/.ivy2` 均失败；生成的 v1 spark-submit 和目标表/SQL已进入执行日志。需让进程使用 compose 已准备的可写 `/home/onelake` 和 `/opt/dagster/dagster_home`。
- 容器实证：旧镜像 `Config.User` 为空、进程 uid=0、HOME=/root、DAGSTER_HOME 未设置；compose 已为 uid 10001 准备可写 tmpfs，但旧镜像没有应用当前 Dockerfile 的 `USER onelake`。离线镜像只需补 `USER 10001:10001`、HOME 与 DAGSTER_HOME 配置。
- 修正后容器 uid/gid=10001，`/home/onelake` 与 `/opt/dagster/dagster_home` 写入测试通过，user-code healthy；后续失败若有将不再由 read-only root home 引起。
- 第二次 Spark stderr 为 `basedir must be absolute: ?/.ivy2/local`：仅设置数字 USER/HOME 不足，JVM 仍需 `/etc/passwd` 中 uid 10001 对应的 home；当前 Dockerfile 的 `useradd` 正是缺失层。
- 补用户后 `getent passwd` 与 JVM `user.home` 均返回 `/home/onelake`，Ivy 缓存路径前置问题已消除。
- v1 成功 run `3e53af7f-249e-40e0-9467-562eaf6970e3`：pipeline_version_id=`3c466f65-ab75-4ed5-a640-8f2a5dcff820`，task `operator_version=1.0.0`，rows_written=1，artifact=`table:onelake.dwd.codex_operator_version_lock_135715`。
- Trino 实际查询结果为 `{"version_marker":"v1","payload_value":101}`；这作为后两阶段“结果不变/升级变化”的基线。
- 算子 v2 发布成功，最新版本/Manifest 均为 `2.0.0`，模板输出 `v2/202`；DEV 节点已显式改绑 v2，DEV compile-preview 也生成 v2 SQL。
- 未重新发布时，DAG 仍为 PUBLISHED、`publishedVersionId=3c466f65-...`、版本列表仍只有 version 1/checksum `ef84233e...`，同时 `hasUnpublishedChanges=true`；这是第二次生产运行的关键前置状态。
- 未重发生产 run `68673a21-f3a5-48e2-b756-ab4d8787b1d4` 成功，绑定的 pipeline_version_id 仍为 version 1 的 `3c466f65-...`，task_run `operator_version=1.0.0`、rows_written=1。
- 该 run 后 Trino 结果仍是 `v1/101`，与首个成功 run 完全一致；DEV 已是 v2 但未污染生产执行，版本锁定与可复现性得到真实数据面证明。
- 重新发布生成 pipeline version 2，id=`785f1922-ee1e-4737-99ea-e75ff9e89d0b`，checksum=`d9a83aed...ba1564`，发布指针已切换且 `hasUnpublishedChanges=false`。
- version 2 快照冻结 `operatorVersion=2.0.0`、`compiled_operator_version=2.0.0` 和 v2/202 SQL；1→2 diff 只显示该节点 config 与 operatorVersion 从 v1 变为 v2，图结构无变化。
- 重发后 run `14cbc940-4dfe-45e9-851b-b312f18de64d` 成功，绑定新 pipeline_version_id `785f1922-...`，task_run `operator_version=2.0.0`、rows_written=1。
- 最终 Trino 结果切换为 `v2/202`；只有重新发布后生产结果才升级，符合预期。
- 汇总数据库审计显示三个成功 run 依次绑定 pipeline/operator `1/1.0.0`、`1/1.0.0`、`2/2.0.0`，每次写入 1 行同一 artifact 表；两个发布快照 checksum 不同且均保留。
- 测试夹具暂时保留用于复现：operator `verify.version_lock_135715`、pipeline `209e108d-...`、结果表 `iceberg.dwd.codex_operator_version_lock_135715` 及运行历史。
- 最终环境复核：Hive Metastore、Spark master/worker、Dagster webserver/daemon 均 Up，Dagster Postgres 与 user-code healthy；本轮验证未修改业务代码，只更新文件规划日志。
- 运行中的 OpenAPI 已包含 operator register/version、from-operator、validate/status/trigger 等 G2 接口，说明后端进程已加载本轮实现。
- `PipelineController#trigger` 的 PROD 路径默认不传 `useVersionId`；`OrchestrationService` 随后选择 `dag.publishedVersionId` 并通过 `PipelineSnapshotService.loadExecutionSnapshot` 构造运行任务，代码边界符合锁定验证前提。


## 2026-07-14 G2 算子拖入生成可执行节点

### 二轮 Review 事实与修复

- `PipelineService#createTaskFromOperator` 的安装检查只接收 ref；显式安装记录即使固定了旧版本，请求仍可读取并锁定其他版本。
- `orchestration.operator` 只按 `(operator_ref, scope, tenant_id)` 唯一，同一租户可同时存在 CUSTOM 与 TENANT_PRIVATE 同 ref；返回 `Optional` 的跨 scope 查询会在多行时失败。
- 通用 `updateTask` 当前对请求中未变化的既有算子绑定也要求 ACTIVE，导致算子废弃后无法继续编辑旧锁定节点。
- Manifest 校验未拒绝 `examples` 中的 null 条目，默认配置直接读取首项会触发空指针；新写入应拒绝，历史异常快照读取应容错。
- 已新增 `OperatorService#getInstalledManifest`，统一 ACTIVE/安装/固定版本/精确 Manifest 校验；创建和改绑路径不能再绕过 pinnedVersion。
- 同层同名 Repository 查询已改为多行返回并按稳定 ID 选择；Palette、`getManifest` 和创建命令保持同一身份解析。
- 更新请求仅在新增或更换算子绑定时要求 ACTIVE；携带原锁定绑定时继续按精确 Manifest 校验，允许废弃算子的既有节点编辑。
- Manifest 校验已拒绝 null example，默认配置读取同时防御历史异常快照；相关聚焦与全模块测试均通过。
- 当前 HEAD `e9b772b` 已完成 G1：`PipelineCompileService` 对带 `operator_ref/operator_version` 的 `SPARK_SQL` 节点按锁定 Manifest 生成 SQL。
- `OperatorService#getManifest(tenantId, ref, version)` 已提供租户可见性和精确版本校验，可直接作为 `createTaskFromOperator` 的 Manifest 来源。
- 现有 `GET /api/v1/orchestration/operators` 返回“平台内置 + 租户自有 + 显式安装”的可见集合，并包含 `installed` 标记；Palette 需要独立的已安装过滤语义，避免未安装的租户私有算子混入。
- `PipelineTask` 已有 `category/operatorRef/operatorVersion/position/config` 字段，节点仍可沿标准 `pipeline_task` 与 G1 编译链运行，无需新表或迁移。
- Manifest 目前没有独立的 defaultConfig 字段；内置算子在 `paramsSchema.properties` 中未声明 `default`，但 `examples[0].params` 提供确定性示例值。默认 config 取值口径需要在设计阶段明确。
- 用户确认默认 config 合并规则：优先 `paramsSchema.properties.*.default`，缺失时回退 `examples[0].params`。
- 用户确认采用独立命令接口方案：Palette 使用专门的已安装算子 GET，拖入使用专门的 from-operator POST，Manifest 到节点的映射权留在 `PipelineService`。
- G2 后端已实现：`OperatorService#listInstalledOperators/isInstalled` 统一 Palette 与写入判定；`PipelineService#createTaskFromOperator` 生成 `SPARK_SQL/EXEC` 标准节点并保留 `_operator_contract` 快照。
- 通用 `/tasks` 的 operatorRef 绑定也复用安装判定，不能绕过 Palette 直接绑定未安装算子。
- 输入算子 `input.ods_table` 已通过单测完成“创建节点 → Schema default/示例合并 → G1 生成 Spark SQL → executable=true”闭环。
- Review 修复后，Palette 与创建命令共享完整 G1 兼容策略：除模板白名单外，只接受零输入源算子或单个 `ONE` 输入端口，并拒绝需要上游输入的伪源模板。
- Palette 按 `operatorRef` 和“租户自有 → BUILTIN → 显式安装”优先级选唯一候选；显式安装同名候选使用稳定 ID 顺序，与创建时 Manifest 解析一致。
- 已安装列表以 `pinnedVersion` 优先加载有效 Manifest；固定版本快照缺失或不兼容时不进入可拖入集合。
- 编译图端口从 SQL 生成阶段已经读取的锁定 Manifest 构造，自定义端口名可编译，且不信任可编辑的 `_operator_contract` 快照。

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

## 2026-06-22 流水线与算子市场阶段二后端市场底座实施发现
- 算子市场后端已从“无表、无 API、前端 mock”推进到 orchestration schema 内的一等公民：`operator` 保存算子注册信息，`operator_version` 保存 Manifest 快照，`operator_install` 保存租户安装/版本锁定。
- 内置算子没有写成散落 SQL，而是集中在 `BuiltInOperatorCatalog`，启动 seeder 幂等写入 65 个 `BUILTIN + SQL_DBT` manifest；迁移只负责建表，Flyway 关闭或表未准备好时 seeder 会跳过，不阻断后端启动。
- Manifest 校验覆盖 `operatorRef` 小写命名、semver、category/scope/compileTarget、paramsSchema、outputSchema、template，以及 `QUALITY_GATE` 必须有 `policy.actionOnViolation`，对应设计方案的注册前自校验。
- 市场 API 已提供列表、详情、validate、注册、发布版本、更新元信息、安装/锁版；列表会合并 BUILTIN、当前租户 CUSTOM/TENANT_PRIVATE 和已安装算子，按分类、scope、关键词过滤。
- 当前阶段二后端仍不做图级编译、端口连线校验、字段闭合推导和资源组存在性校验；这些属于阶段三 `OperatorCompiler` 和画布运行态增强，不能把市场 API 误说成“可执行流水线编译器”。
- 验证通过：`mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 覆盖 65 内置 seed、质量门禁策略校验、注册、安装版本校验和列表 manifest；`git diff --check` 通过。

## 2026-06-22 流水线与算子市场阶段二前端真实 API 接入发现
- `OperatorMarket.tsx` 已从 5 条 mock 卡片切到 `OperatorAPI.listOperators`，真实返回 65 个内置算子；页面仍保留原来的企业控制台卡片网格和详情弹窗结构。
- 前端类型新增 `OperatorManifest/Operator/OperatorVersion/OperatorValidationResult`，`DagNode` 同步补可选 `operatorRef/operatorVersion/config`，与后端和 ODS->DWD 2.5 节点契约对齐。
- 页面支持 scope 分段筛选、category 下拉筛选、关键词搜索、刷新、详情加载、版本展示、参数 schema 展示和安装/锁定版本调用；加载、错误、空态复用 `StateView`。
- 初次浏览器验证时点击安装触发 AntD 静态 `message` warning，已改为 `AntApp.useApp()`，复测 `/orchestration/operators` 控制台 0 error。
- 真实网络验证：登录态下 `GET /api/v1/orchestration/operators`、`GET /operators/input.dwd_table`、`POST /operators/input.dwd_table/install` 和安装后的列表刷新均返回 200。
- 阶段二至此满足设计方案中的“可浏览/搜索/安装的算子市场”；仍未完成阶段三的图级编译、DWD 默认算子链调用市场 manifest、Dagster dbt 运行和质量/血缘回写。

## 2026-06-22 流水线与算子市场阶段三前置核对发现
- 当前代码已经存在 Dagster `onelake_dbt_model_run` job，`DwdModelDagsterClient` 也已能发起 job、查询 run status，`DwdRunArtifactReader` 已读取 dbt `run_results.json` 并触发 loaded/failed 事件；阶段三不应重复新增 Dagster job。
- `module-modeling` 当前不依赖 `module-orchestration`，DWD 服务读取 Catalog 也采用 `JdbcTemplate` 跨 schema 查询；阶段三最小切片应继续通过稳定表契约读取 `orchestration.operator/operator_version`，避免 Java 模块依赖倒挂。
- `DwdModelService.operatorGraphDefinition` 仍是硬编码节点类型和名称，节点缺少 `operatorRef/operatorVersion/manifest`，因此 DWD 2.5 的 operator graph 还没有真正消费算子市场 Manifest。
- 下一轮可落地切片：DWD compile 阶段解析默认内置算子 manifest，校验 `compileTarget=SQL_DBT`、category 匹配，并把 manifest 快照嵌入生成的 operator graph；市场表缺失或 manifest 不合法时阻断 compile。
- 实施后发现：`bootstrap` 运行时依赖本地 Maven 仓库里的模块 SNAPSHOT jar，源码修改后仅重启后端不会生效；需要先 `mvn -q -pl module-modeling -am install -DskipTests -Djacoco.skip=true` 再重启。
- 真实 API 复测确认 `POST /api/v1/modeling/models/{id}/compile` 返回的 operator graph 中，除内部 `DBT_MODEL` 节点外，DWD 默认链已写入 `input.ods_table`、`transform.rename_columns`、`govern.drop_required_missing`、`gate.not_null`、`output.iceberg_table`，且所有 manifest `compileTarget=SQL_DBT`。
- 数据库反查 `orchestration.dag.definition.nodes[*].operatorRef` 与 API 返回一致，说明 manifest 接入已落库，不只是响应 DTO 临时拼装。

## 2026-06-23 流水线与算子市场阶段三图级校验实施发现
- `POST /api/v1/orchestration/operators/graph/validate` 已成为 DAG 画布和 DWD 向导可复用的后端校验入口，支持直接 graph、`operatorGraph` 和 `definition.operatorGraph` 多种请求形态。
- 图级校验会按租户可见范围解析 `operatorRef`，用指定 `operatorVersion` 或 latest version 读取 Manifest 快照，并校验 Manifest 自身、category/nodeType、`compileTarget=SQL_DBT`、required params、输入端口基数和 DAG 环路。
- DWD operator graph 中的 `DBT_MODEL` 是系统运行节点，不属于市场算子；校验器允许它没有 `operatorRef`，但会返回 warning，避免前端或后续编译把它误当成缺配置错误。
- 真实正向验证结果：阶段 55 生成的 DAG `94f21184-752f-40ea-9c65-1a5ee00b3699` 校验 `ok=true`，市场算子 refs 为 `input.ods_table`、`transform.rename_columns`、`govern.drop_required_missing`、`gate.not_null`、`output.iceberg_table`。
- 真实反向验证结果：修改 `definition.operatorGraph` 删除 `transform_mapping.config.mapping` 并添加 `output_dwd -> input_ods` 环路后，接口返回 `ok=false`，错误包含环路、输入边异常和缺少必填参数。
- 重要运行态经验：DAG definition 同时保留顶层 `nodes/edges` 和 `operatorGraph` 快照；图级校验优先校验 `operatorGraph`。测试反例必须修改这个层级，否则会误以为校验漏报。
- 与前几轮一致，bootstrap 运行态依赖本地 Maven 仓库中的模块 SNAPSHOT；新增 controller/service 后需要先 `mvn install` 并进程级重启，单纯 DevTools 热重载可能看不到新 OpenAPI 路由。

## 2026-06-23 流水线与算子市场阶段三前端图级校验发现
- `DagCanvas` 原先的“DAG 校验结果”是静态 mock 弹窗，保存按钮没有动作；阶段 57 已将校验按钮和保存前置检查都接到真实 `OperatorAPI.validateGraph`。
- 画布从后端 DAG 加载时应优先使用 `definition.operatorGraph`，否则 DWD 编译写入的 Manifest graph 会被顶层兼容字段覆盖，导致前端校验结果和后端编译链路不一致。
- 新建画布仍是原型节点，但现在会映射到内置算子 refs：`input.ods_table`、`govern.drop_required_missing`、`mask.partial`、`output.iceberg_table`，因此也能验证市场 Manifest、required params 和端口基数。
- 浏览器验证中，“校验”和“保存”各发起一次 `POST /api/v1/orchestration/operators/graph/validate`，均返回 200；弹窗显示后端成功结果，控制台只有既有 React Router v7 future warning，没有新错误。
- 当前保存仍只做“校验通过，可保存草稿”的前置检查，不会持久化 DAG；下一步应补后端 update/save 契约和前端保存后重新加载验证。

## 2026-06-23 流水线与算子市场阶段三 DAG 草稿保存发现
- 后端原先只有 `POST /api/v1/orchestration/dags` 创建，没有 `PUT` 更新；画布保存无法持久化已有草稿。本轮新增 `PUT /api/v1/orchestration/dags/{id}`，并在 service 内递增 DAG version。
- `getDag`、`triggerDag`、`runs` 原先只按 id 查询；本轮统一改为 `id + tenantId` 查询，避免跨租户读取、触发和查询运行历史。
- 前端保存路径现在是“图级校验通过 -> create/update DAG -> 新建时替换 URL 为真实 DAG id”；保存 payload 同时保留顶层 `nodes/edges` 与 `operatorGraph`，兼容画布读取和 DWD 编译读取。
- 浏览器实证：新建画布保存后，真实创建 DAG `a3504d0f-3a4f-4462-bba3-5d55f6857e40`；重新导航同一 URL 后通过 GET 重新加载；再次保存会走 PUT，并把 DB version 从 1 增到 2。
- DB 反查确认该 DAG `definition.kind=operator_graph`，`definition.operatorGraph.nodes` 为 4 个节点；这说明保存结果已落库，不只是前端路由 state。
- 浏览器验证中出现一次 Keycloak token endpoint 400 资源错误，但所有 DAG 接口均为 200，当前判断为既有认证刷新噪声；后续如频繁出现，应单独排查 auth refresh 流。

## 2026-06-23 流水线与算子市场阶段三列表真实化发现
- `/orchestration/pipelines` 原先展示 3 条前端硬编码流水线，无法看到第 58 轮保存到 `orchestration.dag` 的真实草稿；阶段 59 已切换为 `OrchestrationAPI.listDags()`。
- 列表页现在真实展示 DAG name、dagster job、version 和 enabled/DRAFT 状态；因为后端当前没有 env/lastRun 聚合字段，页面不再伪造生产环境或最近运行时间，最近运行暂以 `-` 表示。
- 草稿 DAG 的触发按钮被禁用，避免 disabled DAG 点击后才由后端报错；这比展示可点击按钮更贴近当前执行边界。
- 浏览器实证：`GET /api/v1/orchestration/dags` 返回 200，列表显示 4 条本地 DAG，包括 `order_pipeline` v2；点击该行“打开画布”进入真实 UUID 画布，控制台 0 error。
- 下一步运行实例页仍需真实化；当前后端只有按 DAG 查询 runs 的接口，没有全局 runs 分页，需先决定是补全局 API，还是复用 `common.running_task` 投影作为运行实例入口。

## 2026-06-23 流水线与算子市场阶段三运行实例真实化发现
- `RunInstances` 已从 3 条静态 mock 改为读取 `orchestration.job_run` 的真实分页接口，适合作为运行历史页；`common.running_task` 继续用于全局任务条近期/活跃状态，不替代完整历史。
- 新增 `/api/v1/orchestration/runs` 采用“当前租户 DAG ids -> job_run in ids”的过滤方式，避免 `job_run` 表缺少 tenant_id 时产生跨租户泄露。
- `JobRunDTO` 带 `dagName/dagsterJob`，前端无需再维护流水线名称 mock，也避免运行实例列表额外调用 DAG 详情。
- 原页面的“日志”按钮跳到了固定采集任务 `/integration/sync-tasks/st-001`，这是跨模块假入口；阶段 60 已移除，保留“打开流水线”作为当前真实可用动作。
- 浏览器实证：`GET /api/v1/orchestration/runs?page=0&size=20` 返回 200，响应体包含本地验证 run `codex-stage60-run-001`、DAG `order_pipeline`、`status=SUCCESS`，页面表格同步展示，控制台 0 error。
- 运行态再次确认：`spring-boot:run -pl bootstrap` 会使用本地 Maven 仓库的模块 jar；新增 controller 后必须先 `mvn install` 再重启，否则会出现源码已改但 HTTP 仍 404 的旧 jar 假象。
- 阶段 61 应回补流水线列表的“最近运行”字段。现在 run 历史已真实化，继续让列表显示 `-` 会造成同一模块内信息不一致。

## 2026-06-23 流水线与算子市场阶段三最近运行聚合发现
- `DagDTO.lastRun` 已复用 `JobRunDTO`，避免流水线列表另造一套最近运行摘要字段；同一个 run 在运行实例页和流水线列表中字段一致。
- `listDags` 现在按当前租户 DAG 列表逐条补最近运行，适合当前控制台小规模列表；如果后续 DAG 数量增长，应改为 repository 层批量查询每个 DAG 的 latest run，避免 N+1 查询。
- 前端流水线列表不再显示全量 `-`；有真实运行历史的 `order_pipeline` 展示 `SUCCESS`、`codex-stage60-run-001` 和开始时间，其余无运行历史的 DAG 仍显示 `-`。
- 浏览器响应体确认 `lastRun` 来自后端：`order_pipeline.lastRun.status=SUCCESS`、`dagsterRunId=codex-stage60-run-001`，页面快照与响应一致。
- 下一步触发链路仍有缺口：`triggerDag` 目前是 Dagster 成功后才保存 `job_run`，因此 Dagster 不可用时会丢失失败运行实例；阶段 62 应改为先落 `QUEUED` 再更新终态。

## 2026-06-23 流水线与算子市场阶段三触发失败可观测发现
- `triggerDag` 必须先落 `QUEUED` run 再调用 Dagster，否则 Dagster 不通、GraphQL 返回非成功 union 或本地解析不到 runId 时，运行实例页没有任何失败证据。
- 业务异常也会触发事务回滚；本轮通过 `@Transactional(noRollbackFor = BizException.class)` 保留 `Dagster 未返回 runId` 场景下的 `FAILED` run。
- 前端触发失败后仍需要刷新 DAG 列表，否则后端 `lastRun` 已更新但用户短时间内看不到失败最近运行。
- 运行时实证：临时启用 `order_pipeline` 后触发返回 400，DB 最新 run `b99b8175-bdc8-452d-b58d-0bab92081547` 为 `FAILED`，流水线列表和运行实例页均能看到该失败记录。
- 验证后已将 `order_pipeline` 恢复为 `enabled=false`，避免本地草稿 DAG 继续显示为可触发。
- 下一步真实表达缺口：`enabled` 不等于“可执行”。`sql_workbench_draft` 是草稿占位作业，应由后端返回触发就绪状态和阻断原因，前端据此禁用触发按钮。

## 2026-06-23 流水线与算子市场阶段三触发就绪真实表达发现
- `enabled` 只能表达 DAG 是否被启用，不能表达执行前置条件是否满足；后端需要单独返回 `triggerable/triggerBlockedReason`，否则前端会把草稿占位作业误展示为可触发。
- `sql_workbench_draft` 是画布/SQL 工作台草稿占位 job，未绑定真实 Dagster 作业时应在调用 Dagster 前拦截，不应创建 `job_run`。
- 阶段 62 的失败可观测用于外部 Dagster launch 失败；阶段 63 的触发就绪用于本地前置条件阻断，两者语义不同，不能混在同一类失败运行里。
- 浏览器实证：`order_pipeline enabled=true + dagsterJob=sql_workbench_draft` 时，API 返回 `triggerable=false` 和 `当前为画布草稿，尚未绑定可执行 Dagster 作业`，页面展示 `待绑定` 且触发按钮禁用。
- 恢复 `order_pipeline enabled=false` 后，页面展示 `草稿`，最近运行仍保留阶段 62 的 `FAILED` 历史，说明触发就绪状态不会抹掉运行历史。
- 下一轮应进入真实执行闭环核对：`onelake_dbt_model_run` Dagster job、dbt 产物、run 状态回写和质量/血缘回写仍需与方案阶段三逐项对齐。

## 2026-06-23 流水线与算子市场阶段三 DWD DAG 真实触发发现
- 当前 Dagster code location 已有 `onelake_dbt_model_run`，不再是早期只有 reconciliation job 的状态；阶段三执行闭环的当前缺口不是 job 不存在，而是编排触发没有携带 DWD `runConfigData`。
- `DwdModelService.run` 已有专用 DWD 运行链路，会创建 `modeling.model_run` 并传 `run_dwd_model` 配置；编排 `triggerDag` 原先只传 job name，不能直接运行 DWD dbt job。
- 本轮在 orchestration 模块内用 JDBC 写 `modeling.model_run`，避免 Java 模块依赖倒挂，同时让 pipeline run 和 model run 共享同一个 Dagster run id。
- 真实触发结果：DAG `94f21184-752f-40ea-9c65-1a5ee00b3699` 触发返回编排 run `3f2ff034-0e63-4cf4-aac1-075399906580`；`orchestration.job_run` 与 `modeling.model_run` 均写入 Dagster run `aa4375e6-1b6c-4dd9-90cc-bb0fbccda024`。
- Dagster GraphQL 确认 run 状态为 `STARTED`，tags 包含 `onelake.model_run_id=0b962b9b-b7f7-446c-8b4f-47e8d29abc2d` 和 `onelake.orchestration_run_id=3f2ff034-0e63-4cf4-aac1-075399906580`。
- 下一轮缺口：状态刷新/终态回写尚未接入 orchestration run 历史；当前 run 会停留在 `RUNNING`，需要查询 Dagster run 状态并同步到 `job_run` 与 `model_run`。

## 2026-06-23 流水线与算子市场阶段三 Dagster 运行状态刷新发现
- `DagsterClient.getRunStatus` 接入后，运行列表读取可将 Dagster `SUCCESS/FAILURE/CANCELED` 同步回 `orchestration.job_run`，流水线列表的 `lastRun` 与运行实例页共用同一条终态数据。
- DWD DAG 不能只同步 `orchestration.job_run`；否则编排页显示成功而建模 run 仍停在 `RUNNING`。Stage 65 已先用 JDBC 兜底同步 `modeling.model_run` 基础状态。
- PostgreSQL 对 `java.time.Instant` 的 JDBC 参数类型推断不稳定，写入 `started_at/finished_at` 时应显式转为 `Timestamp`。
- 真实验证：编排 run `3f2ff034-0e63-4cf4-aac1-075399906580` 和对应 DWD model run 最终都同步为成功，Dagster run 为 `aa4375e6-1b6c-4dd9-90cc-bb0fbccda024`。

## 2026-06-23 流水线与算子市场阶段三 DWD 尾链一致性发现
- 仅在 orchestration 模块用 JDBC 改 `modeling.model_run.status` 会绕过 `DwdModelService.refreshRunStatus` 的 artifact 解析和 `modeling.model.loaded` 事件发布，导致 Catalog/Quality/血缘尾链缺失。
- 抽取 common `DwdModelRunSynchronizer` 后，orchestration 可以在不反向依赖 modeling Java 模块的前提下复用建模侧完整终态逻辑；缺失同步器时仍保留 JDBC 基础状态兜底。
- 真实验证：编排触发 run `34b4f37a-53ab-4d18-ae8c-8acbe1ecd724` 后，DWD model run `f29186c4-5448-4e24-8add-42a8a0a0bbae` 写入 `rows_written=10` 和 `artifacts_path=target/run_results.json`。
- `modeling.model.loaded` 已被 Catalog 和 Quality 消费，Catalog 资产 `dwd.dwd_trade_operator_manifest_df` 行数为 10、质量分为 100，血缘边从 `ods.ods_codex_orders` 指向 DWD 表。
- Quality 侧生成 `DBT_BUILD/NOT_NULL/UNIQUE` 三条通过结果，说明编排触发 DWD 不再绕开质量门禁尾链。

## 2026-06-23 流水线与算子市场阶段三 DWD 资源观测发现
- 后端 `DwdModelRunDTO` 和前端 `DwdModelRun` 已有资源画像、扫描量、成本和重试字段；缺口在资产详情页没有展示，用户无法从 DWD 运行历史看到资源画像。
- Stage 67 已在 DWD 模型 tab 的最近运行摘要展示 `resourceGroup/computeProfile/scan/cost/retry`，不新增后端契约。
- 浏览器实证：资产 `dwd.dwd_trade_operator_manifest_df` 的 DWD 模型区显示 `SUCCEEDED`、`MANUAL`、`写入 10`、`default`、`trino-small`、`扫描 - / 成本 - / 重试 0`，并显示 Dagster run 与 dbt artifact。

## 2026-06-23 流水线与算子市场阶段三画布算子面板真实化发现
- `DagCanvas` 左侧算子面板原先仍是 7 个硬编码原型项，与算子市场 65 个内置 Manifest 已落库的事实不一致。
- Stage 68 已改为调用 `OperatorAPI.listOperators()`，按市场 category 分组展示可见算子；这满足标准设计中“画布算子面板从 OperatorAPI 取分类列表”的当前最小切片。
- 浏览器实证：DWD DAG 画布网络请求 `/api/v1/orchestration/operators` 返回 200，页面左侧展示标准化、关联、加密、聚合、输出、输入、脱敏、治理、质量门禁、转换等完整市场分类和内置算子名称。
- 下一轮阶段四缺口：后端 validate/register/publish/install API 已有，但前端还没有自定义算子注册/发布入口，也没有把 Manifest 校验结果作为提交前置。

## 2026-06-23 流水线与算子市场阶段四自定义算子注册发布发现
- 后端阶段二已经具备 `validate/register/publish/install` API，阶段四第一缺口在前端没有创建自定义 Manifest 的入口，导致 CUSTOM/TENANT_PRIVATE 范围只能通过 API 手工构造。
- `OperatorMarket` 注册/发布版本入口必须把 Manifest 校验作为提交前置，否则用户能看到“注册成功”但后续图级校验或编译失败，问题会延迟到画布侧暴露。
- 浏览器实证创建了本地验证算子 `custom.codex_stage69_ui`，发布到 `1.0.1` 后市场列表展示 latestVersion `1.0.1`；API 冒烟另验证了 `custom.codex_stage69_phone` 的 validate/register/publish/list。
- 注册弹窗默认值需要等 Form 挂载后写入；否则初次点击“仅校验”会读到 undefined。发布版本 Modal 也需要在关闭详情 Modal 后打开，避免 AntD Modal 层级拦截点击。
- 这轮让算子市场达到“自定义算子可注册、可校验、可版本化发布”的最小工程化入口；仍未覆盖 Spark/Python 运行时、多租户私有分享治理和废弃/下架工作流。

## 2026-06-23 流水线与算子市场阶段四画布添加与属性动态化发现
- Stage 71 已把左侧真实市场算子从“展示列表”推进到“可添加节点”：点击市场项会创建画布节点，携带 `operatorRef/operatorVersion/config`，并在目标算子需要输入时从当前选中节点自动连一条边。
- 新节点默认 `config` 来自 Manifest required params，这样添加后能立即进入后端图级校验，而不是先产生必填参数全缺失的无效节点。
- Stage 72 将右侧属性面板改为读取选中算子的 Manifest：展示 `operatorRef/version/category/compileTarget/inputPorts`，并按 `paramsSchema.properties` 动态生成字符串、数字、布尔、数组和对象输入。
- 浏览器实证：DWD DAG 中 `input.ods_table` 的 `sourceFqn` 字段由 `paramsSchema` 动态生成；把值改成 `ods.codex_stage72_canvas_param` 后点击校验，`POST /api/v1/orchestration/operators/graph/validate` 请求体包含更新后的 `config.sourceFqn`，后端返回 200。
- 验证时未保存临时参数和新增节点，避免污染真实 `94f21184-752f-40ea-9c65-1a5ee00b3699` DWD DAG；当前保存链路代码共用同一份节点状态和 `buildDagDefinition`。
- 剩余阶段四缺口仍在 X6 真实拖拽/连线编辑、连接端口可视化、Spark/Python compileTarget 的运行时编译与部署契约，以及更完整的算子生命周期治理。

## 2026-06-23 流水线与算子市场阶段四拖拽定位发现
- Stage 73 对照标准方案后确认：当前最适合继续迭代的是画布交互能力，而不是直接上 Spark/Python 运行时；后者会牵动编译器、Dagster op 和部署契约，风险更高。
- Stage 74 已补齐节点拖拽定位最小闭环：节点可在现有 SVG 画布内拖动，连线按最新 `x/y` 实时重绘，右侧属性面板显示当前坐标。
- `buildValidationGraph` 已把 `x/y` 写入每个节点；这让拖拽位置进入图级校验和后续保存 payload，不再只是 DOM 视觉状态。
- 浏览器实证：`input.ods_table` 节点拖动后，右侧坐标显示 `x 152 / y 158`；图级校验请求体中的 `input_ods` 同步带 `x=152,y=158`，后端返回 200。
- 本轮仍没有宣称完整 X6 完成：当前是低风险原生 pointer 拖拽，保留现有保存/校验链路。完整 X6 Graph 实例、端口连线编辑、删边、连线合法性可视化仍应作为阶段 75 单独推进。

## 2026-06-23 流水线与算子市场阶段四端口连线发现
- Stage 75 继续保留现有 SVG 画布，而不是一次性迁移 X6 Graph；这样可以先补齐端口、删边和合法性可视化，同时不破坏已稳定的保存/图级校验链路。
- 边模型需要显式持有 `id/sourcePort/targetPort`，否则前端无法选中单条边，也无法区分 JOIN/MANY 类算子的不同输入端口。
- 前端本地校验不能替代后端图级校验，但应提前反馈基础结构错误：缺失节点、自环、目标端口不存在、`ONE` 端口多入边、重复边和环路都可以在画布上即时标红。
- 浏览器实证：合法边删除/重连后边数恢复为 5；新增指向同一 `ONE` 输入端口的非法边后出现红色无效连线，删除后恢复真实状态。
- 后端图级校验已能接收含 `sourcePort/targetPort` 的边 payload；DWD DAG 校验返回 200 且 `ok=true`，仅保留 `dbt_model` 系统节点 warning。

## 2026-06-23 流水线与算子市场阶段四算子生命周期发现
- 后端 `OperatorAPI.updateOperator` 已支持状态更新，前端缺口在市场详情没有废弃/恢复入口，也没有在废弃状态下阻断安装和使用。
- 生命周期治理应限制在 `CUSTOM/TENANT_PRIVATE` 范围；`BUILTIN` 算子不展示废弃按钮，避免内置标准算子被误操作。
- `DEPRECATED` 不是删除：列表和详情都应继续显示历史版本与 Manifest，但安装/锁定和使用入口必须 disabled，并给出真实状态反馈。
- 浏览器实证：`custom.codex_stage69_ui` 废弃时 `PUT /api/v1/orchestration/operators/custom.codex_stage69_ui` 请求体为 `{"status":"DEPRECATED"}` 且返回 200；详情显示“已废弃”，安装/使用 disabled。
- 恢复时同一路径请求体为 `{"status":"ACTIVE"}` 且返回 200；详情恢复“可用”，安装/使用重新启用。验证结束已恢复测试算子为 `ACTIVE`。
- 下一轮流水线方向应进入 Spark/Python compileTarget 的真实边界核对；不能只在 Manifest 表单中允许选择 SPARK/PYTHON，却没有 Dagster op、编译器和部署契约支撑。
- Stage 79 复核时发现 Stage 78 仍有缺口：后端 `listOperators` 过滤了非 ACTIVE，导致废弃算子不能在列表/统计中长期展示；`installOperator` 也没有后端拒绝废弃算子。已补齐列表可见、安装拒绝和图级校验拒绝废弃节点。

## 2026-06-23 流水线与算子市场阶段四 Spark/Python 扩展边界发现
- 方案 §2.5 明确 `SPARK/PYTHON` 是后续扩展点，当前第一批执行链路仍是 SQL_DBT + `onelake_dbt_model_run`，所以不能因为枚举已存在就让图级校验通过。
- Manifest 自校验可以支持扩展态，但要按 compileTarget 校验真实契约：SPARK 需要 `SPARK_SQL/PYSPARK` 模板与 `resourceHint.engine=SPARK`；PYTHON 需要 `template.kind=PYTHON`、`entrypoint` 和可选 requirements 数组。
- 前端注册表单原来只有“模板类型 + SQL/dbt 模板”，无法表达 PYTHON entrypoint 或 SPARK resourceHint；Stage 79 已改为完整 `template JSON` 与 `resourceHint JSON`。
- 真实 API 实证：合法 SPARK Manifest `ok=true`，但返回“仅完成 Manifest 契约校验，图级执行仍需先接入 Dagster op 与部署契约”的 warning；缺少 resourceHint 的 SPARK Manifest `ok=false`。
- 下一步不能直接做“Spark/Python 已可运行”，应先设计 Dagster Spark/Python op、依赖隔离、镜像/requirements 管理和资源组注册，再放开图级执行。

## 2026-06-23 流水线与算子市场阶段四后端端口级校验发现
- Stage 75 已在前端做端口连线和本地合法性可视化，但后端图级校验仍只看节点总入边数，JOIN 这类多输入算子无法识别 `left/right` 是否接错。
- Stage 80 已将后端入边校验深化到 `targetPort`：单输入端口兼容旧边格式，多输入端口必须声明 `targetPort`。
- JOIN API 实证：`join.inner` 缺 targetPort 时 `ok=false`；两条边都指向 `left` 时 `ok=false`；分别指向 `left/right` 时 `ok=true`。
- 这轮仍只覆盖端口基数与端口名称，不覆盖字段 schema 闭合；阶段 81 应先确认字段 schema 的真实来源，再实现字段引用/输出推导校验。

## 2026-06-23 流水线与算子市场阶段四字段 schema 与治理校验发现
- 字段 schema 的真实来源是 `catalog.asset.columns`、`modeling.data_model_column_mapping` 与 DWD DAG definition 中的 `outputColumns/operatorGraph`；不能在 graph 无 `sourceColumns/inputColumns/outputColumns` 时伪造完整闭合。
- 本轮后端图级校验只在有字段事实时做强校验：`sourceColumns/inputColumns` 可作为上游字段；`transform.rename_columns` 的 `mapping/mappings` 可推导目标字段；`output_dwd.config.columns` 和 graph `outputColumns` 可作为输出自一致检查。
- 字段引用校验覆盖常见配置键：`column/columns/requiredColumns/keys/groupBy/partitionBy/orderBy/uniqueKey/incrementalColumn` 和 mapping 源字段。真实 API 已能挡住 `quality_gate` 引用 `missing_col`。
- 敏感字段治理只基于 `classification/piiType/suggestLevel` 或显式 `sensitiveColumns`，不会凭字段名猜测；L3/PII 字段透传到输出且未经过 `MASK/ENCRYPT` 会报错，经过 `mask.partial` 后通过。
- DWD 生成图当前会用 `mask.partial` + `columns` 表达批量敏感字段治理，这与内置 Manifest 的单字段 `column` 参数不完全一致；Stage 81 在校验层兼容该批量写法，后续真正逐字段模板渲染时仍应进一步收敛。

## 2026-06-23 流水线与算子市场阶段四资源契约校验发现
- 当前没有 OneLake 业务侧资源组注册表；数据库实证 DWD DAG 使用 `engine=TRINO_DBT`、`resourceGroup=default`、`computeProfile=trino-small`。
- Stage 82 采用静态受控资源契约做第一层后端保护：`TRINO_DBT` 支持 `default/rg-default`，对应 `trino-small/trino-medium/trino-large`；`SPARK/PYTHON` 仍只作为 Manifest 扩展态资源 hint，不放开图级运行。
- Manifest 校验会拒绝未知 `defaultResourceGroup`；graph 校验会拒绝未知 `resourceGroup` 或不属于当前 resourceGroup 的 `computeProfile`。
- 真实 API 实证：`warehouse-xl/TRINO_DBT` 与 `spark-large/default` 均返回 `ok=false`；`TRINO_DBT/default/trino-small` 返回 `ok=true`。
- 这不是完整资源组后台管理：尚未提供资源组 CRUD、租户配额、并发槽位、成本策略注册和运行时调度分配。后续若继续做，应新建资源组管理能力，而不是把静态校验包装成算力市场。

## 2026-06-23 流水线与算子市场阶段四 DWD 质量门禁编译产物发现
- 方案 §5.1 要求质量门禁算子额外产出 dbt `tests/schema.yml`；此前实现虽然会生成 `quality_gate` 节点，但 `schema.yml` 仍按字段映射主键硬编码生成，存在图定义与执行产物规则漂移风险。
- Stage 83 已把 DWD compile 调整为先生成 operator graph，再从 `QUALITY_GATE`/`gate.*` 节点的 `config.columns/config.tests` 生成 dbt tests；当前已支持 dbt 内置 `not_null/unique`。
- 真实 API 实证：`quality_gate.config.columns=["id"]`、`tests=["not_null","unique"]` 时，落盘 `dwd_user_codex_glossary_gbtlu_df.yml` 只在 `id` 列生成 `not_null/unique`，普通字段和敏感字段没有被误加测试。
- 兼容边界：如果 graph 缺失质量门禁配置，仍按主键兜底生成 `not_null/unique`，避免历史草稿丢失基础校验。
- Stage 84 已继续补齐可执行 dbt generic tests：`gate.enum` → `accepted_values.values`，`gate.referential` → `relationships.to/field`；当前产品没有自定义 DWD graph 注入入口，所以这部分用单测覆盖 YAML 形态，真实 API 回归覆盖默认 `not_null/unique` 不退化。
- Stage 85 已为 `gate.range/gate.regex` 补齐 OneLake 自定义 dbt generic tests：`onelake_range` 返回超出范围记录，`onelake_regex` 返回不匹配正则记录；后端生成 `arguments:` 结构，临时最小 dbt 项目 `dbt parse` 通过。
- Stage 86 已为 `gate.row_count` 补齐模型级 OneLake 自定义 dbt generic test：`onelake_row_count` 写入 `models[].tests`，不挂在列级 `columns[].tests`；临时最小 dbt 项目 parse 通过。
- Stage 87 已修复完整 dbt project parse 阻断：DWD compile 不再用单模型 source 覆盖共享 `models/generated/sources.yml`，而是聚合当前模型与当前租户已验证 DWD 模型依赖的 ODS sources；真实产物同时包含 `ods_codex_orders` 与 `ods_customers_100k`。
- 完整 dbt project `dbt parse` 现已通过；剩余 warning 来自既有 `models/marts/schema.yml` 中 `dbt_utils.accepted_range` 仍使用顶层参数，以及 `dbt_project.yml` 的 unused staging 配置。
- `LineageGraph.tsx` 的可选 `dagre` 动态导入不能写成字面量 `import('dagre')`，否则 Vite build 会在依赖未安装时解析失败；应保留运行时动态导入以实现未安装时降级网格布局。
- Stage 88 已支持 `gate.freshness` 输出 dbt source freshness：`column/loadedAtField` 写为 `loaded_at_field`，`maxDelay=24h` 等短格式写为 `warn_after/error_after`；真实 API 临时写入 freshness graph 后，`sources.yml` 形态和完整 dbt parse 均通过，并已恢复测试 DB 变更。
- Stage 89 已支持 `gate.custom_sql` 的最小安全协议：断言 SQL 必须是单条只读语句，必须通过 `{{ model }}` 引用当前模型，且不能引用其他表；编译后写为模型级 `onelake_custom_sql` generic test，dbt macro 运行时替换 `__ONELAKE_MODEL__`。
- DWD compile 现在会保留模型已保存的 `gate.freshness/gate.custom_sql` 扩展质量门禁节点，避免自定义门禁被默认生成图覆盖；但仍不会放开任意历史节点合入，防止默认 graph 重复或不受控算子混入。
- Stage 90 已清理当前 dbt project 的校验噪声：既有 `dbt_utils.accepted_range` 改用 `arguments:`，未命中的 `models.onelake.staging` 配置已移除；完整 `dbt parse` 不再出现 dbt deprecation/unused config warning。
- Stage 91 已把 Stage82 的静态资源契约推进为业务侧注册表：`orchestration.resource_group` 与 `orchestration.compute_profile` 现在是资源组/计算画像的后端事实源，默认保留 `TRINO_DBT/default|rg-default`、`SPARK/spark-default`、`PYTHON/python-default` 种子。
- `OperatorService` 的 Manifest 与 graph 资源校验已经复用注册表；真实 API 临时注册 `warehouse-codex-stage91/trino-codex-stage91` 后可通过校验，错误画像 `spark-large` 仍会被拒绝。
- 资源组后台管理本轮仍是注册表闭环，不是运行时调度器：并发槽位、quota、成本策略已落字段和 API，但尚未接入 Dagster/Trino 队列分配，也未做资源管理 UI。
- Stage 92 已把 Spark/Python 扩展边界显式化为运行契约 API：当前 Dagster repository 只暴露 `onelake_dbt_model_run`，所以 `SQL_DBT` 为 `READY`，`SPARK/PYTHON` 为 `MISSING_DAGSTER_JOB` 且 `graphExecutionSupported=false`。
- 编排触发现在会在创建 `job_run` 前阻断 Spark/Python contract-only DAG；真实临时 Spark DAG 返回 `40012` 且 `job_run` 为 0 行，避免把不可执行运行误记录成失败实例。
- 未完成边界：custom SQL 的结果落库、WARN/QUARANTINE 动作分流和跨模型/维表白名单尚未生产化；当前只完成“可安全编译为 dbt test”的最小闭环。

## 2026-06-23 资产发现与分层表管理边界升级发现
- 当前 `/catalog/search` 与 `/lakehouse/tables` 共享 `CatalogAPI.listAssets()` 和 `Asset` DTO，这是正确的数据事实源复用，不应复制资产模型。
- 两页混淆点主要来自入口命名和列表呈现：目录页叫“搜索浏览”，湖仓页叫“分层表浏览”，且都展示表名、层、密级、质量分、负责人。
- P0 可在不改后端的前提下拉开边界：目录页强化找数、业务标签、热度、申请访问和消费动作；湖仓页强化分层树、表格式、分区、同步、质量门禁和维护状态。
- 后端当前列表查询只支持可选 `layer`，授权状态、推荐理由和维护摘要不是资产列表 DTO 的稳定事实；P0 不应把这些能力伪装成已真实闭环。

## 2026-06-23 业务术语表生产化迭代发现
- 推荐方案落地应以 `module-modeling` 为业务术语主数据所有者，Catalog 只做只读聚合和检索投影，避免 Java 模块依赖倒挂。
- 当前已新增 `modeling.business_term`、`business_term_binding`、`business_term_version`，以及 `/api/v1/modeling/glossary` 术语 CRUD、审定、绑定和版本接口。
- `DomainEvents` 已补充 `modeling.term.*` 事件常量，Glossary 写操作会发布创建、更新、审定、废弃和绑定变更事件。
- Catalog 资产列表接口已扩展 `keyword/term` 参数，并在资产字段 DTO 中返回绑定术语 `terms`，通过 `JdbcTemplate` 只读查询 modeling schema。
- 检查结果：`mvn -q -pl module-modeling -am test -Djacoco.skip=true` 和 `mvn -q -pl module-catalog -am test -Djacoco.skip=true` 均通过。
- 本地运行态验证不能直接跑全量 `make migrate`，因为工作区存在非本轮 orchestration 迁移；本轮只对 `modeling` schema 执行 Flyway，V5 已入 `modeling.flyway_schema_history`。
- `spring-boot:run` 只跑 `bootstrap` 时会加载本地仓库旧 SNAPSHOT；新增跨模块类后需要先执行 `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true`，否则 OpenAPI 看不到新 Controller。
- API 冒烟链路已打通：`CODEX_GMV_102340` 创建、提交、审定、字段绑定、Catalog term 搜索、资产详情字段术语回读均成功。
- 浏览器实证：业务术语表展示术语和关联字段，资产详情 Schema 可见字段术语，资产发现页搜索术语 code 只命中绑定资产。
- 浏览器发现并修复：术语页顶部绑定统计需要服务端 `bindingCount`，不能依赖列表中为空的 `bindings`；资产发现页重复 tag 值会触发 React duplicate key warning，需按来源/索引生成渲染 key。
- 后续增强点：资产详情当前不会自动根据 `?tab=schema&column=amount` 切换和定位字段，浏览器验证时需要手动切到 Schema；这应归入计划中的“字段定位”增强。
- 实施后边界：`/catalog/search` 命名为“资产发现”，主区是“可用资产”，主操作是“申请访问/资产画像”；`/lakehouse/tables` 命名为“分层表管理”，主区是“表治理清单”，主操作是“新建表/派生 DWD/治理详情/优化/SQL”。
- `Tables.tsx` 可复用已有 `CatalogAPI.listMaintenance()` 做维护状态增强；维护加载失败时只降级为 `UNKNOWN`，不能阻断分层表列表。

## 2026-06-23 业务术语影响分析与跨模块闭环发现
- 术语影响分析不能只看 `business_term_binding`，至少要合并 Catalog lineage、Quality rule、DaaS API responseSchema/sourceFqn、Orchestration DAG definition、Security PII 和 Approval request，才能回答“改这个术语会影响哪里”。
- 已审定术语被编辑时，状态进入 `REVIEWING` 还不够；需要同步生成 `GLOSSARY_CHANGE` 审批记录，并在影响分析中展示待处理审批，否则治理链路不可见。
- 敏感术语字段绑定天然是安全信号：手工绑定和 DWD 建模反写都应 upsert `security.pii_scan_record`，本轮实证 `ods.ods_customers_100k.full_name` 与 `dwd.dwd_user_codex_glossary_gbtlu_df.full_name` 均生成 `PENDING/L3`。
- DWD 建模写术语时不要让 Catalog 反向依赖 Modeling 服务；由 `DwdModelService` 保存 `data_model_column_mapping.term_*` 并反写 `business_term_binding(source=MODELING)`，Catalog 继续按绑定表只读聚合。
- DaaS 草稿应在后端保存时富化 `responseSchema`，前端展示只是消费结果；本轮 `a6f77a56-eeb6-480c-976b-553024d15c0a` 已保存 `termCode/caliberSql/termDefinition/classification/masked`。
- `DwdModelDraftRequest.ColumnMappingRequest` 不能保留旧 9 参数重载构造器；Jackson 会丢弃新增 `termId/termCode/termName`，导致请求体看似有术语但落库为空。单测应改为显式补齐新字段。
- `spring-boot:run` 的 devtools 热重启不会可靠替换本地 Maven 仓库模块 jar；本轮旧孤儿 Java 进程继续占用 8080，导致 API 一直跑旧实现。必须确认 `lsof -iTCP:8080` 的 PID，再进程级重启。
- DWD 建模向导从 `ods.ods_customers_100k` 自动推导表名时带数字，现有命名正则不允许 business 段含数字；浏览器验证需手动修正为合法表名，后续可把推导逻辑统一去除数字或调整命名规范。

## 2026-06-24 治理表工厂迭代 1-9 发现
- 用户的真实需求不是单节点串联流水线，而是“以一张 ODS 表为输入，对多个字段并行配置治理规则，最终生成一张 DWD 治理表”；产品入口应从 DAG 画布补充一个字段矩阵式的治理工作台。
- 治理表工厂当前把基础字段转换、字典映射、关联补充统一落为 DWD draft request + operator graph，执行侧仍复用 `module-modeling` 的 dbt/SQL 编译链路，而不是为每个字段治理算子创建独立 Dagster op。
- `join.lookup_enrich` 已支持最小可执行编译：operatorGraph 中声明 `lookupFqn/alias/leftKey/rightKey/fields`，dbt SQL 输出 `left join {{ source(...) }}`，并将 lookup 表写入共享 `sources.yml`。
- 字典值匹配当前由前端将映射表编译为字段表达式 `case when ... then ... else ... end`，同时保留 `standard.codebook_mapping` 节点用于治理图可追溯；后续若接入字典主数据，应替换为字典 ID/版本引用。
- 发布动作当前定义为“已校验模型进入可消费状态并发布建模事件”，要求存在 dbt artifact 与 orchestration DAG；真实物理产表仍依赖运行动作和 Dagster/dbt 执行结果，不能把发布等同于数据已落表。
- 已校验模型需要允许继续编辑并回退草稿，否则用户在保存校验后修改高级算子会被状态机挡住；已发布模型仍应通过新建版本演进。
- 治理表工厂页面已展示运行契约真实边界：`SQL_DBT` 可运行，`SPARK/PYTHON` 因缺少 Dagster job 仍处于 contract-only，不应在页面上开放为可运行高级算力。
- 浏览器验证表明治理表工厂可从登录后的 `/lakehouse/governance-factory` 进入，核心面板、字段矩阵、高级算子入口、保存/编译/运行/发布控件可见；控制台无新增错误，仅有既有 React Router v7 future warnings。

## 2026-06-24 治理能力并入流水线发现
- 最佳产品形态不是继续保留独立“治理表工厂”一级菜单，而是在流水线中提供 DWD 治理模板：表级 DAG 表达输入、治理、输出，字段级治理矩阵作为治理节点的深层配置。
- 字段级治理不能平铺为几十个画布节点，否则字段多时画布不可读；当前实现保留一个“字段治理矩阵”节点，并在宽抽屉中承载字段矩阵、高级算子、校验、编译、运行和发布。
- 分层表管理的“治理成表”已改为进入 `/orchestration/pipelines/new?template=ods-dwd&sourceAssetId=...`，用户从表资产进入后直接落到流水线编辑器，而不是跳到湖仓下的独立页面。
- 旧 `/lakehouse/governance-factory` 路由保留为兼容跳转，自动改写到流水线 `ods-dwd` 模板；左侧菜单已移除独立入口，避免 IA 重叠。
- `GovernanceFactory` 现在是可嵌入组件：独立页壳可隐藏，嵌入模式由 `DagCanvas` 的字段治理节点打开，并通过 `onModelChange` 回写 `modelId/modelStatus/sourceFqn/targetFqn` 到 DAG 节点配置。
- 浏览器验证：流水线模板会生成 `ODS 源表 -> 字段治理矩阵 -> DWD 治理表` 三节点；字段治理矩阵在流水线内打开；保存校验、编译 dbt、运行、发布动作在嵌入式矩阵中可见；菜单中没有独立“治理表工厂”。

## 2026-06-24 DWD 治理流水线工作台重构发现
- 继续在旧 DAG 画布里叠加字段治理、抽屉和门禁配置会让用户故事断裂；DWD 治理应以“源表到目标表”的业务工作台为主路径，技术 DAG 作为高级视图保留。
- `/orchestration/pipelines/new?template=ods-dwd` 应进入 DWD 工作台，而不是默认画布；普通 `/orchestration/pipelines/new` 仍保留旧画布，避免破坏通用编排能力。
- DWD 工作台的稳定闭环阶段是 `源表与目标 -> 治理模型 -> 质量门禁 -> 运行发布 -> 监控血缘`；字段级 Recipe 只占“治理模型”阶段，不应让左侧画布算子列表承担字段处理入口。
- 第一轮实现已复用 `GovernanceFactory`，所以字段映射、字典匹配、关联查询、保存校验、编译 dbt、运行和发布能力没有丢失；下一轮应把工作台详情态和后端模型/DAG 关系加载打通，避免只依赖新建态本地状态。
- 流水线列表需要按 DAG definition/operatorGraph 识别 DWD 治理流水线并进入工作台；否则用户从列表回到详情时仍会落回技术画布，产品主路径会再次割裂。
- 详情态恢复必须同时兼容两类 DAG：前端模板保存的治理节点 config，以及后端 DWD 编译生成的 `DWD_MODEL_DAG` 顶层 definition；只读其中一处会漏掉已有模型。
- `GovernanceFactory` 如果只支持新建态，会让“打开工作台”看起来成功但实际不能继续编辑已有模型；它需要接收 `initialModel` 并重建字段 Recipe。
- 工作台上下文中 `modelId` 和 `pipelineDagId` 必须分离：前者用于加载/编辑治理模型，后者用于打开技术 DAG 与运行实例；混用会让页面看似有 DAG，实际跳转失败。
- 从 DWD 工作台进入运行实例时应带 `dagId` 聚焦当前流水线，否则用户会被全量运行历史打断闭环；运行实例页需要提供“查看全部”作为返回全量视图的显式动作。
- 后端 DWD 编译链路已经支持从 `operatorGraph` 的 `QUALITY_GATE` / `gate.*` 节点生成 dbt tests，因此质量门禁阶段不应停留在静态状态展示；前端可以直接编辑并保存 `not_null/unique/accepted_values/range/custom_sql` 门禁。
- 质量门禁保存必须复用完整 `DwdModelDraftRequest`，只 PATCH operatorGraph 会丢失字段映射和模型上下文；当前前端通过已有 `DataModel.columnMappings` 重建请求，保持模型契约完整。
- DWD 工作台的“监控血缘”不应复制 Catalog 血缘大图；工作台适合展示字段级 lineage 摘要和强入口，完整血缘、影响分析和下游追踪继续交给 `/catalog/lineage?fqn=...`。
- 资产详情入口需要容忍 DWD 目标表尚未投影到 Catalog 的时间差；按 FQN 查不到资产时降级到目录血缘，比显示死链更符合真实运行边界。
- 资源组和计算画像已经有后端注册表与图级校验事实源，DWD 工作台应允许用户选择并保存到模型；但它仍不是运行时调度器，不能把 `resourceGroup/computeProfile` 文案包装成 Dagster/Trino 已按 quota 分配资源。
- 字典匹配的产品化应先让用户在字段 Recipe 内选择“字典集 + 版本”，并把字典引用、版本和 pairs 写入 `operatorGraph`；当前仍沿用 CASE 表达式编译，完整后端字典主数据、审定、发布和运行期字典表 join 应作为后续阶段，而不是在前端预设里伪装完成。
- 标准字典现在有 `modeling.codebook/codebook_version` 后端事实源，DWD 治理设计器可消费已发布字典并与内置预设合并；但当前执行仍把字典 pairs 编译进 CASE，尚未做运行期字典维表 join、缓存刷新、灰度发布或审批流。
- DWD 运行的 `resourceGroup/computeProfile` 已经落库、进入 Dagster op config，并补充进入 execution tags；直接从模型工作台运行和从流水线编排触发两条入口都已对齐资源标签。这满足跨系统观测和检索，但仍不是资源调度器，真正的 quota、并发槽位和队列分配还需要独立调度策略。

## 2026-06-24 流水线模块重设计方案发现
- 流水线现状两个入口：通用画布 `DagCanvas`（能存不能跑，dagsterJob 硬编码 `sql_workbench_draft` 永不可触发，试运行/版本/发布均为写死假数据）；DWD 工作台 `DwdPipelineWorkbench`（能跑但僵化，仅单 ODS→单 DWD）。核心矛盾：灵活的跑不了、能跑的不灵活。
- 算子市场 65 个内置算子仅作 Manifest/校验/可视化；真正生成 SQL 的是 `DwdModelService.generateModelSql`（字段映射+lookup join+增量），算子 template.sql 从未逐节点编译，“图即程序”是空壳。
- 唯一真实可执行数据面是 dbt-on-Trino（Dagster 仅 `onelake_dbt_model_run` + 一个 log stub）；SPARK/PYTHON 全 contract-only，scheduleCron 无调度器。
- 架构债：`ensureDwdDag` 直写 orchestration.dag、`createDwdModelRun` 直写 modeling.model_run（跨 schema 直写违规）；`integration.table.loaded` 被 modeling 与 orchestration 双重消费且匹配规则不一致；编排模块零 Outbox 生产；`JobRun.SUCCESS` 与 `model_run.SUCCEEDED` 枚举不一致；`run_dwd_model` op 忽略资源画像。
- 用户决策：①统一编辑器（合并画布与 DWD 工作台，一条流水线混放多类任务）；②执行引擎要可扩展 Spark（Spark 为真实路径，非契约态）；③任务用新建 `orchestration.pipeline_task` 一等实体表持久化；④本轮做 P1→P4 完整闭环；⑤先落 `docs/流水线模块重设计方案.md` 再开工。
- 重设计核心：流水线=Tasks 的 DAG，运行=Dagster `onelake_pipeline_run` 按引擎分派 executor；TRINO_DBT 子图用 dbt `ref()` + `tag:pipeline_<id>` 选择器执行，跨引擎(含 Spark)依赖走共享 Iceberg/Hive Metastore 表级闭合 + Dagster 顺序。执行层做成引擎可插拔 TaskExecutor SPI。

## 2026-06-25 数据流 DAG 与多输入/多输出调研发现
- 成熟产品都把“任务依赖”和“数据依赖”分层：Airflow DAG 负责任务执行顺序，Asset/TaskFlow 负责数据输入输出；dbt 用 `ref()`/`source()` 让 SQL 里的引用成为依赖图；Dagster 以 asset 为一等对象，资产定义同时声明计算和上游资产；Databricks Jobs 用 DAG 边表达任务依赖与 Run if 条件。
- 可视化 ETL 产品把 Join/Union/Sink 设计成结构化转换节点：Azure Mapping Data Flow 的 Join 节点明确左右输入流、join type 和 join condition；AWS Glue Studio 的 Join transform 要求两个父节点输入，并处理字段冲突。这说明 OneLake 不应只暴露手写 Spark SQL，而应提供结构化 Join/Union/Lookup 节点生成 SQL/Spark 执行物。
- OneLake 当前 `pipeline_task_edge` 可表示 fan-in/fan-out，但语义偏“先后依赖”；要支撑“上游输出即下游输入”，边需要携带 source output、target input、asset FQN、alias 和触发策略，并在编译期自动推导下游 `from_tables`/SQL source。
- 双输入源场景需要多源就绪屏障：同一节点有多个输入时，默认条件应为 `ALL_SUCCEEDED + SAME_FRESHNESS_WINDOW`，避免任意一个 `SYNC_REF` 完成就触发 Join。
- 推荐下一阶段不是新增更多散点任务类型，而是先建立统一节点端口契约，再把 `SYNC_REF`、`SQL_MODEL`、`FIELD_GOVERNANCE`、`SPARK_SQL/PYSPARK`、`QUALITY_GATE` 和结构化 `JOIN/UNION/LOOKUP/BRANCH/SINK` 映射到同一套输入输出模型。

## 2026-06-25 数据流 DAG 契约化实施发现
- Stage 108 已完成第一条生产化纵切：数据流边持久化、Spark Join 输入推导、画布边端口展示、结构化 Join 面板和浏览器校验闭环；这证明当前统一流水线架构可以支撑“两输入到一计算节点”的基本 fan-in。
- `PipelineCompileService` 现在把边上的 `assetFqn/inputAlias/targetInput` 作为 Spark 节点输入事实源，校验后回写 `from_tables` 与 `dataflow_inputs`，再按 `dataflow.nodeKind=JOIN` 生成 Spark SQL。用户不再需要在 Spark 节点里重复填写上游表。
- fan-out 已具备持久化表达能力：同一 source task 可以创建多条 edge 指向不同 target；本轮 UI 会展示输出计数和依赖边图例。但运行时失败传播、下游触发策略和运行实例拓扑还未深化。
- 运行实例页现在以流水线定义为拓扑事实源，`task_run` 只负责状态/指标覆盖；这解决了“只有一个 task_run 时画布只剩一个层级”的观测问题。按 DAG 过滤也已改为调用后端 DAG 运行接口，避免全局第一页本地过滤造成假空。
- `PipelineNodePortRegistry` 已把节点端口契约收敛到流水线主链路，编译校验会拒绝 Join 非 `left/right` 端口、单输入端口重复连入、缺失必填输入、悬空任务和无法解析 asset FQN；fan-out 从一个上游输出到多个下游仍被视为合法。
- 多源就绪屏障已从纯字段契约推进到控制面最小实现：`integration.table.loaded` 到达后按 DAG 记录 SYNC_REF readiness，多个 SYNC_REF 输入指向同一目标时等全部输入就绪再触发流水线。但 readiness 仍是进程内内存态，尚未持久化 batch/window，不适合作为跨重启水位一致性承诺。
- 运行态失败传播已具备节点级语义：失败终态刷新时沿 PIPELINE 边把下游待执行节点标记为 `UPSTREAM_FAILED`，并在前端显示“上游失败”；但真正按 DAG 节点逐个调度仍依赖后续 scheduler/Dagster op 拆分。
- P4 已补齐控制面拓扑初始化：创建运行实例时不再把所有节点扁平写成 `QUEUED`，而是把 `SYNC_REF` 视为已就绪观测节点，直接下游进入 `RUNNING`，fan-in 汇聚节点等待上游成功后再推进。这个能力解决了用户观测层面的“流水线运行只有一个层级/节点关系不可见”，但底层仍是 Dagster aggregate job，尚未拆成每个 pipeline task 一个独立可调度 op。
- P5 画布观测已从“节点配置面板”补齐为“数据流关系面板”：用户点击任一节点，都能看到输入来自哪里、输出给谁、端口、别名、表 FQN、触发策略和新鲜度策略。运行实例展开区也能显示完整拓扑、连线标签和任务明细表。
- 本地 Flyway 因 `orchestration/V4__pipeline_task.sql` 已应用后又发生本地改动，执行 V6 时触发 checksum mismatch。本轮没有 repair 历史，只手工执行 V6 幂等 ALTER 做浏览器验证；后续合并前应整理迁移链，避免 V4 checksum 漂移。
- 本地 `spring-boot:run` 加载多模块 SNAPSHOT jar 时，DevTools 热重启不会替换已加载依赖 jar；如果先启动后端再 `mvn install` 子模块，API 会继续跑旧实现。验证新后端能力前必须用 `lsof -iTCP:8080` 确认 PID 启动时间，并做进程级重启。
- 浏览器登录阻塞来自 OIDC 默认直连 Keycloak；把默认 authority 改为同源 `/auth/realms/onelake` 后，必须在 Vite `/auth` proxy 上 rewrite 去掉前缀，否则 Keycloak 会收到 `/auth/realms/...` 并返回 Page not found。
- 当前结构化节点已从 Join 扩展到 `DERIVE_COLUMN` 与 `SINK`：Join 可表达双输入关联，Derive 可用单输入边生成 UUID、脱敏和字段重算，Sink 可将上游 Spark 结果写入 DWD 表。Union、Lookup、Branch 仍未实现结构化节点契约。
- P6 浏览器验收已跑通：两个 MySQL 源表 `user/user_profile` 各 100 行，对应 ODS Iceberg 表进入 `JOIN -> DERIVE_COLUMN -> SINK(DWD) -> QUALITY_GATE`，运行实例 6 个节点均成功，DWD 表 `dwd.user_governed` 产出 100 行。
- SQL 工作台执行流水线产物查询时暴露出 FQN 归一化缺口：Pipeline Catalog 事件登记的是 `dwd.user_governed`，用户 SQL 常写 `iceberg.dwd.user_governed`；`SqlAssetSecurityService` 已补齐 `iceberg/onelake/hive` catalog 前缀归一匹配，避免已登记资产被误报“未登记到 Catalog”。
- DWD 数据面验证显示手机号、身份证、描述去空格和 UUID 均正确，但 Hive/Trino 对字段标识会展示为小写，物理列 `用户 UUID` 在 Trino `DESCRIBE` 中显示为 `用户 uuid`；后续若要求展示名严格保留大小写，应增加字段 displayName/业务标签，而不是依赖物理列名大小写。
- 2026-06-25 Stage 110 已按“硬删”要求完成主链路旧枚举和旧 DWD/dbt 运行能力删除：新代码不再暴露 `SQL_MODEL`、`FIELD_GOVERNANCE`、`TRINO_DBT`、`SQL_DBT`、`onelake_dbt_model_run` 或 `run_dwd_model` 运行入口；历史值仅在新迁移中作为清理条件出现。建模编译器中仍有 dbt 命名字段/产物路径，这是“模型编译产物”遗留，不再具备独立 DWD/dbt 运行入口。

## 技术决策
| 决策 | 理由 |
|------|------|
| 以当前代码校准文档 | `RTK.md` 明确要求 docs 和 code 不一致时先信当前代码 |
| Airbyte/Dagster 本轮推进到真实数据面实证 | Airbyte 已通过 abctl 运行，Dagster 本地 compose 可用，Postgres 源表到目标库的真实同步已完成 |
| Airbyte 不再放入 `docker-compose.yml` | 官方本地部署已转向 `abctl`，保留无效 compose 服务会持续制造误导和启动失败 |
| 全局任务条先做统一任务投影而不是前端多源拼接 | OneLake 的长任务跨采集、SQL、编排、质量和数据服务，统一投影能保证状态语义、权限、跳转和后续通知联动一致 |
| 流水线合并为统一编辑器 + 任务一等实体表 | 消除“灵活的跑不了/能跑的不灵活”分裂，任务类型化(SQL模型/字段治理/质量门禁/同步引用/Spark)支持数据开发与治理同图 |
| 执行层做成引擎可插拔 SPI，dbt-on-Trino 为基线、Spark 为并列真实路径 | 用户要求扩展 Spark；复用唯一真实数据面 dbt-on-Trino 的同时不把 Spark 做成契约态摆设，跨引擎依赖经共享 Iceberg 表级闭合 |
| 流水线运行 = dbt tag 选择器 + Dagster 单 job 分派 | 让 dbt 负责 Trino 子图 DAG/增量/依赖解析，避免自造执行引擎；Dagster 负责调度、跨引擎顺序与观测 |

## 2026-07-14 湖仓与建模 V2 路线图 Review 修复发现
- Stage 110 已删除 `DwdModelService.run` 和 `/models/{id}/run`；Modeling 只负责模型定义/校验/编译/发布，真实运行由已发布 Spark Pipeline 和 `onelake_pipeline_run` 承担。
- G2 之后，算子节点按锁定 `operatorRef/operatorVersion` 读取精确 Manifest，`PipelineCompileService` 逐节点生成 `compiled_sql`；Dagster 仍以聚合 `run_spark_task_op` 顺序遍历任务，不等于每节点独立 op。
- `CatalogSchemaChangeService.executeApproved` 同时强制 `SCHEMA_CHANGE` 类型和 `APPROVED` 状态，执行 ALTER 后回写 columns/classification；通用 WriteSqlValidator 若允许 ALTER 会绕过该治理边界。
- `CatalogTableService.upsertAsset` 是私有方法；CTAS 路线图必须先抽取公开的资产注册服务，再让 SQL 工作台复用。
- `module-modeling` 不依赖 `module-orchestration`，且 `PipelineSchedulerService` 从已发布快照读调度策略；BREAKING drift 需经 Outbox 通知 orchestration，并在读发布快照前先检查 live DAG 紧急 FROZEN 覆盖，才能无需重发布立即停止 cron。
- Iceberg `ALTER TABLE RENAME` 仅改变表标识，不会将数据/元数据文件迁移到新 S3 prefix；TTL 默认收敛为逻辑 ARCHIVED，物理冷存储必须做复制/重写、校验和 Catalog 原子切换。

## 2026-07-14 湖仓与建模 V2 路线图二轮 Review 修复发现
- `UNIQUE (tenant_id, code)` 不能保证每租户最多一个默认 Catalog；需要 `WHERE is_default=true` 的 PostgreSQL 部分唯一索引，切换默认值必须在同一事务中完成。
- Trino Iceberg `OPTIMIZE` 不接受 `order_by/z_order_cols`；M1 只用 `sorted_by` 表属性和 BIN_PACK/SORT 路径。`retain_last` 要按服务端能力探测，不支持且策略要求最小快照数时必须 fail closed；指定快照永久保护应用 Iceberg Tag。
- 当前编译契约只有 `CompileTarget.SPARK`；`SPARK_SQL` 是 Manifest `template.kind`/任务引擎。`semantic.compile` 必须以 `compileTarget=SPARK` 通过 G1 可见性校验。
- Branch/Tag 的事实源是 Iceberg table refs；当前 Trino JDBC 不能执行 Spark Branch DDL。控制面应通过与 Spark 数据面版本对齐的 Iceberg Java Catalog/ManageSnapshots API 管理 refs，本地表只作审计投影。
- Spark 分支写入本轮选定 `<table>.branch_<branch>` 标识符为唯一契约；`FOR VERSION AS OF` 是读语义，`ALTER TABLE WRITE TO BRANCH` 不是可执行写语句，且 branch 写不能用 CTAS 创建新表。
- 写 API 不应进入 `permitAll`；当前 `anyRequest().authenticated()` 已托底，再加 DE 方法角色即可。DEV/白名单是服务端受信配置，不得由客户端传入；DEV 仅本地 profile 允许并仅跳过 WRITE ACL。
- Iceberg snapshot ID 不是时间序列，`max(snapshot_id)` 既错误又有并发竞态。写审计应在 asset 级串行边界内记录写前快照和时间窗，以 `$history/$snapshots` 的 `committed_at + parent_id` 唯一关联；歧义时标记 `UNKNOWN` 且禁用回滚。
- SCD2 的独立 UPDATE + INSERT 会暴露半完成状态；应用单个 Spark MERGE 生成一个 Iceberg 快照，通过 `scd_change_request` 唯一幂等键处理重放，并串行化同 dimension/business key 的并发变更。

## 2026-07-14 湖仓与建模 V2 路线图三轮 Review 修复发现
- `FROM (SELECT DISTINCT tenant_id ...) t` 只暴露 `t.tenant_id`，默认 Catalog seed 引用 `t.id` 会在 Flyway 执行时直接失败。
- `module-orchestration` 当前不应为 Catalog 维护引入新 TaskType/executor 或跨域 Service 依赖；维护策略、历史与执行都在 Catalog 域，因此采用域内 scheduler + 独立过期锁。
- CTAS 的物理表先于 Catalog 资产注册存在；写审计必须以规范化 `target_fqn` 为稳定身份和锁 key，允许 `asset_id` 暂空并在注册后回填。
- 现有资产授权事实源是 `security.access_grant.permissions` JSONB；`acl_resource` 的单值 `permission` 用于 SavedQuery/QueryTemplate，不能按文档伪造 `permissions` JSONB。
- SemanticEntity 若只有 JPA 实体而无迁移，首次访问必然缺表；V10 必须先建 `modeling.semantic_entity`，Metric 用 `semantic_entity_id` 作权威外键、`entity_fqn` 仅作快照。
- ROLE_PLAYING 维度会以 order_date/ship_date 等角色多次关联同一维度；唯一键必须纳入 `role_name`，同时用 fact FK 唯一约束防止单列重复绑定。
- BREAKING drift 不能把 live DAG `schedule_mode` 当临时覆盖；原因级 `pipeline_freeze_override` 可幂等激活/定向 resolve，不会解除手工 FROZEN 或其他模型的 ACTIVE 覆盖。

## 2026-07-14 G3 算子版本锁定收口发现
- V31 已为 `pipeline_task` 增加 `operator_ref/operator_version`，并为 `task_run` 增加 `operator_version`；本轮无需数据库迁移。
- `PipelineSnapshotService` 已序列化和反序列化算子引用与版本，但快照边界尚未主动拒绝 ref/version 只存在一项的不完整节点。
- PROD 触发已从 `published_version_id` 读取 `ExecutionSnapshot`，再调用 `PipelineCompileService.compile(dagId, tenantId, snapshotTasks, snapshotEdges)`；不会重新读取实时 `pipeline_task`。
- `PipelineCompileService` 已强制算子节点提供版本，并调用 `OperatorService.getManifest(tenantId, ref, exactVersion)`；该入口不会回退 `latestVersion`。
- `OrchestrationService` 创建 `TaskRun` 时已经复制执行快照任务的 `operatorVersion`，但缺少明确的发布快照升级回归测试把这些边界串起来。
- 阶段 113 已有真实 v1/v2 数据面验证证据；本轮重点是把同一不变量固化成快速、离线、可重复的模块单测。
- G3 收口后，快照边界拒绝半锁定节点；升级复现测试两次只读取 `ref@1.0.0`，显式切换的新快照才读取 `ref@2.0.0`。`module-orchestration` 全量 483 个测试通过。

## 2026-07-14 G3 真实运行复核发现
- 当前后端健康为 UP；Postgres/Redis/MinIO/Trino、Spark master/worker、Hive Metastore、Dagster webserver/daemon 均为 running，Dagster user-code 与专用 Postgres healthy。
- 阶段 113 的三次成功 run、两个发布快照、算子 v1/v2 和 Iceberg 结果表均有稳定标识，可直接从当前数据库交叉复核，而不需要伪造一组新结果。
- 当前数据库仍保留 pipeline version 1/2：v1 快照锁定 `1.0.0` 并生成 `v1/101` SQL，v2 快照锁定 `2.0.0` 并生成 `v2/202` SQL；checksum 和版本 ID 均不同。
- 三个目标 run 当前仍为 SUCCEEDED，绑定关系依次为 pipeline/operator `1/1.0.0`、`1/1.0.0`、`2/2.0.0`，每次 `rows_written=1` 且 artifact 相同。
- 当前 Trino 表结果为 `v2/202`，与最终重发布版本一致。
- Iceberg 时间旅行实时读取三个 snapshot_id，结果依次为 `first_v1=v1/101`、`unrepublished_v1=v1/101`、`republished_v2=v2/202`；结果数据与 pipeline/task version 绑定完全一致。
- 现有证据同时覆盖 Manifest、发布快照、JobRun、TaskRun、Iceberg snapshot 和表内容，已能证明版本锁定与复现；无需新建第四次运行污染测试历史。

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
- Stage 108 浏览器验证打开 `/orchestration/pipelines/0699f001-567a-4a8d-84e9-99a41c1ba117`，可见层级 1 两个 ODS 输入、层级 2 一个 Spark Join，底部图例展示 `ods_user -> spark_user_join / left as u` 与 `ods_user_profile -> spark_user_join / right as p`。
- 点击 Spark 节点后右侧面板展示“输入表（由数据流连线推导）”、`left/right` 输入标签、Join 类型、左右别名、关联条件、输出字段和自动生成的 `CREATE OR REPLACE TABLE ... LEFT JOIN ...` SQL；点击页面“校验”后出现“校验通过”，控制台无 error。
- Stage 108 运行实例验证打开 `/orchestration/runs?dagId=0699f001-567a-4a8d-84e9-99a41c1ba117`，展开 `codex-partial-topology-run` 后可见完整 3 节点拓扑、2 条依赖边、`left/right` 边标签、输入输出数量、Spark 行数和产物表；该测试故意只写入 Spark 节点 task_run，用于验证缺失节点级运行记录时仍可观测完整流水线。

---
*每执行2次查看/浏览器/搜索操作后更新此文件*
*防止视觉信息丢失*

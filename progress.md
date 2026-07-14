# 进度日志

## 会话：2026-07-14（算子版本锁定真实运行验证）

### 阶段 113：v1/v2 发布快照复现
- **状态：** complete
- 用户要求以数据工程师视角做三阶段真实运行验证：v1 发布运行；DEV 升级到 v2 但不重发时生产仍复现 v1；重发后采用 v2。
- 验证将同时记录 operator version、pipeline draft/published snapshot、compiled SQL、job/task run 和结果表，避免仅凭接口状态下结论。
- 已确认后端健康和 G2 OpenAPI；核心依赖中 Postgres/Redis/MinIO/Keycloak/Trino 正常，Dagster/Spark 尚未启动。
- 已沿 `OrchestrationService -> PipelineSnapshotService -> PipelineCompileService` 核对：生产运行编译发布快照中的冻结任务，DEV 节点升级不会影响尚未重新发布的生产版本。
- 首次在仓库根目录执行 `docker compose config --services` 失败，因为 compose 文件位于 `onelake-app/`；后续统一切换到该目录执行环境命令。
- 已确认 `PUBLISHED -> PUBLISHED` 且草稿有未发布变更时，后端会重新校验并发布新快照，符合第三阶段验证所需状态机路径。
- 已从 compose 配置确认 Spark、Hive Metastore 与 Dagster 的准确服务名，下一步启动真实运行组件。
- 已成功启动 `hive-metastore`、Spark master/worker 与 Dagster postgres/user-code/webserver/daemon；compose 健康检查通过后再进入 API 造数。
- 首次按猜测文件名读取注册/拖入请求 DTO 时，两个文件名与实际代码不符；已记录并改用类名搜索定位，不据此猜请求结构。
- 已定位真实拖入 DTO `OperatorTaskCreateRequest` 和统一 Dagster job `onelake_pipeline_run`；下一步读取其字段并执行 API 注册/建图。
- 运行组件启动后复核均为 Up，Dagster user-code 健康；拖入请求精确字段也已从 DTO 确认。
- 已实时验证 Keycloak 认证可用，发布审批关闭；具备直接执行三阶段 API 流程的前置条件。
- 搜索内置算子示例时使用了过期路径 `service/BuiltInOperatorCatalog.java`，实际文件不在该路径；本次将以 DTO、校验接口和 RAW_SQL 单测为准。
- 已确认 RAW_SQL 编译物化语义：安全查询会变为目标 Iceberg 表的 `CREATE OR REPLACE TABLE AS`，可用同一表精确比较三个运行阶段。
- 首次组装 v1 Manifest 的 shell 命令因 SQL 单引号嵌入 jq 程序导致 zsh 引号未闭合，API 尚未收到请求、未产生测试数据；改为用 `jq --arg sql` 传递模板文本后重试。
- 修正引号后 Manifest 校验实际通过（`ok=true, errors=[]`），但脚本错误读取为 `.data.valid` 而提前退出；注册/建图仍未执行，改按真实字段 `.data.ok` 判断。
- v1 注册已进入后端，但随后创建空白流水线和拖入节点均返回 `code=50000 internal error`；先暂停后续步骤，读取后端真实日志定位原因。注册操作可能已落库，定位后将按 ref 查证并复用或明确记录孤立夹具。
- 仓库 `.run-logs` 当前没有可读日志文件；后端仍运行在 detached screen `onelake-backend`，改从 screen scrollback 抓取异常栈。
- screen hardcopy 没有捕获历史输出；进一步从 Java 进程文件描述符确认 stdout/stderr 实际写入被 gitignore 的 `.run-logs/backend.log`，此前 `rg --files` 因忽略规则未列出该文件。下一步直接读取该日志。
- 日志检出本地库缺少 `orchestration.pipeline_subscription` 的持续调度异常，且噪声掩盖创建流水线的 HTTP 栈；下一步直接查询 migration history、DAG 表结构和刚注册算子，定位需要补齐的本地迁移。
- 数据库确认注册已落库为 `verify.version_lock_135715@1.0.0`，而 DAG 表仍停在早期 14 列，未应用版本快照/自动化等后续迁移；需先校准并执行项目迁移，再继续建图。
- Flyway history 已确认 orchestration 仅到 V5；准备执行项目 `make migrate`，先让 canonical migration 流程报告真实阻塞。
- `make migrate` 在第一个 common schema 校验阶段即失败：已应用 V3/V7 checksum 与当前文件不一致，尚未触碰 orchestration。为避免对无关 schema 执行 `repair`，改为单独运行 orchestration Flyway，确认该 schema 自身状态。
- 定向 orchestration Flyway 也因历史 V3/V4/V5 checksum 漂移拒绝迁移；这与当前文件曾被修改的本地历史一致。不会执行 `repair` 改写旧 checksum，改用 `validateOnMigrate=false` 仅应用待执行 V6+，保留既有历史记录原样。
- 定向 orchestration Flyway（`validateOnMigrate=false`）已成功应用所有待执行迁移；未执行 repair、未改写 V3/V4/V5 历史 checksum。下一步复核 V24/V26 结构并重试建图。
- 复核通过：orchestration 已到 V32，发布快照/订阅表和 DAG 新字段完整，后端健康；v1 算子与目标表/SQL 均可通过 API 和数据库交叉查证。
- 已从 `verify.version_lock_135715@1.0.0` 创建标准 Spark 节点和空白流水线，字段/默认 config/位置正确；准备校验、发布并首次触发。
- v1 流水线已校验并发布为不可变版本 1，发布指针 `3c466f65-ab75-4ed5-a640-8f2a5dcff820`；编译 SQL 和快照锁定版本均为 v1，准备触发生产运行。
- 首次 PROD 触发被 Dagster 拒绝：运行中的 user-code config schema 不认识后端发送的 `callback_base_url/runtime_params`，表明刚启动的是旧镜像。该次没有形成可用运行结果；需从当前工作树重建 Dagster 镜像并重启相关服务。
- 已确认 canonical `make dagster-up` 会 `--build` 当前 Dagster 代码；读取 Dockerfile 时误猜了 `Dockerfile`/`Dockerfile.webserver`，实际 compose 使用 `Dockerfile_dagster`，不影响后续 make 入口。
- `make dagster-up` 在 Docker BuildKit 拉取 Python base image metadata 时因 registry deadline 超时失败，现有容器未被成功替换。下一步检查本地 base image/cache，并优先只重建包含 definitions 的 user-code 镜像。
- 本地 base tag 不存在，但旧 user-code 镜像完整且 Dockerfile 的业务变化入口仅是 `definitions.py`；准备通过临时容器替换该文件并 commit 新本地镜像，再强制重建 user-code 服务，不访问 registry。
- 已离线生成 user-code 镜像 `sha256:68d0748b...`，强制重建 user-code 并重启 webserver/daemon；等待健康后重触发同一 pipeline v1。
- 新 definitions 已被接受并创建 v1 run `b2e45050-8db1-4a91-ad9d-960e93f736e1`，但运行从 QUEUED 转为 FAILED；诊断查询又误用了不存在的 `job_run.env` / `task_run.task_type` 列。下一步用 `select *`/information_schema 和 Dagster 日志读取真实失败原因。
- 真实失败已定位为旧镜像用户/可写目录漂移：发布版本和 task_run 均正确锁定 v1，Spark submit 已组装，但 root 的 `.dagster/.ivy2` 在 read-only rootfs 无法写入。下一步修正本地镜像 USER/HOME/DAGSTER_HOME 后重跑。
- 已确认旧镜像以 root 运行，而当前 Dockerfile 预期 uid 10001；不修改仓库代码，直接给离线本地镜像补正确 USER/HOME/DAGSTER_HOME 配置并重建服务。
- user-code 镜像已更新为 `sha256:7b4894ab...`，容器实证 uid/gid=10001、HOME 和 DAGSTER_HOME 均可写、健康检查通过；准备再次触发同一 v1 发布版本。
- 第二次 v1 运行 `324dcb07-...` 仍在 spark-submit 阶段失败，但发布版本 id 与 task `operator_version=1.0.0` 继续正确；read-only 目录问题已排除，读取本次 Dagster/Spark 日志定位新的具体失败点。
- 第二次失败点已精确定位：数字 uid 10001 在旧镜像 `/etc/passwd` 中没有用户条目，Java 将 `user.home` 解析为 `?`，Ivy 因 `?/.ivy2` 非绝对路径退出。需给离线镜像补当前 Dockerfile 本应创建的 `onelake` 用户条目。
- 离线镜像 `sha256:116830ec...` 已补 onelake 用户；容器/Java 实证 uid=10001(onelake)、`user.home=/home/onelake`、健康检查通过，准备第三次触发 v1。
- v1 第三次运行成功：run `3e53af7f-...`、task SUCCEEDED、1 行写入、锁定 operator v1；Trino 读取结果 `v1/101`，首阶段基线完成。
- 已发布 operator v2 并把 DEV 节点升级到 2.0.0；DEV 预览为 v2/202，但生产发布指针和唯一发布版本仍是 v1 快照，`hasUnpublishedChanges=true`。准备不重发直接触发 PROD。
- 未重发的第二次 PROD run `68673a21-...` 成功，仍绑定发布版本 1 / operator 1.0.0；Trino 结果保持 `v1/101`，第二阶段通过。
- 已重新发布 pipeline version 2（`785f1922-...`）；新快照与结构化 diff 均确认节点/compiled SQL 从 v1 切换 v2，准备触发最终 PROD。
- 重发后 PROD run `14cbc940-...` 成功，绑定 pipeline version 2 / operator 2.0.0；Trino 结果变为 `v2/202`，第三阶段通过。
- 最终 SQL 审计已并列核对两个发布版本、三个成功 run 和两份算子模板；`git diff --check` 通过。组合状态命令中的 `docker compose ps` 又因在仓库根目录执行而失败，改在 `onelake-app/` 单独复核容器。
- 收口复核：Spark/Hive Metastore/Dagster 服务均 Up（user-code 与 Dagster Postgres healthy）；测试夹具保留用于复现，不清理 operator/pipeline/run/result table；本轮未修改业务代码，因此不重复运行 Maven 单测。
- 已定位 PROD 触发读取 `publishedVersionId` 不可变快照的代码路径，下一步核对请求结构、数据面启动命令和结果表方案。


## 会话：2026-07-14（G2 算子拖入生成可执行节点）

### 阶段 111：设计前上下文核对
- **状态：** in_progress
- 读取 `RTK.md`、M4 9.1/9.2 任务卡、现有规划文件和相关历史实现记录。
- 检查当前 HEAD 与工作区，确认只有既有未跟踪运行产物，不触碰这些文件。
- 定位 `PipelineService`、`OperatorService`、`OperatorController`、`PipelineController`、`PipelineTask`、`OperatorManifestDTO` 与现有测试。
- 确认 G1 编译链、精确版本 Manifest 查询和标准 `pipeline_task` 字段已经就绪；业务代码尚未修改，等待设计确认。
- 用户确认默认 config 使用 Schema default 优先、首个示例参数兜底的规则；进入方案比较。
- 用户确认独立命令接口方案；开始分段确认架构、数据流、错误处理和测试设计。
- 用户已确认架构/API、节点生成数据流、错误处理与测试设计。
- 新增并自检 `docs/superpowers/specs/2026-07-14-operator-to-pipeline-task-design.md`；占位符扫描和 `git diff --check` 通过。
- 首次新增规格的 patch 因一行缺少 `+` 被拒绝，修正格式后成功，未产生残缺文件。
- 设计规格已单独提交为 `1a9b0ca docs(orchestration): design operator task creation`，等待用户最终审阅后进入实现。
- 用户已确认书面规格；`writing-plans` 技能不可用，已将阶段 111 展开为 TDD、服务/API 实现、编译闭环和全量验证步骤作为回退计划。
- 已新增 G2 失败测试；首次聚焦命令未带 `-am`，被 reactor 内 `InternalApiTokenFilter` 依赖缺失提前截断，下一次改用联动构建命令。
- G2 服务/API 最小实现已写入；聚焦测试进入运行后发现两处测试预期偏差（FQN 安全反引号、分类排序），保留生产契约并修正测试。
- 聚焦测试 `OperatorServiceTest,PipelineOperatorTaskCreationTest,PipelineStatusMachineTest` 已通过。
- 用户要求的 `mvn -q -pl module-orchestration -am test` 已通过；日志中的 receipt failure、Netty native DNS 等为既有测试场景/环境日志，Maven exit code 为 0。
- 已同步编排 V2 升级计划和 M4 9.2 任务卡，明确 G2 后端已实现、9.4 前端仍未实施。
- Surefire 汇总：`module-common` 20 个测试、`module-orchestration` 467 个测试，failure/error/skipped 均为 0。
- `git diff --check` 通过；既有未跟踪 `onelake-app/dagster/__pycache__/` 保持不动。
- Code review 发现并修复四项契约漂移：G1 不支持算子进入 Palette、同 ref 身份歧义、pinnedVersion 与 Manifest 不一致、Manifest 端口未参与图校验。
- 新增共享 `OperatorG1Compatibility`；Palette 和创建命令统一模板、端口基数及源模板组合判定。
- 编译阶段复用锁定 Manifest 构造端口契约；补自定义端口、源端口、多输入拒绝等回归测试。
- Review 修复后的聚焦测试与 `mvn -q -pl module-orchestration -am test` 再次通过。
- 阶段 111 完成。

### 阶段 112：G2 二轮 Review 修复与提交
- **状态：** complete
- 复核未提交实现、现有 G2 规格、Operator 表唯一约束和创建/更新调用链，确认四项 Review 问题均可由当前代码路径触发。
- 用户确认固定版本冲突必须返回业务错误，不允许服务端静默改写版本。
- 二轮修复设计已写入现有规格并提交为 `5637111 docs(orchestration): specify operator review fixes`。
- `writing-plans` 技能不可用，继续使用阶段 112 的 TDD、实现、全量验证和提交清单作为回退实施计划。
- 已新增四类失败测试；首次聚焦测试按预期在 testCompile 红灯，错误集中为尚未实现的 `getInstalledManifest` 和 Repository 多行返回契约。
- 已开始实现集中安装解析、稳定 ID 选择、创建/改绑校验分流和 example 空值防御。
- 首轮绿灯测试暴露两处测试夹具问题：UUID 自然序对高位按有符号值比较，且编译阶段仍应单独 mock 锁定 Manifest 读取；均不改变生产契约。
- 聚焦测试通过：`OperatorServiceTest`、`PipelineOperatorTaskCreationTest`、`PipelineStatusMachineTest` 合计 64 个测试，失败/错误为 0。
- 用户要求的 `mvn -q -pl module-orchestration -am test` 通过；Surefire 汇总为 501 个测试，失败/错误/跳过均为 0。
- 一次测试汇总读取误在 `onelake-app/` 工作目录下重复拼接路径，修正相对路径后成功读取报告；未影响代码或测试结果。
- 完成提交前复核与 `git diff --check`；无关湖仓/建模文档和 `dagster/__pycache__` 明确排除在 G2 提交之外。

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

### 阶段 53：流水线与算子市场阶段二后端市场底座
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 读取 `docs/流水线与算子市场算子标准设计方案.md`，确认阶段二目标是表、市场后端 API、65 内置算子 seed 和前端市场接真实 API。
  - 读取 `module-orchestration` 当前 DAG/Run 实体、服务、控制器和 V1 迁移，确认原先没有 operator 表、API 或测试目录。
  - 新增迁移 `orchestration/V2__operator_market.sql`，包含 `operator`、`operator_version`、`operator_install` 和索引。
  - 新增算子实体、枚举、Repository、DTO、`OperatorService`、`OperatorController` 和 `OperatorSeeder`。
  - 将设计方案里的 65 个内置算子落成 `BuiltInOperatorCatalog`，每个算子都有统一 Manifest、SQL_DBT template、输入端口、输出 schema、参数 schema、治理/资源提示和示例。
  - `OperatorService` 支持当前租户可见列表、详情、Manifest 校验、自定义注册、版本发布、元信息更新、安装/版本锁定和内置算子幂等 seed。
  - 补充 `OperatorServiceTest`，覆盖内置算子数量、seed 持久化、质量门禁策略校验、注册审计、安装版本校验和列表 manifest。
  - 第一次运行 `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 发现 service 中少写 `OperatorCategory.QUALITY_GATE` 枚举限定名，已修复。
  - 第二次测试发现两个 Mockito 多余 stub，删除后第三次测试通过。
  - 运行 `git diff --check` 通过。
- 创建/修改的文件：
  - `onelake-app/bootstrap/src/main/resources/db/migration/orchestration/V2__operator_market.sql`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/api/OperatorController.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/config/BuiltInOperatorCatalog.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/config/OperatorSeeder.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/domain/entity/Operator.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/domain/entity/OperatorInstall.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/domain/entity/OperatorVersion.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/domain/enums/`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/dto/`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/repository/`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/service/OperatorService.java`
  - `onelake-app/module-orchestration/src/test/java/com/onelake/orchestration/service/OperatorServiceTest.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 54：流水线与算子市场阶段二前端市场真实 API 接入
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 读取 `OperatorMarket.tsx`、`web-console/src/api/index.ts`、`web-console/src/types/index.ts`、`PageHeader`、`StateView` 和 `IntentBadge`。
  - 新增前端 `OperatorManifest`、`OperatorVersion`、`Operator`、`OperatorValidationResult` 类型，并为 `DagNode` 增加 `operatorRef/operatorVersion/config` 可选字段。
  - 在 `api/index.ts` 新增 `OperatorAPI`，封装 list/detail/validate/register/publish/update/install。
  - 重写 `OperatorMarket.tsx`：从真实 API 拉取算子，支持 scope 分段、category 下拉、关键词搜索、刷新、详情弹窗、参数/版本/示例展示和安装/锁定版本。
  - 第一次 `pnpm exec tsc --noEmit` 失败：`PageHeader` 不支持 `stats` 属性；改用组件已有 `meta` 属性后通过。
  - `pnpm build` 通过。
  - 本地 8080 旧进程未加载 operators controller；执行 `mvn -q -pl module-orchestration -am install -DskipTests -Djacoco.skip=true`、`make migrate` 并重启后端。
  - 后端启动日志确认 `OperatorSeeder` 写入 65 个内置算子。
  - API 验证：dev token 调用 `/api/v1/orchestration/operators?scope=BUILTIN` 返回 65，`/operators/mask.partial` 返回 1 个版本与 `COLUMN_EXPR` 模板。
  - 浏览器打开 `http://localhost:5173/orchestration/operators`，页面显示真实算子卡片；详情请求、安装请求和刷新请求均为 200。
  - 点击安装时发现 AntD 静态 message warning，改为 `AntApp.useApp()` 后复测浏览器控制台 0 error。
  - 验证命令通过：`pnpm exec tsc --noEmit`、`pnpm build`、`git diff --check`。
- 创建/修改的文件：
  - `onelake-app/web-console/src/types/index.ts`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/orchestration/OperatorMarket.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 55：流水线与算子市场阶段三 OperatorCompiler 接入前置核对
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 重新读取 `task_plan.md`、`findings.md`、`progress.md`、`RTK.md` 摘要和阶段 53/54 记录。
  - 读取 `DwdModelService` 的 compile、run、dag definition、operator graph 生成逻辑，以及 `DwdModelServiceTest` 现有覆盖。
  - 读取 `module-modeling/pom.xml`，确认 modeling 只依赖 common，不直接依赖 orchestration。
  - 读取 `dagster/definitions.py` 与 ODS->DWD 方案摘要，确认 `onelake_dbt_model_run` 和 dbt run/artifact 回写链路已经存在。
- 当前结论：
  - 阶段三第一轮实现不重复做 Dagster job，而是把 DWD 默认 operator graph 接入算子市场 manifest。
  - 实现方式沿用 modeling 已有 `JdbcTemplate` 跨 schema 边界，读取 built-in operator latest manifest 并嵌入 graph。
- 执行的实现：
  - `DwdModelService.compileArtifacts` 在写 dbt 产物前解析 DWD 默认链所需的 built-in operator manifest。
  - 生成的 operator graph 节点新增 `operatorRef/operatorVersion/operatorCategory/compileTarget/manifest/policy/emitsLineage/emitsQualityResult`。
  - 默认链使用 `input.ods_table`、`transform.rename_columns`、`govern.drop_required_missing`、`gate.not_null` 和按物化方式选择的输出算子。
  - 缺少 manifest、manifest JSON 非法、category 不匹配、`compileTarget` 非 `SQL_DBT` 或 template 缺失时阻断 compile。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl module-orchestration,module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q install -DskipTests -Djacoco.skip=true` 通过。
  - 真实 API 创建 DWD 草稿并 compile：`POST /api/v1/modeling/models/{id}/compile` 返回 200，operator refs 为 `input.ods_table,transform.rename_columns,govern.drop_required_missing,gate.not_null,output.iceberg_table`。
  - DB 反查 `orchestration.dag.definition.nodes[*].operatorRef` 与 API 返回一致。
  - `git diff --check` 通过。
- 遇到的问题：
  - 第一次重启后端后真实 API 仍返回旧 operator graph，因为 `bootstrap` 运行时使用本地 Maven 仓库的 `module-modeling` SNAPSHOT jar；执行 module install 并重启后复测通过。
  - Spring Boot 优雅关闭在 `taskScheduler` 上等待较久，明确 PID 后使用强制结束完成重启。
- 创建/修改的文件：
  - `onelake-app/module-modeling/src/main/java/com/onelake/modeling/service/DwdModelService.java`
  - `onelake-app/module-modeling/src/test/java/com/onelake/modeling/service/DwdModelServiceTest.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 56：流水线与算子市场阶段三图级校验服务
- **状态：** complete
- **开始时间：** 2026-06-22 CST
- 执行的操作：
  - 读取 `OperatorService`、`OperatorController`、DAG definition 和 `OperatorServiceTest`，确认图级校验应复用市场 Manifest，不新增第二套契约。
  - 在 `OperatorService` 新增 `validateGraph`，支持直接 graph、`operatorGraph`、`definition.operatorGraph` 等请求形态。
  - 校验节点 id、边引用、环路、operatorRef/operatorVersion、Manifest 自校验、`compileTarget=SQL_DBT`、节点类型与 category、required params 和输入端口基数。
  - 允许内部 `DBT_MODEL` 系统节点无 `operatorRef`，并返回 warning，避免把 dbt runtime 节点伪装成市场算子。
  - `OperatorController` 暴露 `POST /api/v1/orchestration/operators/graph/validate`。
  - 补充 `OperatorServiceTest`，覆盖合法 DWD 默认图、缺必填参数和环路。
  - 运行 `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - 运行 `mvn -q -pl module-orchestration -am install -DskipTests -Djacoco.skip=true` 和进程级后端重启后，OpenAPI 已包含新端点。
  - 真实 API 正向验证：DWD compile 生成的 DAG `94f21184-752f-40ea-9c65-1a5ee00b3699` 校验返回 `ok=true`，5 个市场算子 refs 均被校验。
  - 真实 API 反向验证：修改 `definition.operatorGraph` 后返回 `ok=false`，错误包含 `DAG 存在环路`、`节点 transform_mapping 缺少必需参数: mapping`。
  - 运行 `mvn -q install -DskipTests -Djacoco.skip=true` 和 `git diff --check` 通过。
- 遇到的问题：
  - 首次 runtime 验证 404，原因是后端进程未完整加载新 module-orchestration SNAPSHOT；强制停止旧 PID 并重启后 OpenAPI 映射恢复。
  - 反向验证第一次误改 DAG 顶层 `nodes/edges`，但接口优先校验 `operatorGraph` 快照，导致仍返回合法；改为修改 `definition.operatorGraph` 后按预期返回错误。
- 创建/修改的文件：
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/api/OperatorController.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/service/OperatorService.java`
  - `onelake-app/module-orchestration/src/test/java/com/onelake/orchestration/service/OperatorServiceTest.java`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 57：流水线与算子市场阶段三前端图级校验接入
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 读取 `DagCanvas.tsx`、`api/index.ts`、`types/index.ts` 中 DAG、Operator API 和节点类型现状。
  - `OperatorAPI` 新增 `validateGraph`，调用 `POST /api/v1/orchestration/operators/graph/validate`。
  - `DagCanvas` 保留从 DAG definition 读取到的 `operatorRef/operatorVersion/config`，并优先读取 `definition.operatorGraph`。
  - 静态样例节点增加默认内置算子映射：`input.ods_table`、`govern.drop_required_missing`、`mask.partial`、`output.iceberg_table`，便于新建画布也能真实校验。
  - 校验按钮改为请求真实 graph validate API，弹窗展示后端返回的 errors/warnings/success。
  - 保存按钮改为保存前执行同一图级校验；本轮仍不持久化 DAG，下一轮补真实保存。
  - 将 `DagCanvas` 的反馈消息切到 AntD `App.useApp()`，避免新增静态 message warning。
  - 运行 `pnpm exec tsc --noEmit`、`pnpm build` 和 `git diff --check` 通过。
  - 浏览器打开 `http://localhost:5173/orchestration/pipelines/new`，点击“校验”和“保存”各产生一次真实 `POST /api/v1/orchestration/operators/graph/validate => 200`，弹窗显示 `✓ 4 节点通过`，控制台 0 error。
- 创建/修改的文件：
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/orchestration/DagCanvas.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 58：流水线与算子市场阶段三 DAG 草稿真实保存
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 读取 `DagController`、`OrchestrationService`、`DagRepository`、`Dag`、`DagDTO`、`PipelineList` 和 `DagCanvas`。
  - 后端新增 `DagRepository.findByIdAndTenantId` 与 `OrchestrationService.updateDag`，保存修改后的 definition 并递增 version。
  - `getDag`、`triggerDag`、`runs` 改为按当前租户查 DAG，避免跨租户读取、触发或查询运行历史。
  - `DagController` 暴露 `PUT /api/v1/orchestration/dags/{id}`，供画布保存草稿。
  - 新增 `OrchestrationServiceTest`，覆盖 update 持久化 definition、version 递增和跨租户拒绝。
  - 前端 `OrchestrationAPI` 增加 `updateDag`。
  - `DagCanvas` 保存按钮在图级校验通过后，新建路由调用 `createDag`，真实 UUID 路由调用 `updateDag`；新建成功后替换 URL 到 `/orchestration/pipelines/{dagId}`。
  - 保存 payload 同时写顶层 `nodes/edges` 和 `operatorGraph`，兼容当前画布与 DWD 编译读取。
  - 验证命令通过：`mvn -q -pl module-orchestration -am test -Djacoco.skip=true`、`pnpm exec tsc --noEmit`、`pnpm build`、`mvn -q install -DskipTests -Djacoco.skip=true`、`git diff --check`。
  - 重启后端后 OpenAPI 确认 `/api/v1/orchestration/dags/{id}` 已包含 `put`。
  - 浏览器验证：新建画布点击保存产生 `POST /operators/graph/validate => 200` 和 `POST /orchestration/dags => 200`，URL 替换为真实 DAG `a3504d0f-3a4f-4462-bba3-5d55f6857e40`。
  - 重新导航到该 UUID URL 后，页面通过 `GET /orchestration/dags/{id} => 200` 从后端加载；DB 反查 `definition.kind=operator_graph`、`operatorGraph.nodes=4`。
  - 再次点击保存产生 `POST /operators/graph/validate => 200` 和 `PUT /orchestration/dags/{id} => 200`，DB version 从 1 递增到 2。
- 遇到的问题：
  - 浏览器重载真实 UUID 时出现一次 Keycloak token endpoint 400 控制台资源错误，但 DAG GET/validate/create/update 均为 200，判定为既有认证刷新噪声，不阻塞本轮。
- 创建/修改的文件：
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/api/DagController.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/repository/DagRepository.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/service/OrchestrationService.java`
  - `onelake-app/module-orchestration/src/test/java/com/onelake/orchestration/service/OrchestrationServiceTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/orchestration/DagCanvas.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 59：流水线与算子市场阶段三流水线列表真实化
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 读取 `PipelineList` 当前 mock 表格、`OrchestrationAPI.listDags/triggerDag` 和 `Dag` 类型。
  - `PipelineList` 移除硬编码 `pipelines`，改为页面加载时调用 `OrchestrationAPI.listDags()`。
  - 保留原表格布局，真实展示 DAG 名称、dagster job、version、enabled/DRAFT 状态和最近运行占位。
  - 草稿 DAG 的触发按钮禁用，避免把 disabled DAG 伪装成可运行；已启用 DAG 才调用真实 `triggerDag`。
  - 页面增加加载、错误和重试状态。
  - 运行 `pnpm exec tsc --noEmit`、`pnpm build` 和 `git diff --check` 通过。
  - 浏览器打开 `http://localhost:5173/orchestration/pipelines`，真实请求 `GET /api/v1/orchestration/dags => 200`，表格展示 4 条本地 DAG，其中包含 `order_pipeline` 草稿 v2。
  - 点击 `order_pipeline` 的“打开画布”进入 `/orchestration/pipelines/a3504d0f-3a4f-4462-bba3-5d55f6857e40`，控制台 0 error。
- 创建/修改的文件：
  - `onelake-app/web-console/src/pages/orchestration/PipelineList.tsx`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 60：流水线与算子市场阶段三运行实例真实化
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 读取 `RunInstances.tsx`、`OrchestrationAPI`、`JobRun`、`JobRunDTO`、`JobRunRepository`、`DagController` 和 `OrchestrationService`。
  - 后端新增租户范围运行实例查询：`GET /api/v1/orchestration/runs?page=&size=`，先取当前租户 DAG，再以 DAG id 集合过滤 `orchestration.job_run`。
  - `JobRunDTO` 补充 `dagName/dagsterJob`，避免前端用 mock 字段拼流水线名称。
  - 前端新增 `JobRun` 类型与 `OrchestrationAPI.listRuns/listDagRuns`，`RunInstances` 移除静态数组，接入真实分页、加载、错误、空态和刷新。
  - 移除原页面指向采集任务的假“日志/重试”动作，改为打开对应流水线，避免跨模块伪导航。
  - 本地写入验证运行实例 `codex-stage60-run-001` 到 DAG `a3504d0f-3a4f-4462-bba3-5d55f6857e40`。
  - 验证命令通过：`mvn -q -pl module-orchestration -am test -Djacoco.skip=true`、`mvn -q -pl bootstrap -am -DskipTests compile`、`mvn -q install -DskipTests -Djacoco.skip=true`、`pnpm exec tsc --noEmit`、`pnpm build`、`git diff --check`。
  - 浏览器验证：登录 `dev/dev123456` 后打开 `/orchestration/runs`，`GET /api/v1/orchestration/runs?page=0&size=20 => 200`，表格展示 `codex-stage60-run-001`、`order_pipeline`、`SUCCESS`、耗时 3.0m，控制台 0 error。
- 遇到的问题：
  - 第一次 runtime 验证 `/api/v1/orchestration/runs` 返回 `NoResourceFoundException`，原因是后端启动使用 `.m2` 旧 `module-orchestration` SNAPSHOT jar；执行 `mvn install` 并进程级重启后 endpoint 注册正确，未登录直连返回 401，浏览器登录态返回 200。
- 创建/修改的文件：
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/api/JobRunController.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/dto/JobRunDTO.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/repository/JobRunRepository.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/service/OrchestrationService.java`
  - `onelake-app/module-orchestration/src/test/java/com/onelake/orchestration/service/OrchestrationServiceTest.java`
  - `onelake-app/web-console/src/api/index.ts`
  - `onelake-app/web-console/src/pages/orchestration/RunInstances.tsx`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 61：流水线与算子市场阶段三流水线最近运行真实聚合
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 读取 `DagDTO`、`OrchestrationService.listDags`、`JobRunRepository`、`PipelineList` 和 `Dag/JobRun` 前端类型。
  - `DagDTO` 新增 `lastRun: JobRunDTO`，`OrchestrationService.listDags/getDag` 为每条 DAG 附加最近一次运行摘要。
  - `JobRunRepository` 新增 `findFirstByDagIdOrderByStartedAtDesc`。
  - `PipelineList` 的“最近运行”列从固定 `-` 改为展示 `lastRun.status`、`dagsterRunId/id` 和开始时间。
  - 补充 `OrchestrationServiceTest.listDagsIncludesLatestRunMetadata`，覆盖列表 DTO 最近运行聚合。
  - 验证命令通过：`mvn -q -pl module-orchestration -am test -Djacoco.skip=true`、`mvn -q install -DskipTests -Djacoco.skip=true`、`pnpm exec tsc --noEmit`、`pnpm build`、`git diff --check`。
  - 浏览器验证：打开 `/orchestration/pipelines`，`GET /api/v1/orchestration/dags => 200`，`order_pipeline` 行展示 `SUCCESS codex-stage60-run-001 2026/6/23 06:57:14`，控制台 0 error。
- 创建/修改的文件：
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/dto/DagDTO.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/repository/JobRunRepository.java`
  - `onelake-app/module-orchestration/src/main/java/com/onelake/orchestration/service/OrchestrationService.java`
  - `onelake-app/module-orchestration/src/test/java/com/onelake/orchestration/service/OrchestrationServiceTest.java`
  - `onelake-app/web-console/src/pages/orchestration/PipelineList.tsx`
  - `onelake-app/web-console/src/types/index.ts`
  - `task_plan.md`
  - `findings.md`
  - `progress.md`

### 阶段 62：流水线与算子市场阶段三触发运行失败可观测
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `triggerDag` 改为先保存 `QUEUED` run，再调用 Dagster；成功后更新 `RUNNING`，异常时更新 `FAILED/finishedAt`。
  - 为业务异常增加 `@Transactional(noRollbackFor = BizException.class)`，避免 `Dagster 未返回 runId` 时失败 run 被事务回滚。
  - `PipelineList` 触发成功或失败后都会重新加载 DAG 列表，让 `lastRun` 及时刷新。
  - 本地临时启用 `order_pipeline` 后触发，`POST /api/v1/orchestration/dags/a3504d0f-3a4f-4462-bba3-5d55f6857e40/run?trigger=MANUAL` 返回 400。
  - DB 验证最新 `job_run` 为 `b99b8175-bdc8-452d-b58d-0bab92081547`、`status=FAILED`、`finished_at` 已写入。
  - 浏览器验证 `/orchestration/pipelines` 的 `order_pipeline.lastRun` 显示 `FAILED`，`/orchestration/runs` 显示同一条失败运行历史。
  - 验证后将临时启用的 `order_pipeline` 恢复为 `enabled=false`。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `mvn -q install -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm exec tsc --noEmit` 通过。
  - `pnpm build` 通过。
  - `git diff --check` 通过。
  - 浏览器截图：`stage62-run-failure-visible.png`。

### 阶段 63：流水线与算子市场阶段三触发就绪真实表达
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `DagDTO` 新增 `triggerable` 与 `triggerBlockedReason`。
  - `OrchestrationService` 统一计算触发就绪：草稿/禁用、缺少 Dagster job、`sql_workbench_draft` 占位作业均返回不可触发原因。
  - `triggerDag` 在创建 run 与调用 Dagster 前拦截不可执行草稿，避免把本地前置条件伪装成一次失败运行。
  - `PipelineList` 改用后端触发就绪字段展示 `可触发/待绑定/草稿`，并禁用不可触发按钮。
  - 浏览器临时将 `order_pipeline` 置为 `enabled=true`，API 返回 `triggerable=false`、`triggerBlockedReason=当前为画布草稿，尚未绑定可执行 Dagster 作业`；页面显示 `待绑定` 且触发按钮禁用。
  - 验证后恢复 `order_pipeline enabled=false`，最终页面显示 `草稿`、最近运行保留阶段 62 的 `FAILED` 历史、触发按钮禁用。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `mvn -q install -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm exec tsc --noEmit` 通过。
  - `pnpm build` 通过。
  - `git diff --check` 通过。
  - 后端健康检查 `GET /actuator/health` 返回 200。
  - 浏览器截图：`stage63-trigger-readiness.png`。

### 阶段 64：流水线与算子市场阶段三 DWD DAG 真实触发闭环
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 复核 Dagster code location：`onelake` / `onelake-loc` 已暴露 `onelake_dbt_model_run` 和 `onelake_sync_task_schedule_reconcile`。
  - 复核本地两条 VALIDATED DWD 模型，均有 `dbt_model_name`、`artifact_path`、`orchestration_dag_id` 和 `dagster_job=onelake_dbt_model_run`。
  - 扩展 `DagsterClient`，支持携带 `runConfigData` 和 execution tags 的 `launchRun`。
  - `OrchestrationService.triggerDag` 对 `definition.kind=DWD_MODEL_DAG` 创建 `modeling.model_run`，构造 `run_dwd_model` 所需配置，并将 model run id 写入 Dagster tags。
  - 触发成功后同步回写 `orchestration.job_run.dagster_run_id/status` 与 `modeling.model_run.dagster_run_id/status`。
  - 临时启用 DAG `94f21184-752f-40ea-9c65-1a5ee00b3699`，调用 `POST /api/v1/orchestration/dags/{id}/run?trigger=MANUAL` 返回 200，编排 run 为 `3f2ff034-0e63-4cf4-aac1-075399906580`。
  - DB 验证 `orchestration.job_run` 与 `modeling.model_run` 都写入同一 Dagster run id `aa4375e6-1b6c-4dd9-90cc-bb0fbccda024`。
  - Dagster GraphQL 验证 run `STARTED`，tags 包含 `onelake.model_id`、`onelake.model_run_id`、`onelake.orchestration_run_id`、`onelake.tenant_id`、`onelake.dbt_model`。
  - 验证后恢复临时启用的 DWD DAG 为 `enabled=false`。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `mvn -q install -DskipTests -Djacoco.skip=true` 通过。
  - `git diff --check` 通过。
  - `GET /actuator/health` 返回 200。

### 阶段 65：流水线与算子市场阶段三 Dagster 运行状态刷新
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `DagsterClient` 增加 `getRunStatus`，读取 Dagster run `STARTED/SUCCESS/FAILURE/CANCELED` 与 started/finished 时间。
  - 编排运行列表和 DAG 内运行列表读取时刷新非终态 `orchestration.job_run`，并同步刷新 `DagDTO.lastRun`。
  - DWD DAG 的终态同步到 `modeling.model_run`，保持编排 run、模型 run 与 Dagster run 三方状态一致。
  - 修复 PostgreSQL 无法推断 `Instant` 参数类型的问题，将 JDBC 写入时间显式转换为 `java.sql.Timestamp`。
  - 对已终态 `job_run` 增加 DWD model_run 补偿同步，避免上一次同步失败后无法恢复。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `mvn -q install -DskipTests -Djacoco.skip=true` 通过。
  - `git diff --check` 通过。
  - API `GET /api/v1/orchestration/dags/94f21184-752f-40ea-9c65-1a5ee00b3699/runs?page=0&size=20` 返回 run `3f2ff034-0e63-4cf4-aac1-075399906580` 为 `SUCCESS`。
  - DB 验证 `orchestration.job_run` 与 `modeling.model_run` 均写入同一 Dagster run `aa4375e6-1b6c-4dd9-90cc-bb0fbccda024` 的终态和完成时间。

### 阶段 66：流水线与算子市场阶段三编排触发 DWD 尾链一致性
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 新增 common 接口 `DwdModelRunSynchronizer`，由 `DwdModelService` 实现 `refreshByDagsterRunId`。
  - `OrchestrationService` 在刷新 DWD Dagster 终态时优先调用 modeling 同步器，复用原有 artifact 解析、`modeling.model.loaded/failed` 事件发布和质量/血缘尾链。
  - 保留 JDBC 状态兜底：同步器不存在或失败时仍可回写 `modeling.model_run` 基础状态。
  - 为 modeling 同步器和 orchestration 委派路径补单元测试。
  - 临时启用 DAG `94f21184-752f-40ea-9c65-1a5ee00b3699`，触发编排 run `34b4f37a-53ab-4d18-ae8c-8acbe1ecd724`，Dagster run 为 `b8907152-7e50-431a-b24a-6826653f947d`。
  - 验证后恢复临时启用的 DWD DAG 为 `enabled=false`。
- 验证：
  - `mvn -q -pl module-orchestration,module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q install -DskipTests -Djacoco.skip=true` 通过。
  - `git diff --check` 通过。
  - Dagster GraphQL 验证 run `b8907152-7e50-431a-b24a-6826653f947d` 为 `SUCCESS`。
  - API 运行列表返回 run `34b4f37a-53ab-4d18-ae8c-8acbe1ecd724` 为 `SUCCESS`，started/finished 时间来自 Dagster。
  - DB 验证 `modeling.model_run f29186c4-5448-4e24-8add-42a8a0a0bbae` 为 `SUCCEEDED`，`artifacts_path=target/run_results.json`，`rows_written=10`。
  - Outbox 事件 `modeling.model.loaded` 已发布，Redis consumer group `catalog/quality` lag 为 0。
  - Catalog 资产 `dwd.dwd_trade_operator_manifest_df` 行数为 10、质量分为 100，血缘为 `ods.ods_codex_orders -> dwd.dwd_trade_operator_manifest_df`。
  - Quality 结果包含 `DBT_BUILD/NOT_NULL/UNIQUE` 三条规则，均通过。

### 阶段 67：流水线与算子市场阶段三 DWD 运行资源观测
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 复核 `DwdModelRunDTO` 和前端 `DwdModelRun` 已有 `resourceGroup/computeProfile/estimatedScanBytes/actualScanBytes/costEstimate/retryCount` 字段。
  - 在资产详情 DWD 模型 tab 的“最近运行”摘要中展示资源组、计算画像、扫描量、成本估算和重试次数。
- 验证：
  - `pnpm --dir onelake-app/web-console build` 通过。
  - `git diff --check` 通过。
  - DB 验证 run `f29186c4-5448-4e24-8add-42a8a0a0bbae` 为 `SUCCEEDED`，`resource_group=default`，`compute_profile=trino-small`，`rows_written=10`。
  - 浏览器访问 `/lakehouse/tables/6b9d7bfc-ad10-4a00-a0f8-12b828b30587`，DWD 模型 tab 显示 `SUCCEEDED/MANUAL/写入 10/default/trino-small/扫描 - / 成本 - / 重试 0`。

### 阶段 68：流水线与算子市场阶段三画布算子面板真实化
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `DagCanvas` 左侧算子面板从 `OperatorAPI.listOperators()` 读取真实算子市场数据，替换 7 个硬编码原型算子。
  - 按 category 分组展示市场算子，并用 `operatorRef` 将画布节点外观与市场元数据对齐。
  - 保留现有保存、图级校验和 DWD DAG 加载行为。
- 验证：
  - `pnpm --dir onelake-app/web-console build` 通过。
  - `git diff --check` 通过。
  - 浏览器访问 `/orchestration/pipelines/94f21184-752f-40ea-9c65-1a5ee00b3699` 控制台 0 error。
  - 网络验证 `/api/v1/orchestration/operators` 和 `/api/v1/orchestration/dags/94f21184-752f-40ea-9c65-1a5ee00b3699` 均返回 200。
  - 页面左侧展示标准化、关联、加密、聚合、输出、输入、脱敏、治理、质量门禁、转换等市场分类和完整内置算子名称。

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
| 2026-06-23 CST | Stage 65 同步 DWD `model_run` 时 PostgreSQL 无法推断 `java.time.Instant` 参数类型 | 1 | 将 JDBC 时间参数转换为 `java.sql.Timestamp`，并让终态 run 也可补偿同步 DWD 状态 |
| 2026-06-23 CST | Stage 66 临时恢复 DWD DAG 时误写 `orchestration.dag.updated_at`，该表不存在该列 | 1 | 改为只更新 `enabled` 字段；验证完成后恢复 `enabled=false` |
| 2026-06-23 CST | 浏览器验证资产详情时 Keycloak token endpoint 出现一次 400 | 1 | 判定为既有 token 刷新噪声；目标 catalog/modeling 接口均已返回 200，页面数据已加载 |
| 2026-06-23 CST | Stage 69 注册弹窗初次“仅校验”时报 `Cannot read properties of undefined (reading 'trim')` | 1 | Modal/Form 挂载后再写默认值，并在构造 Manifest 前调用 `form.validateFields()` |
| 2026-06-23 CST | Stage 69 发布版本弹窗叠在详情弹窗上方时点击被底层 Modal wrap 拦截 | 1 | 打开发布版本编辑器前先关闭详情弹窗，再打开版本 Modal |
| 2026-06-23 CST | Stage 72 类型检查失败：`inferredOperator` 在声明前被动态属性面板引用 | 1 | 将 `inferredOperator` 提升为组件外函数后类型检查通过 |
| 2026-06-23 CST | Stage 74 浏览器拖拽脚本首次命中外层 main 容器，节点坐标未变化 | 1 | 改为筛选 `position:absolute` 且宽度为 140、文本包含 `operatorRef` 的真实节点元素后验证通过 |

### 阶段 70：资产发现与湖仓分层表管理边界升级
- **状态：** in_progress
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
  - 备注：浏览器 console 日志缓冲中仍有本轮修复前产生的 AntD static message warning；本轮触碰的 `CatalogSearch` 与 `AssetDetail` 已改为 `AntdApp.useApp()`。

### 阶段 72：业务术语表生产化 P0 后端与 Catalog 联动
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 按推荐方案新增业务术语主数据、绑定、版本三张表和 `GlossaryController/GlossaryService`。
  - 新增 `BusinessTerm*` DTO/Repository/Entity，支持术语 CRUD、提交审定、审定通过、退回、废弃、字段绑定、绑定失效和版本查询。
  - `DomainEvents` 增加 `modeling.term.created/updated/approved/deprecated/binding_changed`。
  - Catalog 资产查询支持 `keyword/term`，字段 DTO 增加 `terms`，用于资产详情 Schema 展示业务术语。
  - 前端新增 `GlossaryAPI` 与业务术语类型，`Glossary` 页面改为真实接口驱动，支持术语检索、分业务域展示、创建/编辑、提交、审定、废弃和资产字段绑定。
  - `CatalogSearch` 本地搜索纳入字段术语编码/名称，`AssetDetail` Schema 表新增“业务术语”列并可跳转术语表。
- 检查：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `git diff --check -- module-common/src/main/java/com/onelake/common/outbox/DomainEvents.java module-modeling/src/main/java module-modeling/src/test/java bootstrap/src/main/resources/db/migration/modeling/V5__business_glossary.sql` 通过。
  - `mvn -q -pl module-catalog -am test -Djacoco.skip=true` 通过。
  - `git diff --check -- module-catalog/src/main/java module-catalog/src/test/java` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `mvn -q -pl module-modeling,module-catalog -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am compile -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮后端、前端和迁移文件通过。
  - 本地只对 `modeling` schema 执行 Flyway V5，`modeling.business_term`、`business_term_binding`、`business_term_version` 均已存在，`modeling.flyway_schema_history` 记录 version `5` 成功。
  - 后端重启后健康检查 `/actuator/health` 为 `UP`，OpenAPI 已暴露 `/api/v1/modeling/glossary/**`。
  - API 冒烟通过：创建 `CODEX_GMV_102340`，提交后为 `REVIEWING`，审定后为 `APPROVED` v1，绑定 `dwd.dwd_trade_codex_orders_df.amount` 后 Catalog `term` 搜索命中该资产，资产详情 `amount` 字段返回该术语。
  - 浏览器验证通过：`/catalog/glossary` 展示 `CODEX_GMV_102340`、字段绑定统计为 1；`/catalog/assets/70946dc2-d533-42b0-a94f-3af7e73d7b84` 切到 Schema 后 `amount` 展示业务术语；`/catalog/search` 搜索 `CODEX_GMV_102340` 后“可用资产 (1)”且命中 `dwd.dwd_trade_codex_orders_df`。
  - 修复浏览器发现的问题：术语列表新增 `bindingCount`，避免顶部绑定统计为 0；`CatalogSearch` 重复 tag key 改为带来源/索引的 key，消除本轮新验证中的重复 key 风险。

### 阶段 69：流水线与算子市场阶段四自定义算子前端注册发布入口
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `OperatorMarket.tsx` 新增“注册算子”入口，支持填写 Manifest 基础信息、端口、参数 schema、输出 schema、SQL/dbt 模板、质量门禁策略和版本说明。
  - 注册/发布前调用 `OperatorAPI.validateOperator`，校验失败阻断落库；校验通过后分别调用 `registerOperator` 与 `publishVersion`。
  - 自定义/租户私有算子详情中新增“发布新版本”入口，内置算子不展示该入口。
  - 修复注册弹窗初次打开时表单默认值未挂载导致“仅校验”读取 undefined 的问题。
  - 修复详情弹窗与发布版本弹窗叠加导致点击被底层 Modal 拦截的问题。
- 验证：
  - `pnpm --dir onelake-app/web-console build` 通过。
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - API 冒烟：`custom.codex_stage69_phone` validate/register/publish 1.0.1/list 均返回 200。
  - 浏览器验证：`/orchestration/operators` 注册 `custom.codex_stage69_ui`，仅校验通过，注册返回 200；发布新版本后列表展示 latestVersion `1.0.1`。

### 阶段 71：流水线与算子市场阶段四画布从市场添加算子节点
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `DagCanvas` 左侧真实算子市场面板支持点击添加节点到当前 DAG 草稿。
  - 新增节点使用市场算子的 `operatorRef/latestVersion/category/displayName`，并从 Manifest required params 生成默认 `config`。
  - 如果当前选中节点存在且目标算子有输入端口，则自动创建一条临时边，便于进入后端图级校验链路。
- 验证：
  - `pnpm --dir onelake-app/web-console build` 通过。
  - 浏览器打开 `/orchestration/pipelines/94f21184-752f-40ea-9c65-1a5ee00b3699`，`GET /api/v1/orchestration/operators` 与 `GET /api/v1/orchestration/dags/{id}` 均返回 200。
  - 点击 `Codex Stage69 UI 注册算子` 后画布出现新节点，图级校验 `POST /api/v1/orchestration/operators/graph/validate` 返回 200，弹窗显示节点通过，仅保留 `dbt_model` 系统节点 warning。
  - 未保存临时节点，避免污染真实 DWD DAG 草稿。

### 阶段 72：流水线与算子市场阶段四画布节点属性动态化
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 右侧属性面板改为按选中算子的 Manifest 展示 `operatorRef/version/category/compileTarget/inputPorts`。
  - 按 `paramsSchema.properties` 动态生成字符串、数字、布尔、数组和对象参数输入；必填字段展示“必填”标识。
  - 编辑节点名称、SQL 或动态参数时同步更新当前节点状态，图级校验和保存定义构造共用该状态。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过。
  - `git diff --check` 通过。
  - 浏览器验证 DWD DAG 中 `input.ods_table` 的 `sourceFqn` 动态字段可编辑；点击校验后 `POST /api/v1/orchestration/operators/graph/validate` 返回 200，请求体包含 `config.sourceFqn=ods.codex_stage72_canvas_param`。
  - 验证后将前端内存态参数改回 `ods.ods_codex_orders`，未保存临时值。

### 阶段 73：流水线与算子市场阶段四剩余增强核对
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照 `docs/流水线与算子市场算子标准设计方案.md` §6.4、§7.1 和当前 `DagCanvas.tsx` 实现，核对剩余缺口。
  - 确认已完成：真实市场算子面板、添加节点、动态属性面板、Manifest 图级校验、DAG 保存定义构造。
  - 确认未完成：完整 X6 Graph 实例迁移、端口级连线编辑/删边、Spark/Python compileTarget 运行时、完整拖拽图编译器。
  - 本轮选择低风险切片进入阶段 74：先补画布节点拖拽定位，并保持现有保存/校验链路稳定。

### 阶段 74：流水线与算子市场阶段四画布节点拖拽定位最小闭环
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `DagCanvas` 为画布节点增加 pointer 拖拽能力，拖动时更新节点 `x/y`，并同步右侧属性面板坐标显示。
  - SVG 连线按节点最新坐标实时重绘。
  - `buildValidationGraph` 将节点 `x/y` 写入图级校验 payload，保存定义也复用同一份 graph。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过。
  - `git diff --check` 通过。
  - 浏览器打开 `/orchestration/pipelines/94f21184-752f-40ea-9c65-1a5ee00b3699` 控制台 0 error。
  - 拖动 `input.ods_table` 节点，节点页面坐标从 `(542,244)` 移到 `(614,302)`，右侧显示内部坐标 `x 152 / y 158`。
  - 点击校验后 `POST /api/v1/orchestration/operators/graph/validate` 返回 200，请求体中 `input_ods` 带 `x=152,y=158`；弹窗显示 6 节点通过，仅保留 `dbt_model` 系统节点 warning。
  - 验证后刷新页面恢复真实 DAG 状态，未保存临时拖拽坐标。

### 阶段 75：流水线与算子市场阶段四端口连线与 X6 深化
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `DagCanvas` 的边模型补齐 `id/sourcePort/targetPort/validationMessage`，兼容旧 DAG 边结构并在加载时自动补默认端口。
  - 画布节点按 Manifest 输入端口渲染左侧端口按钮，输出端口渲染右侧连接按钮，支持“先点输出、再点输入”创建端口级连线。
  - 新增边选中态和右侧边属性面板，可编辑源节点、源端口、目标节点、目标端口，并支持删除边。
  - 在前端本地补齐连线合法性标注：缺失节点、自环、目标无输入端口、目标端口不存在、`ONE` 输入被多条边占用、重复边和环路均展示为无效红色虚线。
  - 图级校验 payload 继续复用当前画布状态，并把每条边的 `id/sourcePort/targetPort/valid` 传给后端。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check -- onelake-app/web-console/src/pages/orchestration/DagCanvas.tsx` 通过。
  - 浏览器打开 `/orchestration/pipelines/94f21184-752f-40ea-9c65-1a5ee00b3699`，删除并重连合法边后边数从 5 -> 4 -> 5。
  - 再创建一条指向同一 `ONE` 输入端口的非法边后，画布出现红色无效连线，属性面板显示无效状态，删除后恢复 5 条边。
  - 点击“校验”后 `POST /api/v1/orchestration/operators/graph/validate` 返回 200，请求体包含 `sourcePort/targetPort`；响应 `ok=true`，仅保留 `dbt_model` 系统节点 warning。
  - 验证后刷新页面恢复真实 DAG 状态，未保存临时连线变更。

### 阶段 78：流水线与算子市场阶段四算子生命周期治理入口
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `OperatorMarket` 使用后端 `updateOperator` API 补齐自定义/租户私有算子的“废弃/恢复”入口，内置算子不展示该危险操作。
  - 市场统计新增“已废弃”，列表卡片和详情弹窗按 `DEPRECATED` 展示“已废弃”状态。
  - 废弃算子时阻断“安装/锁定版本”和“使用”动作，避免把不可用算子继续加入新 DAG。
  - 恢复算子后重新显示为可用，并恢复安装/使用按钮。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `git diff --check -- onelake-app/web-console/src/pages/orchestration/DagCanvas.tsx onelake-app/web-console/src/pages/orchestration/OperatorMarket.tsx task_plan.md` 通过。
  - 浏览器在 `/orchestration/operators` 打开 `Codex Stage69 UI 注册算子` 详情，点击“废弃”并确认，`PUT /api/v1/orchestration/operators/custom.codex_stage69_ui` 返回 200，请求体为 `{"status":"DEPRECATED"}`。
  - 废弃后详情显示“已废弃”，且“安装/锁定版本”“使用”按钮 disabled。
  - 点击“恢复”并确认，`PUT /api/v1/orchestration/operators/custom.codex_stage69_ui` 返回 200，请求体为 `{"status":"ACTIVE"}`；详情恢复“可用”，按钮重新可用。
  - 验证后已恢复 `custom.codex_stage69_ui` 为 `ACTIVE`，未留下废弃状态污染测试数据。
  - 浏览器控制台仍保留一次本地 Keycloak token endpoint 400 和两条 React Router v7 future warning；阶段 78 的两次状态更新 API 均为 200。

### 阶段 76：业务术语表 M4/M5 影响分析与治理闭环
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `GlossaryController` 新增 `/terms/{id}/impact` 和 `/terms/{id}/version-diff`。
  - `GlossaryService` 聚合术语绑定字段、Catalog 下游资产、Quality 规则、DaaS API、Orchestration DAG、Security PII 记录和治理审批单。
  - 已审定术语编辑时自动转为 `REVIEWING`，并写入 `security.approval_request` 的 `GLOSSARY_CHANGE` 待审批记录。
  - 术语版本快照补齐定义、口径、同义词、负责人、标签和密级，最近版本 diff 可解释字段级变化。
  - `Glossary.tsx` 详情区新增影响分析、风险提示、质量/API/安全/审批汇总、版本历史和最近差异表。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl module-modeling,module-catalog,module-dataservice -am test -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - API 冒烟样本 `CODEX_FULL_NAME_GBTLU`：`GET /impact` 返回 bindings=2、qualityRules=4、apis=3、securityNotices=2、approvals=1；`/version-diff` 返回 `definition/caliberSql/synonyms/status` 变更。
  - 浏览器打开 `/catalog/glossary` 搜索 `CODEX_FULL_NAME_GBTLU`，页面可见“影响分析”“DaaS API”“版本历史与最近差异”和术语编码，截图已保存到 `.run-logs/glossary-impact-browser.png`。

### 阶段 77：业务术语跨模块最小联动闭环
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 新增 `modeling/V6__dwd_mapping_glossary.sql`，为 `data_model_column_mapping` 增加 `term_id/term_code/term_name`。
  - `DwdModelDraftRequest`、`DataModelDTO`、`DataModelColumnMapping` 支持术语字段；`DwdModelService` 保存 DWD 草稿时校验已审定术语并反写 `business_term_binding(source=MODELING)`。
  - 敏感术语绑定字段时写入 `security.pii_scan_record` 待确认记录；建模反写绑定也会触发 DWD 目标字段 PII 待确认。
  - `QualityRules.tsx` 新建规则表单支持按已审定术语筛选资产/字段并带出口径表达式。
  - `TableWizard.tsx` 字段映射行新增“业务术语”选择列，ODS 派生 DWD 时继承源字段已有术语。
  - `DataServicePublisher#createDraft/publish` 会按 `sourceFqn + business_term_binding` 富化 `responseSchema`，`ApiWizard` 和 `ApiDetail` 展示字段术语、定义、口径、密级和动态脱敏提示。
- 验证：
  - 本地仅应用 `modeling/V6__dwd_mapping_glossary.sql` 并登记 `modeling.flyway_schema_history` version=6，未执行全量迁移以避免无关 orchestration 迁移干扰。
  - 真实 API 冒烟样本 `CODEX_FULL_NAME_GBTLU`：手工绑定 `ods.ods_customers_100k.full_name`，建模草稿生成 `dwd.dwd_user_codex_glossary_gbtlu_df.full_name` 的 `MODELING` 绑定。
  - DB 复核：`business_term_binding` 中该术语有 `MANUAL` 与 `MODELING` 两条 ACTIVE 绑定；`security.pii_scan_record` 中源字段和 DWD 字段均为 `PENDING/L3`。
  - DaaS 草稿 `a6f77a56-eeb6-480c-976b-553024d15c0a` 的 `responseSchema` 包含 `termCode=CODEX_FULL_NAME_GBTLU`、`caliberSql=trim(full_name)`、`classification=L3`、`masked=true`。
  - 浏览器打开 API 详情页可见“响应字段与术语”、`CODEX_FULL_NAME_GBTLU` 和“动态脱敏”，截图 `.run-logs/api-detail-glossary-browser.png`。
  - 浏览器打开质量规则新建弹窗可见“业务术语/绑定资产/字段”入口，截图 `.run-logs/quality-term-selector-browser.png`。
  - 浏览器打开 DWD 建模向导，手动修正源表数字导致的命名校验后进入字段步骤，可见“业务术语”列，截图 `.run-logs/table-wizard-term-browser.png`。
  - 运行中发现旧孤儿后端进程仍占用 8080，导致新 jar 未加载；已 `kill -9` 旧 PID 后重启 `onelake-backend-glossary` screen，并重新完成 API 冒烟。

### 阶段 79：流水线与算子市场阶段四 Spark/Python 扩展边界与编译器深化
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案 §2.5，确认 `SPARK/PYTHON` 是扩展点，不属于当前 SQL_DBT 执行闭环，不能在画布图级校验中伪装为可运行。
  - `OperatorService` 将 Manifest 校验拆成 compileTarget 多态契约：`SQL_DBT` 要求 `template.sql`；`SPARK` 要求 `template.kind=SPARK_SQL/PYSPARK` 与对应 `sql/entrypoint`；`PYTHON` 要求 `template.kind=PYTHON` 与 `entrypoint`。
  - 非 SQL_DBT 扩展态必须声明 `resourceHint.defaultResourceGroup` 与 `resourceHint.engine`，且 engine 需与 compileTarget 一致。
  - 图级校验继续阻断非 SQL_DBT 节点，并返回明确的“尚未接入当前 SQL_DBT 图级执行闭环”错误。
  - `OperatorMarket` 注册/发布表单改为完整 `template JSON` 和 `resourceHint JSON`，选择 SPARK/PYTHON 时展示扩展态警示。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - 后端正确目录执行 `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 后重启，`/actuator/health` 为 `UP`。
  - 真实 API：`POST /api/v1/orchestration/operators/validate` 校验合法 SPARK Manifest 返回 `ok=true`，warning 为 `compileTarget=SPARK 当前仅完成 Manifest 契约校验...`。
  - 真实 API：缺少 `resourceHint` 的 SPARK Manifest 返回 `ok=false`，错误为 `compileTarget=SPARK 必须声明 resourceHint.defaultResourceGroup 与 resourceHint.engine`。
  - 浏览器打开注册弹窗，确认存在 `template JSON` 与 `resourceHint JSON`，选择 SPARK 后显示“SPARK 为扩展态”和 Dagster op 落地提示。

### 阶段 80：流水线与算子市场阶段四后端端口级图校验深化
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - `OperatorService.validateGraph` 记录每个节点的入边列表，保留旧的入边总数兼容逻辑。
  - `validateInputPorts` 改为按 Manifest `inputPorts.name/cardinality` 校验端口，单输入端口兼容未声明 `targetPort` 的旧边格式。
  - 多输入算子要求边声明 `targetPort`；未知端口、缺端口、`ONE` 端口多条入边都会返回明确错误。
  - 补充 JOIN 内置算子测试，覆盖缺失 `targetPort`、重复 `left` 端口、正确 `left/right` 三类路径。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - 正确目录执行 `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 后重启，`/actuator/health` 为 `UP`。
  - 真实 API：`join.inner` 两条边均缺 `targetPort` 时 `ok=false`，返回“多输入端口，边必须声明 targetPort”和左右端口缺边错误。
  - 真实 API：两条边都指向 `targetPort=left` 时 `ok=false`，返回“输入端口 left 最多允许 1 条输入边”和 `right` 缺边。
  - 真实 API：`left/right` 分别连入时 `ok=true`。
  - 浏览器控制台在 `/orchestration/operators` 仍有本地 Keycloak token 400、通知 500、运行任务 500；本轮验证接口均为 200，错误不来自阶段 79/80 改动。

### 阶段 81：流水线与算子市场阶段四字段 schema 闭合与治理校验
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 查询本地 `catalog.asset`、`modeling.data_model_source`、`modeling.data_model_column_mapping` 表结构，确认真实字段 schema 来源是 `catalog.asset.columns` 和 DWD 字段映射，不从页面或 mock 推断。
  - `OperatorService.validateGraph` 新增字段 schema 自一致校验：支持 `sourceColumns/inputColumns/outputColumns`、输入/输出节点 `config.columns`、`transform.rename_columns` 的 mapping/mappings 输出推导。
  - 字段引用校验覆盖 `column/columns/requiredColumns/keys/groupBy/partitionBy/orderBy/uniqueKey/incrementalColumn` 以及 mapping 源字段；无 source schema 时只做 mapping/output 自一致检查并返回 warning。
  - 敏感字段治理校验读取 `sourceColumns.classification/piiType/suggestLevel` 和显式 `sensitiveColumns`，输出敏感字段未经过 `MASK/ENCRYPT` 算子时报错。
  - 兼容 DWD 生成图中 `mask.partial` 使用 `columns` 批量承载敏感字段的现状，避免单字段 Manifest 参数误伤已有图。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过，重启后端为 `onelake-backend-stage81`，`/actuator/health` 为 `UP`。
  - 真实 API：缺失字段 `missing_col` 返回 `ok=false`，错误为 `节点 quality_gate 引用了不存在的字段: missing_col`。
  - 真实 API：`user_phone` 为 L3/PII 且未经过 MASK/ENCRYPT 时返回 `ok=false`，错误为 `敏感字段 user_phone 透传到输出但未经过 MASK/ENCRYPT 算子`。
  - 真实 API：`user_phone` 经过 `mask.partial` 后返回 `ok=true`。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 与阶段相关文件尾随空白检查通过。
  - 浏览器 `/orchestration/operators` 可加载真实算子列表，注册弹窗仍显示 `template JSON` 与 `resourceHint JSON`；控制台仅有 React Router v7 future warning。

### 阶段 82：流水线与算子市场阶段四资源组与执行资源契约校验
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案 §2.4 资源校验与 ODS->DWD 资源契约，确认当前没有业务侧资源组注册表，真实运行数据统一为 `TRINO_DBT/default/trino-small`。
  - `OperatorService.validateResourceHint` 在 Manifest 自校验阶段识别不支持的 `defaultResourceGroup/engine` 组合；SQL_DBT 未声明资源组时继续兼容默认值。
  - `OperatorService.validateGraph` 新增 graph/node `engine/resourceGroup/computeProfile/resourceProfile` 校验，当前受控允许 `TRINO_DBT/default|rg-default/trino-small|trino-medium|trino-large`。
  - 显式声明未接入 engine、未知 resourceGroup 或不属于该 group 的 computeProfile 时返回错误；未声明图级 resourceGroup 时返回默认兼容 warning。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过；清理旧 Java 残留进程后，后端 `onelake-backend-stage82` 监听 8080，PID 69939，健康检查 `UP`。
  - 真实 API：`resourceGroup=warehouse-xl` 返回 `ok=false`，错误包含 `resourceGroup 不存在或不支持当前 engine: warehouse-xl/TRINO_DBT`。
  - 真实 API：`computeProfile=spark-large` + `resourceGroup=default` 返回 `ok=false`，错误包含 `computeProfile 不存在或不属于当前 resourceGroup: spark-large/default`。
  - 真实 API：`TRINO_DBT/default/trino-small` 返回 `ok=true`。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 与阶段相关文件尾随空白检查通过。
  - 浏览器刷新 `/orchestration/operators` 后仍显示可见算子 67、内置 65、自定义 2、注册入口可见；控制台仅有 React Router v7 future warning。

### 阶段 83：流水线与算子市场阶段四 DWD 编译产物与质量门禁算子对齐
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案 §5.1，确认当前 `schema.yml` 质量测试产物仍由 `DataModelColumnMapping.primaryKey` 硬编码生成，未消费 operator graph 中 `QUALITY_GATE` 节点配置。
  - `DwdModelService.compileArtifacts` 先生成 `operatorGraph`，再把同一份图传给 `generateSchemaYaml`。
  - `generateSchemaYaml` 新增质量门禁提取逻辑：读取 `QUALITY_GATE`/`gate.*` 节点的 `config.columns` 与 `config.tests`，当前落地支持 dbt 内置 `not_null/unique`。
  - 保留无质量门禁配置时按主键生成 `not_null/unique` 的兼容兜底，避免历史草稿或异常图丢失基础测试。
  - 单测补充断言：DWD compile 返回的 operatorGraph 包含 `gate.not_null` 和 `tests=["not_null","unique"]`，落盘 `schema.yml` 同时包含 `not_null/unique`。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - 重启后端为 `onelake-backend-stage83`，PID 3963，`/actuator/health` 为 `UP`。
  - 真实 API：`POST /api/v1/modeling/models/ccaf721f-d39d-4cea-9a73-89cea59313ce/compile` 返回 `code=0`，operatorGraph 中 `quality_gate.config={"columns":["id"],"tests":["not_null","unique"],"actionOnViolation":"FAIL"}`。
  - 真实落盘产物：`onelake-app/dbt/models/intermediate/dwd_user_codex_glossary_gbtlu_df.yml` 只在 `id` 列下生成 `not_null/unique`，`full_name/age` 未被误加测试。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮相关文件通过。

### 阶段 84：流水线与算子市场阶段四质量门禁 dbt generic tests 覆盖增强
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案质量门禁清单，确认 `gate.enum` 和 `gate.referential` 可分别映射为 dbt generic tests `accepted_values` 与 `relationships`，无需新增执行引擎。
  - 将 `DwdModelService` 内部 dbt test 表示从纯字符串升级为 `DbtTestSpec(name, arguments)`，保持 `not_null/unique` 的简写 YAML 输出。
  - `gate.enum` 读取 `config.column/config.values` 并输出 `accepted_values.values`。
  - `gate.referential` 读取 `config.column/config.refModel/config.refColumn` 并输出 `relationships.to/field`。
  - 增加单测，使用伪造 operator graph 覆盖 `accepted_values` 与 `relationships` YAML 形态。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - 重启后端为 `onelake-backend-stage84`，PID 30795，`/actuator/health` 为 `UP`。
  - 真实 API 回归：`POST /api/v1/modeling/models/ccaf721f-d39d-4cea-9a73-89cea59313ce/compile` 返回 `code=0`，默认 `quality_gate` 仍为 `columns=["id"]`、`tests=["not_null","unique"]`，落盘 yml 仍包含 `not_null/unique` 且未出现 `accepted_values/relationships` 误写。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮相关文件通过。

### 阶段 85：流水线与算子市场阶段四范围/正则门禁 dbt macro 落地
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案质量门禁清单，确认 `gate.range/gate.regex` 不是 dbt 内置 generic tests，需要 OneLake 自定义 test macro 才能真实执行。
  - 在 `onelake-app/dbt/macros/onelake_macros.sql` 新增 `onelake_range` 与 `onelake_regex` generic tests：返回违规记录，交由 dbt test 判定失败。
  - `DwdModelService` 支持 `gate.range` → `onelake_range(arguments.min_value/max_value)`，`gate.regex` → `onelake_regex(arguments.pattern)`。
  - 带参数 generic tests 使用 dbt 1.11 推荐的 `arguments:` 结构，避免继续引入 MissingArgumentsProperty deprecation。
  - 扩展单测，覆盖 `accepted_values/relationships/onelake_range/onelake_regex` 四类带参数 tests 的 YAML 形态。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - 临时最小 dbt 项目复制当前 macro 后执行 `uvx --from dbt-trino dbt parse --profiles-dir <tmp> --project-dir <tmp> --no-partial-parse` 通过。
  - 当前完整 dbt 项目的 `dbt parse` 仍被既有生成模型 `dwd_trade_operator_manifest_df` 依赖缺失 source `ods.ods_codex_orders` 阻断；该问题来自本地生成产物/source 漂移，不是新增 macro 语法错误。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - 重启后端为 `onelake-backend-stage85`，PID 44320，`/actuator/health` 为 `UP`。
  - 真实 API 回归：`POST /api/v1/modeling/models/ccaf721f-d39d-4cea-9a73-89cea59313ce/compile` 返回 `code=0`，默认 `quality_gate` 仍为 `columns=["id"]`、`tests=["not_null","unique"]`，未误写 `onelake_range/onelake_regex`。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮相关文件通过。

### 阶段 86：流水线与算子市场阶段四行数门禁模型级 dbt test 落地
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案质量门禁清单，确认 `gate.row_count` 是模型级质量门禁，应输出到 `models[].tests`，不应写到列级 `columns[].tests`。
  - 在 `onelake-app/dbt/macros/onelake_macros.sql` 新增 `onelake_row_count` generic test，返回低于/高于阈值的违规计数行。
  - `DwdModelService.generateSchemaYaml` 新增模型级 tests 渲染，列级 tests 与模型级 tests 分流，避免 row_count 被误挂到字段下。
  - 扩展单测，覆盖 `onelake_row_count` 的模型级 YAML 输出，以及列级 `accepted_values/relationships/onelake_range/onelake_regex` 不退化。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - 临时最小 dbt 项目复制当前 macro 后执行 `uvx --from dbt-trino dbt parse --profiles-dir <tmp> --project-dir <tmp> --no-partial-parse` 通过，覆盖 `onelake_row_count/onelake_range/onelake_regex`。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - 重启后端为 `onelake-backend-stage86`，screen `53400.onelake-backend-stage86`，PID 53475，`/actuator/health` 为 `UP`。
  - 真实 API 回归：`POST /api/v1/modeling/models/ccaf721f-d39d-4cea-9a73-89cea59313ce/compile` 返回 `code=0`，默认 `quality_gate` 仍为 `columns=["id"]`、`tests=["not_null","unique"]`，未误写 `onelake_row_count`。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮相关文件通过。

### 阶段 87：流水线与算子市场阶段四 DWD sources.yml 聚合一致性修复
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案 §5.1 的 dbt 产物要求，复核完整 dbt project parse 失败原因：`models/generated/sources.yml` 是共享 source manifest，但 DWD compile 每次只写当前模型 source，导致历史已验证 DWD SQL 依赖缺失 source。
  - `DwdModelService.compileArtifacts` 改为输出当前模型 source + 当前租户已 `VALIDATED` 且有 `artifactPath` 的 DWD 模型 sources，并按 schema/table 聚合到同一个 `sources.yml`。
  - 增加单测 `compileArtifactsAggregatesSourcesForExistingCompiledDwdModels`，覆盖编译新模型时保留既有已编译模型的 ODS source。
  - 验证前端构建时发现 `LineageGraph.tsx` 的可选 `dagre` 动态导入会被 Vite 生产构建解析并失败；保持“未安装则降级网格布局”的原意，改为运行时动态导入。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - 重启后端为 `onelake-backend-stage87`，screen `74534.onelake-backend-stage87`，PID 74922，`/actuator/health` 为 `UP`。
  - 真实 API 回归：`POST /api/v1/modeling/models/ccaf721f-d39d-4cea-9a73-89cea59313ce/compile` 返回 `code=0`，`engine=TRINO_DBT`、`resourceGroup=default`、`computeProfile=trino-small`，operatorGraph 仍包含 `QUALITY_GATE`。
  - 真实落盘产物：`onelake-app/dbt/models/generated/sources.yml` 同时包含 `ods_codex_orders` 与 `ods_customers_100k`，历史 `dwd_trade_operator_manifest_df.sql` 的 `source('ods','ods_codex_orders')` 依赖已恢复。
  - 完整 dbt project：`uvx --from dbt-trino dbt parse --profiles-dir . --no-partial-parse` 通过；仅保留既有 `models/marts/schema.yml` 的 `dbt_utils.accepted_range` 参数 deprecation warning 和 unused staging 配置 warning。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮相关文件通过。

### 阶段 88：流水线与算子市场阶段四 freshness 质量门禁 dbt source 产物落地
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案质量门禁清单，确认 `gate.freshness` 的真实 dbt 产物应写入 `sources.yml` 的 source table：`loaded_at_field` + `freshness.warn_after/error_after`，而不是列级 generic test。
  - `DwdModelService.generateSourceYaml` 新增 source freshness 解析：支持 `operatorRef=gate.freshness` 或 `tests/type/test=freshness`，读取 `sourceFqn/assetFqn`、`column/loadedAtField`、`maxDelay/warnAfter/errorAfter` 和 `actionOnViolation`。
  - `maxDelay` 支持 `24h/30m/2d/1w` 以及 `{count, period}` 形式；`actionOnViolation=WARN` 输出 `warn_after`，默认/`FAIL` 输出 `error_after`，显式 `warnAfter/errorAfter` 优先。
  - 聚合历史已验证 DWD sources 时容错解析历史 `operatorGraph`，将已有模型的 freshness 配置保留到共享 `sources.yml`。
  - 增加单测 `compileArtifactsWritesSourceFreshnessForExistingCompiledDwdModels`，覆盖历史已验证模型的 freshness 配置输出。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - 重启后端为 `onelake-backend-stage88`，screen `89055.onelake-backend-stage88`，PID 89431，`/actuator/health` 为 `UP`。
  - 真实 API 验证：临时将模型 `49f1e2f0-7a8d-4911-a6cc-7a467bb1b772` 的 operatorGraph 写入 `gate.freshness(column=updated_at,maxDelay=24h,actionOnViolation=WARN)` 后，调用 `POST /api/v1/modeling/models/ccaf721f-d39d-4cea-9a73-89cea59313ce/compile` 返回 `code=0`。
  - 真实 freshness 产物：临时生成的 `sources.yml` 中 `ods_codex_orders` 包含 `loaded_at_field: "updated_at"`、`freshness.warn_after.count=24`、`period=hour`；随后已恢复 DB 原始 operatorGraph 并重新 compile，当前仓库 `sources.yml` 未残留测试 freshness。
  - freshness 形态下完整 dbt project `dbt parse` 通过；恢复后完整 dbt project `uvx --from dbt-trino dbt parse --profiles-dir . --no-partial-parse` 通过，仅保留既有 `models/marts/schema.yml` deprecation warning 与 unused staging 配置 warning。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮相关文件通过。

### 阶段 89：流水线与算子市场阶段四 custom_sql 质量门禁只读断言落地
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案质量门禁清单，确认 `gate.custom_sql` 不能直接把任意模板字符串写入 dbt；本轮采用“只读、单语句、仅当前模型”的最小安全协议。
  - 在 `onelake-app/dbt/macros/onelake_macros.sql` 新增 `onelake_custom_sql` generic test：`assertion_sql` 返回违规记录，dbt test 非空即失败；运行时将 `__ONELAKE_MODEL__` 替换为当前 dbt model relation。
  - `DwdModelService` 将 `gate.custom_sql/custom_sql` 映射为模型级 dbt test，读取 `assertionSql/assertion_sql/sql`。
  - 编译阶段复用 `ReadOnlySqlValidator`：将 `{{ model }}` 占位符替换为安全临时表名后校验单条只读语句，并拒绝未使用 `{{ model }}` 或引用其他表的断言 SQL。
  - DWD compile 新增已保存扩展质量门禁合并逻辑：仅保留 `gate.freshness/gate.custom_sql` 节点，避免历史默认 graph 被重复合入，也避免自定义门禁被默认生成图覆盖。
  - 增加单测，覆盖 `generateSchemaYaml` 中 `onelake_custom_sql` YAML 形态，以及 compile 阶段保存过的 `gate.custom_sql` 能进入返回的 operatorGraph 和落盘 schema。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - 重启后端为 `onelake-backend-stage89`，screen `4187.onelake-backend-stage89`，PID 4601，`/actuator/health` 为 `UP`。
  - 真实 API 验证：临时将模型 `ccaf721f-d39d-4cea-9a73-89cea59313ce` 的 operatorGraph 写入 `gate.custom_sql(assertionSql="select * from {{ model }} where age < 0")` 后，调用 compile 返回 `code=0`，返回 operatorGraph 包含 `gate.custom_sql`。
  - 真实 custom SQL 产物：临时生成的 `dwd_user_codex_glossary_gbtlu_df.yml` 包含 `onelake_custom_sql.arguments.assertion_sql: "select * from __ONELAKE_MODEL__ where age < 0"`；随后已恢复 DB 原始 operatorGraph 并重新 compile，当前仓库 schema 未残留测试 custom SQL。
  - custom SQL 形态下完整 dbt project `dbt parse` 通过；恢复后完整 dbt project `uvx --from dbt-trino dbt parse --profiles-dir . --no-partial-parse` 通过，仅保留既有 `models/marts/schema.yml` deprecation warning 与 unused staging 配置 warning。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 对本轮相关文件通过。

### 阶段 90：流水线与算子市场阶段四 dbt 校验噪声清理
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 将 `onelake-app/dbt/models/marts/schema.yml` 中既有 `dbt_utils.accepted_range` 顶层参数改为 dbt 1.11 推荐的 `arguments.min_value`，与 DWD 质量门禁输出风格保持一致。
  - 移除 `onelake-app/dbt/dbt_project.yml` 中当前没有资源命中的 `models.onelake.staging` 配置，避免 parse 阶段 unused configuration warning。
- 验证：
  - `uvx --from dbt-trino dbt parse --profiles-dir . --no-partial-parse` 通过，已无 dbt deprecation warning 和 unused staging config warning。

### 阶段 91：流水线与算子市场阶段四资源组与计算画像注册表闭环
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照 Stage82 留下的“静态资源契约不是完整资源组后台管理”缺口，新增 `orchestration.resource_group` 与 `orchestration.compute_profile` 迁移。
  - 迁移种子化 4 个内置资源组：`default/rg-default/TRINO_DBT`、`spark-default/SPARK`、`python-default/PYTHON`，以及 11 个默认计算画像。
  - 新增 `ResourceGroup`、`ComputeProfile` 实体，`ResourceGroupRepository`、`ComputeProfileRepository`，以及 `ResourceGroupService`。
  - 新增 `/api/v1/orchestration/resource-groups` 查询、资源组 upsert、计算画像 upsert API，并补充 Swagger/OpenAPI 说明。
  - `OperatorService` 的 `resourceHint` 与 operator graph `resourceGroup/computeProfile` 校验改为调用 `ResourceGroupService`，默认注册表为空时保留 Stage82 静态默认兜底；租户同名资源组会覆盖组状态，计算画像可继承全局默认 profile。
  - 前端 `OperatorAPI` 与 `types` 新增 `ResourceGroup/ComputeProfile` 契约，后续画布或资源管理页可直接复用。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `uvx --from dbt-trino dbt parse --profiles-dir . --no-partial-parse` 通过，仅保留 uvx/dbt-trino 可执行提示和 Trino keyring info，无 dbt deprecation/unused config warning。
  - 本地执行 `orchestration/V3__resource_group_registry.sql`，实际插入 4 个资源组和 11 个计算画像。
  - 重启后端为 `onelake-backend-stage91`，screen `40592.onelake-backend-stage91`，PID `40953`，`/actuator/health` 为 `UP`；启动前确认并清理旧 Stage89 孤儿 Java 进程占用的 8080。
  - 真实 API：`GET /api/v1/orchestration/resource-groups` 返回 4 个内置资源组及默认 profiles。
  - 真实 API：临时创建 `warehouse-codex-stage91` 与 `trino-codex-stage91` 后，Manifest 校验返回 `ok=true`，graph 校验返回 `ok=true`。
  - 真实 API：同一临时资源组下使用错误画像 `spark-large` 返回 `ok=false`，错误为 `computeProfile 不存在或不属于当前 resourceGroup: spark-large/warehouse-codex-stage91`。
  - 临时资源组和画像已通过 SQL 清理，DB 中 `warehouse-codex-stage91` 剩余 0 条。

### 阶段 92：流水线与算子市场阶段四 Spark/Python 运行契约就绪边界
- **状态：** complete
- **开始时间：** 2026-06-23 CST
- 执行的操作：
  - 对照方案 §2.5 与 Stage79/Stage82 结论，确认 Spark/Python 当前只完成 Manifest 契约，不能放开图级执行。
  - 直接查询当前 Dagster GraphQL repository：`onelake/onelake-loc` 仅暴露 `onelake_dbt_model_run` 与 `onelake_sync_task_schedule_reconcile`，没有 Spark/Python job。
  - `DagsterClient` 新增 repository jobs 查询能力，用于运行契约就绪检查。
  - 新增 `RuntimeContractService`、`RuntimeContractDTO` 与 `/api/v1/orchestration/runtime-contracts`，返回 `SQL_DBT/SPARK/PYTHON` 的 Manifest 支持、图级执行支持、Dagster job 可用性与阻断原因。
  - `OrchestrationService.triggerReadiness` 在触发前读取 DAG definition/engine/compileTarget 与 dagsterJob，若命中 Spark/Python contract-only 契约则返回不可触发，避免创建无意义运行实例。
  - 前端 `OperatorAPI` 与 `types` 新增 `RuntimeContract` 契约，后续画布或资源管理页可据此禁用未接入运行态入口。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `uvx --from dbt-trino dbt parse --profiles-dir . --no-partial-parse` 通过，仅保留 uvx/dbt-trino 可执行提示和 Trino keyring info。
  - 重启后端为 `onelake-backend-stage92`，screen `50062.onelake-backend-stage92`，PID `50424`，`/actuator/health` 为 `UP`。
  - 真实 API：`GET /api/v1/orchestration/runtime-contracts` 返回 `SQL_DBT READY`，`SPARK/PYTHON MISSING_DAGSTER_JOB`，且 Spark/Python `graphExecutionSupported=false`。
  - 真实 API：创建临时 `codex_stage92_spark_contract` DAG 后触发返回 `code=40012`，message 为 `SPARK 仍处于 Manifest 契约态，尚未接入 Dagster Spark op、依赖隔离和部署契约`。
  - DB 实证：该临时 Spark DAG 触发后 `orchestration.job_run` 为 0 行，说明阻断发生在创建 run 之前；临时 DAG 已清理，剩余 0 条。

### 阶段 93：治理表工厂迭代 1-4 最小建模闭环
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 新增 `/lakehouse/governance-factory` 治理表工厂页面，面向“选择一张 ODS 表，配置多个字段治理规则，生成一张 DWD 表”的工作流。
  - 页面提供源表与目标配置、字段治理矩阵、高级算子配置、SQL/dbt 预览和保存校验/编译入口。
  - 支持字段直通、表达式、字典映射和关联补充；字典映射生成 `CASE` 表达式，关联补充生成 `join.lookup_enrich` operator graph 节点。
  - `DwdModelDraftRequest` 扩展 `pipelineMode/operatorGraph/resourceGroup/computeProfile/engine/costPolicy` 等字段，保留旧构造器兼容既有测试。
  - `DwdModelService` 保存治理图与执行资源信息，编译时从 operatorGraph 读取 lookup join，并将 lookup source 写入 `sources.yml`。
  - 分层表管理 ODS 行新增 `治理成表` 动作，直达治理表工厂并携带 `sourceAssetId`。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - `uvx --from dbt-trino dbt parse --profiles-dir .` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。

### 阶段 94：治理表工厂迭代 5-9 运行发布与契约可见闭环
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 页面新增本地异常预览，覆盖主键缺失、敏感字段直通、字典配置缺失、lookup 配置不完整等常见治理问题。
  - 页面接入 `ModelingAPI.runModel`，展示最近运行状态；接入 `OperatorAPI.listRuntimeContracts`，展示 SQL_DBT/Spark/Python 运行契约。
  - 后端新增 `POST /api/v1/modeling/models/{id}/publish`，要求模型已校验或已发布、存在 dbt artifact 和 orchestration DAG，并发布 `modeling.model.published` outbox 事件。
  - 页面接入发布按钮和 `DRAFT/VALIDATED/PUBLISHED` 状态展示。
  - 修复模型状态流转：`VALIDATED` 允许再次编辑并回退 `DRAFT`，`PUBLISHED` 仍提示新建版本。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过，覆盖 lookup join 编译、发布事件和已校验模型回草稿编辑。
  - `mvn -q install -DskipTests -Djacoco.skip=true` 通过。
  - `uvx --from dbt-trino dbt parse --profiles-dir .` 通过，仅有 uvx/dbt-trino 可执行提示和 Trino keyring info。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - 重启后端为 `onelake-backend-governance`，screen `16458.onelake-backend-governance`，PID `16848`，`/actuator/health` 为 `UP`。
  - OpenAPI 实证：`/api/v1/modeling/models/{id}/publish` 存在，operationId 为 `publishModel`。
  - 浏览器实证：登录后访问 `/lakehouse/governance-factory`，核心面板、字段矩阵、高级算子入口、保存校验、编译 dbt、运行、发布、`SQL_DBT READY` 均可见；控制台无 error，仅有既有 React Router future warnings。

### 阶段 95：治理能力并入流水线最佳实践收敛
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 将 `GovernanceFactory` 扩展为可嵌入组件：`embedded/initialSourceAssetId/onModelChange` 支持从流水线节点属性抽屉复用字段治理矩阵。
  - `DagCanvas` 新增 `ods-dwd` 模板识别：访问 `/orchestration/pipelines/new?template=ods-dwd&sourceAssetId=...` 时自动生成 ODS 输入、字段治理矩阵、DWD 治理表三节点。
  - 字段治理节点右侧属性面板新增“配置字段治理”入口，宽抽屉内承载源表、目标表、字段矩阵、高级算子、校验、编译、运行和发布。
  - 字段治理模型保存/编译/发布后，将 `modelId/modelStatus/sourceFqn/targetFqn` 回写到流水线节点配置，保持 DAG 与 DWD 模型引用关系。
  - 分层表管理“治理成表”入口改为进入流水线 DWD 治理模板。
  - 左侧菜单移除独立“治理表工厂”，旧 `/lakehouse/governance-factory` 路由兼容跳转到流水线模板。
  - 修复字段治理矩阵内高级算子配置抽屉遮罩阻断问题；行内源字段、输出字段、算子与主键控件获得焦点/变更时同步切换当前配置字段。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。
  - 浏览器实证：流水线模板打开后显示 `dwd_field_governance_pipeline`，画布包含 `ODS 源表`、`字段治理矩阵`、`DWD 治理表`，字段治理矩阵在流水线内打开，保存校验/编译 dbt/运行/发布动作可见。
  - 浏览器实证：菜单中不存在独立“治理表工厂”；旧 `/lakehouse/governance-factory?sourceAssetId=...` 路由跳转为 `/orchestration/pipelines/new?sourceAssetId=...&template=ods-dwd`。
  - 用户反馈 Web 上找不到入口后，新增流水线列表顶部 `新建 DWD 治理流水线` 显式入口；浏览器实证 `/orchestration/pipelines` 顶部可见该按钮，仍保留普通 `新建流水线`。
  - 浏览器实证：字段治理矩阵内打开 `id 高级算子配置` 后，未新增高级抽屉遮罩；直接点击 `customer_no` 行“算子”控件，右侧抽屉切换为 `customer_no 高级算子配置`。

### 阶段 96：流水线与治理设计器产品边界重构
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 将 `ods-dwd` 流水线模板从 `ODS 源表 -> 字段治理矩阵 -> DWD 治理表` 调整为 `ODS 源表 -> DWD 治理模型 -> 治理质量门禁 -> DWD 治理表`，让顶层 DAG 表达资产流转、模型运行、质量门禁和发布边界。
  - 顶层画布左侧面板改为“编排算子”，仅展示输入、转换、治理、关联、聚合、质量门禁、输出等顶层/表级能力；字段级标准化、脱敏、加密算子通过提示引导到治理设计器内部维护。
  - DWD 治理模型属性面板从“算子引用/字段治理矩阵”改为“模型契约/DWD 治理模型”，入口改为“打开治理设计器”。
  - `GovernanceFactory` 可见文案从治理表工厂/字段治理矩阵收敛为 `DWD 治理设计器`、`字段映射与处理 Recipe`、`字段处理配置`。
  - 算子市场新增前端推导的适用范围分层与筛选：编排步骤、模型 Recipe、字段处理器、质量断言、复合模板；算子卡片和详情弹窗展示适用范围说明。
  - DWD 治理模型保存/编译/发布后，真实 `sourceFqn/targetFqn` 会同步回写到顶层 ODS 输入节点和 DWD 输出节点。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 通过。
  - 浏览器实证：`/orchestration/pipelines/new?template=ods-dwd` 显示 `dwd_table_governance_pipeline`，画布包含 `ODS 源表`、`DWD 治理模型`、`治理质量门禁`、`DWD 治理表`，宽抽屉标题为 `DWD 治理设计器`，页面不再出现顶层 `字段治理矩阵` 标题。
  - 浏览器实证：点击流水线 `校 验` 后返回 `4 节点通过`，无缺必填参数错误，无 resourceGroup warning，仅剩未保存治理模型前的 sourceColumns/outputColumns schema 闭合提示。
  - 浏览器实证：`/orchestration/operators` 展示适用范围筛选与卡片标签；选择 `字段处理器` 后列表收敛到 19 个字段级处理器。

### 阶段 97：DWD 治理流水线工作台主路径重构
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 新增 `onelake-app/web-console/src/pages/orchestration/dwd-workbench/DwdPipelineWorkbench.tsx`，将 DWD 治理流水线主路径重构为阶段化工作台。
  - 新增 `DwdPipelineCreate.tsx`，作为 DWD 模板创建入口的轻量包装。
  - `routes.tsx` 新增 `PipelineCreateRoute`：`template=ods-dwd` 进入 DWD 工作台，普通新建流水线继续进入旧 DAG 画布；新增详情工作台 `/orchestration/pipelines/:id/workbench` 与技术 DAG `/orchestration/pipelines/:id/graph`。
  - 工作台提供 `源表与目标 -> 治理模型 -> 质量门禁 -> 运行发布 -> 监控血缘` 五阶段导航，并在页面中展示 `ODS 源表 -> DWD 治理模型 -> 质量门禁 -> DWD 治理表` 资产流。
  - 工作台在治理模型阶段嵌入现有 `GovernanceFactory`，复用字段映射与处理 Recipe、高级算子、保存校验、编译 dbt、运行和发布能力。
  - `PipelineList` 根据 DAG definition/operatorGraph 识别 DWD 治理流水线，DWD 行点击进入工作台，普通 DAG 仍进入画布；操作文案随类型显示“打开工作台/打开画布”。
  - 工作台详情态接入 `OrchestrationAPI.getDag` 和 `ModelingAPI.getModel`，兼容从 DAG definition 顶层 `modelId/sourceFqn/targetFqn` 与治理节点 config 恢复上下文。
  - `GovernanceFactory` 新增 `initialModel` 初始化能力，可按已有模型还原目标表、物化方式、分区表达式、字段映射、字段处理和模型状态，用于工作台详情态编辑。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 通过。
  - 浏览器实证：`/orchestration/pipelines/new?template=ods-dwd&sourceAssetId=test-source` 展示 `DWD 治理流水线`、`治理闭环`、`治理资产流`、`字段映射与处理 Recipe`、`质量门禁`、`监控血缘`，且不出现旧 `流水线 order_pipeline` 画布标题；控制台无 error。
  - 浏览器实证：`/orchestration/pipelines/new` 仍展示旧画布，页面包含 `流水线 / order_pipeline / 校 验 / 试运行 / 发布 / 编排算子` 等画布内容，未进入 DWD 工作台。
  - 详情态真实 API 直连未做终端实证：`curl http://localhost:8080/api/v1/orchestration/dags` 返回 401 Bearer，说明需要登录态 token；本轮不伪造 API 成功，只完成前端真实接口接入、类型/构建和创建路径浏览器实证。

### 阶段 98：DWD 工作台新建到详情态闭环
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - `DwdPipelineWorkbench` 新增统一 `handleModelChange`：治理模型保存、编译或发布回调后，将 `modelId/modelStatus/sourceFqn/targetFqn/dbtModelName/engine/resourceGroup/computeProfile/compiledSql` 写回工作台上下文。
  - 工作台新增 `pipelineDagId` 上下文，修正之前用 `modelId` 代表 DAG 的混淆；产物面板、技术 DAG 按钮、运行发布阶段都使用真实 DAG id。
  - 新建态在模型编译生成 `orchestrationDagId` 后自动 `replace` 到 `/orchestration/pipelines/{dagId}/workbench`，保证保存后的工作台可回访。
  - 运行发布阶段将原占位按钮调整为真实入口：技术 DAG、运行实例、观测血缘。
  - `RunInstances` 支持 `?dagId=` 查询参数，进入后客户端聚焦当前流水线运行记录，并提供“查看全部”返回全量列表。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 通过。
  - 浏览器实证：使用本地开发账号 `dev` 登录后，`/orchestration/pipelines/new?template=ods-dwd&sourceAssetId=test-source` 展示 `DWD 治理流水线`、`治理资产流`、`字段映射与处理 Recipe`、`质量门禁`、`监控血缘`。
  - 浏览器实证：`/orchestration/runs?dagId=codex-test-dag` 展示 `运行实例`、`当前流水线` 和 `查看全部`，控制台无 error。

### 阶段 99：DWD 工作台质量门禁可编辑
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 对照 `DwdModelService`，确认后端已支持 `QUALITY_GATE` / `gate.*` 节点，并将 `not_null`、`unique`、`accepted_values`、`range`、`custom_sql` 编译为 dbt tests。
  - `DwdPipelineWorkbench` 新增质量门禁草稿模型，支持从 `DataModel.operatorGraph` 还原主键完整性、枚举值、范围、自定义 SQL 门禁。
  - 质量门禁阶段新增可编辑控件：启停开关、字段多选、允许值、范围上下限、自定义 SQL、失败策略。
  - 保存门禁时用现有模型字段映射构造 `DwdModelDraftRequest`，更新 `operatorGraph` 后调用模型校验并刷新模型详情。
  - 工作台保留未保存模型的空态：新建页未保存模型时显示门禁阶段但禁用保存，避免制造“门禁已落库”的错觉。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 通过。
  - 浏览器实证：登录态访问 `/orchestration/pipelines/new?template=ods-dwd&sourceAssetId=test-source`，切换到 `质量门禁` 阶段后显示 `保存门禁`，未保存模型时保持空态；控制台无 error。

### 阶段 100：DWD 工作台监控血缘闭环
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 对照现有 `/catalog/lineage`，确认目录血缘图已有字段级列、方向、深度、影响分析和 `fqn` URL 参数，DWD 工作台无需重复实现大图。
  - `DwdPipelineWorkbench` 的监控血缘阶段新增 `FieldLineagePanel`，从 `DataModel.columnMappings` 生成字段级 lineage 摘要。
  - 监控血缘阶段新增 `目录血缘`、`资产详情`、`运行实例` 三个入口，分别跳转到目录血缘、Catalog 资产详情和当前 DAG 运行实例。
  - 资产详情入口先按目标表 FQN 调用 `CatalogAPI.listAssets({ keyword })` 匹配资产 id；未投影到 Catalog 时降级打开目录血缘。
  - 未保存模型时继续显示空态，不伪装已有血缘。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 通过。
  - 浏览器实证：登录态访问 `/orchestration/pipelines/new?template=ods-dwd&sourceAssetId=test-source`，切换到 `监控血缘` 阶段后显示空态 `发布后生成资产、血缘与运行观测`，工作台与资产流仍可见；控制台无 error。

### 阶段 101：DWD 工作台资源与算力配置闭环
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - `DwdPipelineWorkbench` 在源表与目标阶段接入 `OperatorAPI.listResourceGroups()`，展示可用资源组、组内计算画像和执行引擎。
  - 工作台新增 `resourceConfig` 状态，支持从已加载模型或 DAG definition 恢复 `resourceGroup/computeProfile/engine`。
  - 新增“保存算力配置”动作：用完整 `DwdModelDraftRequest` 更新模型，避免只保存局部配置导致字段映射或 operatorGraph 丢失。
  - `GovernanceFactory` 新增 `initialResourceGroup/initialComputeProfile/initialEngine`，新建或编辑治理模型时使用工作台当前算力配置，不再退回硬编码默认值。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 通过。
  - 浏览器实证：登录态访问 `/orchestration/pipelines/new?template=ods-dwd&sourceAssetId=test-source`，源表与目标阶段可见 `资源组`、`计算画像`、`执行引擎` 和 `保存算力配置`，控制台无 error。

### 阶段 102：DWD 工作台字典治理预设与版本化配置
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - `GovernanceFactory` 新增 4 个产品化字典预设：性别标准、是否标识、订单状态、会员等级，均带 `code/name/version/domain/pairs/noMatchPolicy`。
  - 字典匹配抽屉新增“字典集”选择器，选择预设后自动填入映射内容，展示字典名、版本和映射数量，同时仍允许用户编辑映射文本。
  - `buildOperatorGraph` 将 `dictionaryRef/dictionaryName/dictionaryVersion/dictionarySource/pairs/noMatchPolicy` 写入 `standard.codebook_mapping` 节点，并在 `fieldRules` 摘要中保留字典元信息。
  - `fieldRulesFromModel` 改为解析完整 `operatorGraph`，从字典节点恢复字典引用、版本、映射内容和未命中策略，支持已保存模型再次打开后回显。
  - 保持当前 `CASE WHEN` 字段表达式编译路径，明确本轮不伪装后端字典主数据、审批或发布服务已完成。
- 验证：
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - 浏览器实证：登录态访问真实 ODS 源表 `ods.ods_customers_100k` 的 DWD 工作台，切换 `gender` 字段为 `字典匹配` 后，字段处理抽屉可见 `字典集`、版本标签、映射数量和 CASE SQL 预览。
  - 浏览器实证：选择字典预设后，映射文本域写入 `Y=是/N=否/1=是/0=否/true=是/false=否`，SQL 预览生成 `case when src.gender ... else src.gender end`；控制台无 error，仅保留既有 React Router future warnings。

### 阶段 103：标准字典主数据事实源闭环
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 新增 `modeling/V7__codebook_registry.sql`，提供 `modeling.codebook` 与 `modeling.codebook_version`，保存字典集草稿、当前 entries、未命中策略、最新发布版本和版本快照。
  - 新增 `Codebook/CodebookVersion` 实体、仓储、DTO、`CodebookService` 与 `CodebookController`，接口覆盖列表、详情、创建、更新、发布、废弃和版本历史。
  - `DomainEvents` 新增 `modeling.codebook.created/updated/published/deprecated`，让字典主数据变化进入现有 outbox 事件边界。
  - 前端 `types` 与 `ModelingAPI` 新增 `Codebook` 契约；`GovernanceFactory` 将已发布后端字典转换为字典预设，并与内置字典合并展示。
  - 为避免旧后端导致页面加载时 404，本轮进程级重启后端为 `onelake-backend-stage103`，并手工执行幂等 V7 迁移。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过，新增 `CodebookServiceTest` 覆盖创建、发布版本快照和重复字典项拦截。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - 数据库实证：执行 V7 迁移后 `modeling.codebook` 可查询；临时 codebook API 创建/发布返回 `code=0`、`status=PUBLISHED`、`latestVersion=2026.06-codex`。
  - 浏览器实证：工作台自动请求 `/api/v1/modeling/codebooks?status=PUBLISHED` 返回 200；字典集下拉同时显示临时后端已发布字典和内置预设，且控制台无 error，仅保留既有 React Router future warnings。
  - 临时验证数据已清理：`modeling.codebook where code like 'codex.stage103.%'` 剩余 0 条。
  - `git diff --check` 通过。

### 阶段 104：DWD 运行资源上下文透传到 Dagster tags
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 核对 `DwdModelService.run`、`DwdModelDagsterClient.launchDwdModelRun` 和 `dagster/definitions.py`，确认资源组和计算画像已经写入模型运行记录，并传入 Dagster op config。
  - 将 `onelake.resource_group` 与 `onelake.compute_profile` 补入 Dagster execution tags，便于 Dagster run、OneLake run 与后续资源观测按算力画像关联。
  - 更新 `DwdModelServiceTest.runValidatedModelLaunchesDagsterAndStoresRun`，捕获 tags 并断言默认 `default/trino-small` 被传入 Dagster launch metadata。
  - 明确本轮只做资源上下文透传，不声明已完成调度队列、并发槽位或 quota 分配。
- 验证：
  - `mvn -q -pl module-modeling -am test -Djacoco.skip=true` 通过。

### 阶段 105：DWD 编排触发资源上下文一致性闭环
- **状态：** complete
- **开始时间：** 2026-06-24 CST
- 执行的操作：
  - 核对 `OrchestrationService.createDwdModelRun`，确认编排触发 DWD DAG 时已经把 `resource_group/compute_profile` 写入 `model_run` 与 Dagster `run_config`。
  - 将 `onelake.resource_group` 与 `onelake.compute_profile` 补入编排触发的 Dagster execution tags，避免同一 DWD 模型通过“工作台运行”和“流水线触发”时观测标签不一致。
  - 更新 `OrchestrationServiceTest.triggerDwdModelDagCreatesModelRunAndLaunchesDagsterWithRunConfig`，同时捕获 op config 和 tags，断言 `default/trino-small` 两端一致。
  - 明确本轮仍只做资源上下文一致性，不声明已完成 Dagster/Trino 队列分配、并发槽位或 quota 调度。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过。

## 五问重启检查
| 问题 | 答案 |
|------|------|
| 我在哪里？ | 流水线与算子市场阶段 79、80、81、82、83、84、85、86、87、88、89、90、91、92 已完成；治理表工厂迭代 1-9 对应阶段 93、94 已完成；治理能力并入流水线阶段 95 已完成；流水线与治理设计器产品边界重构阶段 96 已完成；DWD 治理流水线工作台主路径阶段 97 已完成；DWD 工作台新建到详情态闭环阶段 98 已完成；DWD 工作台质量门禁可编辑阶段 99 已完成；DWD 工作台监控血缘闭环阶段 100 已完成；DWD 工作台资源与算力配置阶段 101 已完成；DWD 工作台字典治理预设与版本化配置阶段 102 已完成；标准字典主数据事实源阶段 103 已完成；DWD 运行资源上下文透传阶段 104 已完成；DWD 编排触发资源上下文一致性阶段 105 已完成；业务术语阶段 76、77 的进度记录已存在但不属于本轮流水线方向 |
| 我要去哪里？ | 当前流水线方向阶段四后端校验、DWD 编译产物对齐、可执行 dbt generic tests、source manifest 一致性、freshness source 产物、custom_sql 只读断言、dbt 校验噪声清理、资源组/计算画像注册表、Spark/Python 运行契约就绪边界、治理表工厂字段级治理闭环、流水线融合、产品边界重构、DWD 工作台主路径、新建到详情态闭环、质量门禁可编辑、监控血缘闭环、资源算力配置闭环、字典治理预设闭环、标准字典主数据事实源、直接运行资源 tags 和编排触发资源 tags 已完成；后续若继续扩展，应进入字典运行期 join/缓存策略、资源调度分配或真实 Spark/Python Dagster op，而不是伪装已有运行态 |
| 目标是什么？ | 继续把流水线与算子市场从可浏览/可注册/可添加推进到完整算子工程化 |
| 我学到了什么？ | 见 `findings.md` |
| 我做了什么？ | 见上方记录 |

### 阶段 108：数据流 DAG 契约化与多输入/多输出计算闭环
- **状态：** in_progress
- **开始时间：** 2026-06-25 CST
- 执行的操作：
  - 结合用户提出的 fan-in/fan-out、上游输出作为下游输入、双数据源 Join 生成 DWD 等场景，重新审视当前流水线语义。
  - 调研 Dagster、Airflow、dbt、Databricks Jobs、Azure Mapping Data Flow、AWS Glue Studio 的官方文档，提炼“任务依赖、资产依赖、结构化转换节点、数据就绪触发”四类设计模式。
  - 在 `task_plan.md` 追加阶段 108，明确边契约化、节点端口、自动输入推导、多源就绪屏障、结构化 Join/Union/Lookup 和运行实例观测的实施范围。
  - 在 `findings.md` 记录调研结论和 OneLake 当前架构缺口。
  - `pipeline_task_edge` 新增 `sourceOutput/targetInput/assetFqn/inputAlias/joinRole/triggerPolicy/freshnessPolicy`，并补充 V6 幂等迁移。
  - 后端 `PipelineCompileService` 在编译期按入边为 Spark 节点推导 `from_tables` 与 `dataflow_inputs`；`dataflow.nodeKind=JOIN` 且左右输入齐全时自动生成 Spark SQL。
  - 前端统一编辑器新增“关联 Join”节点入口、“添加连线”弹窗、节点输入/输出计数、依赖边端口/alias 图例和 Spark Join 结构化配置面板。
  - 修复本地浏览器登录阻塞：OIDC 默认 authority 改走同源 `/auth/realms/onelake`，Vite `/auth` 代理去掉前缀后转发到 Keycloak。
  - 构造 `spark_join_dataflow_join_e2e_201235` 测试流水线：`ods_user` 与 `ods_user_profile` 两个 ODS 输入，经 `left/right` 数据流边进入 `spark_user_join`，产出 `iceberg.dwd.user_enriched_join_e2e`。
  - 运行实例页改为服务端按 DAG 查询运行历史，避免 `?dagId=` 时先取全局第一页再本地过滤导致“当前流水线 0 条”的假空。
  - 运行实例展开区改为以流水线任务定义绘制完整拓扑，`task_run` 只作为状态覆盖；即使部分节点没有 task_run，也能展示未开始/已阻断状态、输入输出数量、端口 alias、行数和产物表。
  - 新增 `PipelineNodePortRegistry` 作为流水线主链路节点端口契约，编译校验覆盖 source output、target input、Join left/right 必填且单入、asset FQN 解析、端口基数和 fan-out 合法性。
  - `PipelineSyncRefTriggerHandler` 接入数据流边，多个 `SYNC_REF` 输入指向同一目标节点时先记录 readiness；默认等待全部输入到齐后才触发流水线。
  - `TaskRunStatus` 新增 `UPSTREAM_FAILED`、`SKIPPED`，终态失败刷新时 `OrchestrationService` 沿 PIPELINE 边把下游 `QUEUED` 节点标记为 `UPSTREAM_FAILED`。
  - 前端运行实例状态文案、状态色和统一编辑器 freshness 策略选项同步支持 `UPSTREAM_FAILED/SKIPPED` 与 `SAME_FRESHNESS_WINDOW`。
  - `OrchestrationService.triggerPipelineRun` 创建 `task_run` 时按 PIPELINE DAG 初始化状态：`SYNC_REF` 观测节点写入 `SUCCEEDED` 和产物表，所有直接上游已满足的节点写入 `RUNNING`，汇聚节点在上游未完成前保持 `QUEUED`。
  - 统一编辑器右侧面板新增通用“数据流关系”，对任意节点展示输入来自哪个上游任务、输出到哪个下游任务，以及端口、别名、asset FQN、触发策略和新鲜度策略。
  - 发现并处理本地运行态未加载新代码的问题：旧 `spring-boot:run` JVM 于 21:03 启动，module-orchestration SNAPSHOT jar 于 21:14 更新，DevTools 不会替换依赖 jar；已强制结束 PID 81922/父 Maven 81492，并重启为 screen `onelake-backend` PID 27445。
  - `PipelineNodePortRegistry` 与 `PipelineCompileService` 新增结构化 `DERIVE_COLUMN`/`SINK` 支持：单输入端口 `in`、输出 `out`，按入边生成派生字段 Spark SQL 与 DWD 写入 SQL。
  - 统一编辑器左侧新增“派生字段”“落 DWD 表”节点入口；Spark 节点右侧面板新增“派生字段”和“DWD 出口”配置区，可配置源别名、保留源字段、字段表达式、写入方式和输出字段。
  - `SqlAssetSecurityService` 修复 SQL 工作台资产校验 FQN 归一化：`iceberg/onelake/hive.schema.table` 会匹配 Catalog 中的 `schema.table`，避免流水线自动登记的 Spark 产物被误判未登记。
  - 构造 P6 验收数据：MySQL `user/user_profile` 各 100 行，Iceberg ODS `ods.mysql_user/ods.mysql_user_profile` 各 100 行；流水线 `p6_mysql_user_join_dwd_20260625140602` 以两个 `SYNC_REF` fan-in 进入 Spark Join，再接 `DERIVE_COLUMN`、`SINK(DWD)`、`QUALITY_GATE`。
- 验证：
  - `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 通过，覆盖 110 个编排模块测试。
  - `mvn -q -pl module-orchestration -Dtest=PipelineSparkCompileTest,PipelineCompileServiceTest,PipelineSyncRefTriggerHandlerTest,OrchestrationPipelineTriggerTest,OrchestrationServiceTest,PipelineEndToEndTest test -Djacoco.skip=true` 通过，覆盖边端口契约、Spark Join 输入推导、多源 readiness 屏障和上游失败传播。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - `pnpm --dir onelake-app/web-console build` 通过，仅保留 Vite chunk-size warning。
  - `git diff --check` 通过。
  - 数据库实证：因本地 V4 migration checksum 与已应用记录不一致，未执行 Flyway repair；仅手工执行 V6 幂等 ALTER，`pipeline_task_edge` 已具备 7 个新增数据流字段。
  - API 实证：测试流水线 validate 返回 `valid=true`；Spark 节点配置被写入 `from_tables=["iceberg.ods.user","iceberg.ods.user_profile"]`、`dataflow_inputs(left/right)` 与自动生成的 `CREATE OR REPLACE TABLE ... LEFT JOIN ...` SQL。
  - 浏览器实证：登录后打开 `/orchestration/pipelines/0699f001-567a-4a8d-84e9-99a41c1ba117`，页面展示 3 个节点、2 条依赖边、层级 1→2；右侧 Spark 面板展示左右输入、Join 条件、输出字段和自动 SQL；点击“校验”显示“校验通过”，控制台无 error。
  - 浏览器实证：构造只含 `spark_user_join` 一个 task_run 的运行实例 `codex-partial-topology-run`，打开 `/orchestration/runs?dagId=0699f001-567a-4a8d-84e9-99a41c1ba117` 并展开后，仍展示 3 个节点、2 条依赖边、`left/right` 边标签、两个 ODS 节点“未开始”和 Spark 节点“成功”；控制台无 error/warn。
  - API 实证：临时流水线 `codex_edge_contract_bad_210134` 故意把 Join 右输入接到 `inputs`，新后端返回 `valid=false`，错误包含 `target port 'inputs' is not declared` 和缺失 `right`；`codex_edge_contract_good_210134` 使用 `left/right` 后返回 `valid=true`，Spark config 自动生成 `from_tables`、`dataflow_inputs` 和 `CREATE OR REPLACE TABLE ... LEFT JOIN ...`。
  - 浏览器实证：打开 `/orchestration/pipelines/a378c80c-13ac-41fa-8cd7-f1fe018e183c`，页面展示层级 1 两个 SYNC_REF、层级 2 `spark_join`、2 条依赖边 `left as u/right as p`；点击 `spark_join` 后右侧展示输入表、Join 类型、条件和自动 SQL；点击“校验”后返回 200 并显示“校验通过”。新标签重开同一路由控制台为 0 error，仅保留 React Router 开发态 warning。
  - API 实证：重启后端后新建 `codex_p4_topology_691089`，触发前 validate 返回 `valid=true`；触发后即时查询 `task_run`，`sync_user=SUCCEEDED(table:ods.user)`、`spark_a=RUNNING`、`spark_b=RUNNING`、`quality_gate=QUEUED`，证明初始运行态不再全量扁平化为 QUEUED。
  - 浏览器实证：打开 `/orchestration/pipelines/7ef45218-af0c-4ecb-b295-2dac11791f11`，画布展示 3 个层级、4 个节点、4 条依赖边和节点输入/输出计数；点击“用户清洗 A”后右侧可见“数据流关系 / 输入来自 / 输出给”，含 `sync_user -> spark_a`、`spark_a -> quality_gate`、端口、别名、表 FQN 与触发策略；控制台 0 error。
  - 浏览器实证：打开 `/orchestration/runs` 展开最新 `codex_p4_topology_691089` 运行实例，可见完整节点拓扑、4 条带端口/表名的连线、任务明细表；任务级展示层级、类型、状态、输入/输出、目标表和产物，控制台 0 error，交互无明显卡顿。
  - `mvn -q -pl module-catalog -Dtest=SqlAssetSecurityServiceTest,SqlWorkbenchServiceTest test -Djacoco.skip=true` 通过，覆盖 SQL 资产 FQN 归一化与工作台执行边界。
  - API 实证：`p6_mysql_user_join_dwd_20260625140602` validate 返回 `valid=true`；触发运行 `993824e4-9bf3-4de6-bfa8-737a569dcb48` 后，`sync_user/sync_profile/join_user_profile/govern_user_fields/sink_dwd_user/quality_gate` 六个 task_run 全部 `SUCCEEDED`。
  - 数据面实证：`iceberg.dwd.user_governed` 行数 100、UUID 100 个、手机号脱敏 100 行、身份证脱敏 100 行、描述去空格 100 行；样例行形如 `138****0001`、`110101********0001`。
  - Catalog 实证：pipeline.task.loaded 事件自动登记 `tmp.user_joined`、`tmp.user_governed`、`dwd.user_governed`，其中 DWD 资产 `row_count=100`。
  - 浏览器实证：打开 `/orchestration/pipelines/10cb6a51-2245-438f-ac84-3d0844ddd490`，画布展示 5 个层级、6 个任务、5 条依赖边；左侧可见“关联 Join/派生字段/落 DWD 表”，派生节点右侧展示上游 `join_user_profile`、`onelake.tmp.user_joined`、`用户 UUID` 和脱敏表达式，Sink 节点展示 `onelake.dwd.user_governed` 与覆盖写入。
  - 浏览器实证：打开 `/orchestration/runs` 并展开最新成功运行，页面展示完整多节点拓扑、`left as u/right as p/in as src/in as s/in as q` 连线标签、六个节点成功状态和 Spark 节点 100 行产出。
  - 浏览器实证：SQL 工作台运行 DWD 校验查询，结果区显示 `total_rows/uuid_count/masked_mobile_rows/masked_id_rows/trimmed_desc_rows = 100/100/100/100/100`，不再出现 `Catalog 资产未登记`。

### 阶段 109：流水线主链路 Spark-only 收敛
- **状态：** complete
- **开始时间：** 2026-06-25 CST
- 执行的操作：
  - 将流水线 `Dag` 默认引擎、资源组和计算画像收敛为 `SPARK / spark-default / spark-small`。
  - 将 `pipeline_task` 默认任务引擎收敛为 `SPARK_SQL`；`SQL_MODEL`、`FIELD_GOVERNANCE` 和 `TRINO_DBT` 仅作为历史兼容类型保留。
  - 更新 `PipelineService`：新建任务只允许 `SYNC_REF`、`SPARK_SQL`、`PYSPARK`、`QUALITY_GATE`，ODS->DWD 模板改为 `SYNC_REF -> Spark 字段治理 -> Spark DWD Sink -> Quality Gate`。
  - 更新 `PipelineBackfillService`：由模型回填流水线时生成 Spark Sink，而不是 dbt/SQL_MODEL 节点。
  - 更新 `PipelineCompileService` 与 `OrchestrationService`：流水线编译和触发只生成 Spark 运行配置，不再为主链路构建 dbt selector/run config。
  - 更新 Dagster `onelake_pipeline_run`：主链路直接运行 `run_spark_task_op`；dbt op 保留为历史能力，不再接入统一流水线 job。
  - 更新前端统一编辑器：移除 `SQL 模型` 新建入口和 `Trino + dbt` 引擎选择；字段治理和质量门禁文案改为 Spark 表产物语义。
- 验证：
  - `mvn -q -pl module-orchestration -Dtest=PipelineCompileServiceTest,PipelineSparkCompileTest,PipelineEndToEndTest,OrchestrationPipelineTriggerTest,RuntimeContractServiceTest test -Djacoco.skip=true` 通过。
  - `mvn -q -pl module-orchestration test -Djacoco.skip=true` 通过。
  - `mvn -q -pl bootstrap -am install -DskipTests -Djacoco.skip=true` 通过。
  - `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false` 通过。
  - API 实证：`/api/v1/orchestration/runtime-contracts` 仅返回 `compileTarget=SPARK, engine=SPARK, dagsterJob=onelake_pipeline_run`。
  - API 实证：新建流水线默认返回 `engine=SPARK, resourceGroup=spark-default, computeProfile=spark-small`。
  - API 实证：尝试创建 `SQL_MODEL/TRINO_DBT` 流水线任务返回 400，文案为“流水线已收敛为 Spark 引擎，请使用 Spark SQL/PySpark 或结构化 Spark 节点”。
  - API 实证：创建 `SPARK_SQL` 节点成功，返回 `engine=SPARK_SQL`。

### 阶段 110：硬删历史枚举和旧 DWD/dbt 模型运行能力
- **状态：** complete
- **开始时间：** 2026-06-25 CST
- 执行的操作：
  - 删除 `TaskType.SQL_MODEL`、`TaskType.FIELD_GOVERNANCE`、`EngineType.TRINO_DBT`、`CompileTarget.SQL_DBT/PYTHON` 等旧主链路枚举。
  - 删除旧 DWD/dbt 运行入口：Dagster `onelake_dbt_model_run` / `run_dwd_model`、`TrinoDbtRunConfigBuilder`、建模 `DwdModelDagsterClient`、`DwdRunArtifactReader`、`DwdModelRunRequest`、`DwdModelRunSynchronizer` 和 `POST /api/v1/modeling/models/{id}/run`。
  - `OrchestrationService.triggerDag` 不再为 DWD 模型构造 dbt runConfig 或跨 schema 写 `modeling.model_run`；非注册 Dagster job 由运行契约阻断。
  - 算子市场 Manifest、资源组注册表和运行契约全部改为 `SPARK`；默认内置资源只保留 `spark-default/spark-small|medium|large`。
  - 前端画布、算子市场、运行实例、治理表工厂和表详情页清掉旧 SQL/dbt 运行入口与文案，统一展示 Spark 治理/编排能力。
  - 新增迁移 `orchestration/V7__spark_only_runtime_resources.sql` 与 `modeling/V8__spark_only_dwd_defaults.sql`，用于清理已落库历史默认资源和值。
  - 删除未接入 Spark 主链路的旧 `QualityGateCompiler` 及测试。
- 验证：
  - `mvn -q -pl module-orchestration,module-modeling,module-common -am clean test-compile` 通过。
  - `mvn -q -pl module-orchestration,module-modeling,module-common -am -Dtest=PipelineCompileServiceTest,PipelineSparkCompileTest,OrchestrationPipelineTriggerTest,PipelineStatusMachineTest,PipelineEndToEndTest,OrchestrationServiceTest,RuntimeContractServiceTest,OperatorServiceTest,ResourceGroupServiceTest,DwdModelServiceTest,RunningTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过。
  - `pnpm build` 于 `onelake-app/web-console` 通过，仅保留既有 Vite chunk-size warning。
  - 搜索生产路径确认旧枚举/旧 job 只剩迁移清理条件：`modeling/V8__spark_only_dwd_defaults.sql` 中用于把 `FIELD_GOVERNANCE/TRINO_DBT` 数据改写为 Spark。

---
*每个阶段完成后或遇到错误时更新此文件*

### 阶段 114：湖仓与建模 V2 路线图 Review 修复
- **状态：** complete
- **开始时间：** 2026-07-14 CST
- 执行的操作：
  - 读取 `RTK.md` 和现有文件规划上下文，以当前源码校准 Review 结论。
  - 修正 V2 总计划中已删除的 DWD 独立运行入口、过期的算子编译描述和 Spark contract-only 判定。
  - 修正 M1 TTL 方案：默认逻辑归档，物理冷存储要求复制/重写、校验和 Catalog 原子切换。
  - 修正 M2 通用写 SQL、CTAS 资产注册和 BREAKING drift 紧急冻结的安全/模块边界。
  - 删除 `onelake-app/dagster/__pycache__/`，并在 `.gitignore` 忽略 `__pycache__/` 与 `*.py[cod]`。
- 验证：
  - `git diff --check` 通过。
  - 三份 V2 新文档的 no-index whitespace check 通过，无尾随空白或冲突标记。
  - Markdown 代码围栏平衡检查通过：M1=40、M2=54、总计划=6。
  - 旧错误表述回归检索为 0，`onelake-app/dagster` 下无 `__pycache__`/`*.pyc` 遗留。
  - 本轮只修改文档与 `.gitignore`，未运行 Maven 测试。

### 阶段 115：湖仓与建模 V2 路线图二轮 Review 修复
- **状态：** complete
- **开始时间：** 2026-07-14 CST
- 执行的操作：
  - 核对当前 Trino JDBC 477、Spark 3.5.1 + Iceberg 1.5.2、`CompileTarget.SPARK`、`SecurityConfig.anyRequest().authenticated()` 和现有 Spark-only 流水线边界。
  - 对照 Trino/Iceberg 官方能力确认 8 项 Review 问题为可执行性、安全或数据一致性缺陷。
  - 为每租户唯一默认 Catalog 补充部分唯一索引，并将默认切换收敛为事务操作。
  - 将 M1 维护收敛为 Trino 真实支持的 `retain_last` 能力探测、`sorted_by` 和 BIN_PACK/SORT；Z-Order 延后到 Spark executor，快照保护延后到 Iceberg Tag。
  - 修正 `semantic.compile` 为 `compileTarget=SPARK + template.kind=SPARK_SQL`，与现有 G1 校验和 Spark-only 运行契约一致。
  - Branch/Tag 改为 Iceberg Java Catalog/ManageSnapshots 事实源；pipeline 分支写选定 Spark branch identifier，禁用不存在或只读的 SQL 语法。
  - 写 API 保留认证托底并增加 DE 方法角色；DEV 模式、白名单和资源限制改为仅服务端可控。
  - 写审计改为写前快照 + 提交时间 + 父链唯一关联，歧义时 `UNKNOWN` 且不允许回滚；禁止 `max(snapshot_id)`。
  - SCD2 改为带幂等回执的单个 Spark MERGE/Iceberg 快照提交，并覆盖失败、重放与并发验收。
- 验证：
  - `git diff --check` 通过；三份未跟踪 V2 文档的 `git diff --no-index --check` 均通过。
  - Markdown 代码围栏成对：总计划 6、M1 40、M2 54。
  - 冲突标记、尾随空白、旧错误正向表述回归检索均为 0；`onelake-app/dagster` 下无 `__pycache__`/`*.pyc` 遗留。
  - 本轮仅修改路线图和规划记录，未修改业务代码，因此未运行 Maven/前端测试。

### 阶段 116：湖仓与建模 V2 路线图三轮 Review 修复
- **状态：** complete
- **开始时间：** 2026-07-14 CST
- 执行的操作：
  - 修正 M1 默认 Catalog seed 的无效 `t.id`，改为子查询实际暴露的 `t.tenant_id`。
  - 将维护调度收敛为 `module-catalog` 内的 `CatalogMaintenanceScheduler`，新增独立 `maintenance_scheduler_lock` 迁移草案，移除新流水线 TaskType/executor 方案。
  - 修正 M2 写审计与权限：`target_fqn` 必填、`asset_id` 可空且 CTAS 注册后回填；WRITE 复用 `security.access_grant.permissions`。
  - 在 modeling V10 增加 `semantic_entity` 建表草案和 Metric 外键；在 `fact_dimension_binding` 增加 `role_name` 与可支持 ROLE_PLAYING 的唯一键。
  - 在 orchestration V34 增加 `pipeline_freeze_override`，将 BREAKING drift 改为 requested/resolved 双事件和原因级冻结/解冻，不改写手工 `schedule_mode`。
  - 同步 V2 总计划、M1/M2 详细任务与 Agentic 提示词，并清理错误注解引号、`CONFLAMED` 拼写和通用 DROP/事务回滚误导。
- 验证：
  - `git diff --check` 通过；三份未跟踪 V2 文档的 `git diff --no-index --check` 均通过。
  - Markdown 代码围栏成对；冲突标记、尾随空白和七项旧错误表述回归检索为 0。
  - 首次 no-index 校验脚本误用 zsh 只读变量 `status`；改为 `rc` 后重跑通过，该脚本失败未修改文件。
  - 本轮仅修改 Markdown 路线图与规划记录，未修改业务代码，因此未运行 Maven/前端测试。

### 阶段 117：G3 算子版本锁定单测与快照边界收口
- **状态：** complete
- **开始时间：** 2026-07-14 CST
- 执行的操作：
  - 读取 `RTK.md`、M4 9.3 任务、M3 `PipelineSnapshotService`、9.1 `PipelineCompileService`、`OperatorService` 精确版本读取和 `OrchestrationService` 运行建档路径。
  - 确认当前生产链路已具备快照执行和精确 Manifest 查询，剩余缺口是快照不完整绑定的边界防御以及跨升级单测覆盖。
  - 与用户确认版本采用语义：算子升级不自动改写草稿节点；只有显式把节点切换到新版本并重新发布，新运行才采用新版本。
  - 写入设计规格 `docs/superpowers/specs/2026-07-14-operator-version-lock-design.md`，提交为 `9ebf15f`。
  - 规划记录首次 patch 因错误复用不同文件的尾部上下文未应用；按三个文件真实结构分别追加后恢复。
  - TDD 红灯：运行 61 个聚焦测试，新增快照不完整绑定用例按预期失败，其余锁定版本编译、v1/v2 复现和 `task_run.operator_version` 用例通过。
  - 在 `PipelineSnapshotService` 生成规范化 JSON 前增加 `operatorRef/operatorVersion` 成对锁定校验，不引入 latest 回退或新数据模型。
  - 聚焦绿灯：`PipelineSnapshotServiceTest,PipelineSparkCompileTest,OrchestrationPipelineTriggerTest` 共 61 个测试全部通过。
  - 同步 M4 9.3 落地状态，记录发布快照、精确 Manifest 编译、运行版本建档和升级复现覆盖。
- 验证：
  - 红灯：聚焦 61 个测试中仅新增快照不完整绑定用例失败，证明缺口位于快照边界。
  - 绿灯：同一聚焦命令 61/61 通过。
  - `mvn -q -pl module-orchestration -am test` 通过；`module-orchestration` 483 个测试，0 failure / 0 error / 0 skipped。
  - `git diff --check` 通过。

### 阶段 118：G3 算子版本锁定真实运行复核
- **状态：** complete
- **开始时间：** 2026-07-14 CST
- 执行的操作：
  - 恢复阶段 113 的真实验证资产：算子 `verify.version_lock_135715`、v1/v2 发布版本、流水线发布版本 1/2、三次成功运行和结果表 `onelake.dwd.codex_operator_version_lock_135715`。
  - 本轮只把当前运行时和数据库中仍可核验的事实作为最终结论；旧记录若已被清理会明确标注，不用计划文本冒充实时证据。
  - 当前态检查通过：后端 `/actuator/health` 为 UP，Trino ACTIVE，Dagster server 可访问；Spark、Hive Metastore、Dagster 和核心存储容器均在运行。
  - 数据库实时复核两个发布快照与三个 run：版本/算子绑定为 `1/1.0.0`、`1/1.0.0`、`2/2.0.0`，三次均 SUCCEEDED、写入 1 行。
  - 当前 Trino 结果表返回 `v2/202`；首次读取 Iceberg `$snapshots` 时 shell 展开了 `$snapshots`，实际查询落到基础表并报列不存在，未修改数据。下一步用单引号保护元数据表名。
  - 正确读取 Iceberg `$snapshots`：保留三次 append 快照，提交时间分别对应三次成功运行；首次时间旅行 UNION 的阶段标签被 shell 引号消耗，查询未成功且未修改数据，已改用双引号 SQL 参数。
  - Iceberg 时间旅行成功：三个快照内容为 `v1/101`、`v1/101`、`v2/202`，分别对应首次 v1、升级不重发、显式改绑并重发 v2。
  - 证据闭环后未触发额外运行：避免产生与用户三阶段验收无关的第四条历史记录；本轮所有运行态操作均为只读查询。

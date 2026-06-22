# ODS 到 DWD 标准闭环实施方案

> 日期：2026-06-22
> 范围：OneLake 从已入湖 ODS 表派生、调度、校验、登记 DWD 明细表的产品与工程闭环。

## 1. 目标边界

本方案的目标不是再做一个“建表向导”，而是打通一条标准数据工程链路：

```text
源端表 -> Airbyte 采集 -> ODS 原始保真表 -> ODS 成功事件
      -> DWD 模型定义 -> dbt 生成/执行 -> DWD 明细表
      -> 质量门禁 -> Catalog/血缘/新鲜度 -> 前端可观测
```

OneLake 当前已有采集、Catalog、质量、SQL 工作台、dbt 示例和 Dagster code location，但缺少把这些能力串起来的 DWD 模型运行闭环。第一阶段应以“一个 ODS 表生成一个 DWD 表”为最小可用闭环，之后再扩展多表 Join、Schema 变更审批、Backfill 和生产治理。

## 2. 产品对标结论

| 产品/模式 | 对 OneLake 的启发 |
| --- | --- |
| Databricks Medallion | ODS 对应 Bronze，保留原始数据和审计能力；DWD 对应 Silver，做清洗、去重、标准化和非聚合明细建模。采集不应默认直写 DWD。 |
| Airbyte | 源端同步只负责把数据以 Full Refresh/Incremental/CDC 方式进入 ODS，并携带 checkpoint、批次、CDC 元数据。 |
| dbt | DWD 模型应该是声明式 SQL + sources/ref + tests + incremental materialization，而不是页面里临时执行 INSERT。 |
| Dagster | DWD 模型应以 asset/job 的形式被调度，记录运行状态、日志、依赖和失败原因。 |
| OpenMetadata/Catalog | dbt manifest/run_results/catalog 可作为表级、字段级血缘、文档和运行结果的标准来源。 |

## 3. 不跑偏原则

1. ODS 是唯一默认采集落点；DWD 直写只能作为高阶例外，不能作为主链路。
2. DWD 数据不从 SQL 工作台手工写入；SQL 工作台保持查询、草稿和诊断，不承担生产写入。
3. Java 控制面不直接搬运业务数据；它保存模型定义、触发 Dagster/dbt、接收状态、更新治理元数据。
4. 每轮迭代都必须有真实验收对象：表、事件、run、日志、质量结果、Catalog 资产或浏览器页面。
5. 第一轮只做单源单目标模型，先把链路跑通，再做多表 Join、可视化编排和复杂 Schema 演进。

## 3.1 整体链路匹配评审

| 链路环节 | 所属产品域 | 当前基础 | 方案要求 | 评审结论 |
| --- | --- | --- | --- | --- |
| 源端入湖到 ODS | 数据集成 | Airbyte trigger/reconcile、`integration.table.loaded`、ODS Catalog 建档已具备 | ODS 作为默认采集落点，保留原始字段、批次和 checkpoint | 匹配整体设计，可作为 DWD 上游事件源 |
| ODS 资产与字段可信 | Catalog + Security | Catalog 已能接 `integration.table.loaded`，字段 schema 和 PII 标签已有基础 | DWD 派生前必须校验 ODS 资产存在且字段非空，敏感字段默认不自动透传 | 需要在迭代 1 强制作为准入条件 |
| DWD 模型定义 | 建模 | `module-modeling` 目前偏指标/标准骨架 | DWD 模型作为持久化对象，保存 source/target、字段映射、SQL/dbt、增量策略 | 是新增核心能力，必须先做草稿和校验 |
| DWD 运行编排 | 数据开发编排 | `orchestration.dag/job_run`、Dagster launch 基础能力已有，但当前 Dagster code location 只有 schedule reconcile job | DWD 模型必须同步成为可编排的 DAG/Asset，能手动触发和事件触发 | 原方案方向正确，但需显式补“模型到编排 DAG/Asset 的映射” |
| dbt 执行 | 数据面 | dbt 项目有示例模型，未接 Dagster dbt runtime | 由 Dagster 调用 `dbt build --select <model>`，产出 run_results/manifest/catalog | 需要在迭代 3 明确产物路径和状态解析 |
| 质量门禁 | 质量 | 质量模块已有规则/结果/告警最小闭环，但现有试跑不是 Trino/dbt 真稽核 | dbt tests 或 Dagster checks 失败要写 `quality.run_result` 与告警 | 需要从 dbt artifacts 写入质量，而不是复用控制面假试跑 |
| DWD 资产回写 | Catalog/血缘 | Catalog 支持资产、字段、lineage edge、字段级映射 | DWD 成功后 upsert DWD asset，写 ODS->DWD 表级/字段级血缘 | 匹配整体设计，需要新增模型运行成功事件 |
| 前端可观测 | 湖仓 + 数据开发 | 湖仓表详情、Catalog、质量、SQL、编排页面已有基础 | 用户能从 ODS 详情进入 DWD 模型，并看到运行、质量、血缘 | 原方案迭代 6 合理，但首批也要提供最小运行入口 |

## 3.2 评审后必须补强的设计约束

1. **DWD 模型同时属于建模和编排。** `module-modeling` 保存业务定义，`module-orchestration`/Dagster 负责运行。不能只保存模型草稿而不生成或关联可运行 DAG/Asset。
2. **运行事件要分层。** `integration.table.loaded` 只表示 ODS 入湖完成；DWD 运行完成后应新增模型运行事件，例如 `modeling.model.loaded`、`modeling.model.failed`，再由 Catalog/Quality/通知消费。
3. **质量结果来自真实执行产物。** 第一批可以用 dbt tests，读取 `run_results.json` 写入 `quality.run_result` 和 `quality.alert`；不要把质量模块现有模拟试跑当成 DWD 生产门禁。
4. **字段安全标签要沿血缘继承。** DWD 字段映射应继承 ODS 字段的 `classification/piiType/suggestLevel`，除非模型显式脱敏或降级，并记录原因。
5. **dbt 文件生成是 MVP 实现，不是最终形态。** 本地第一批可以写 `onelake-app/dbt`，但模型元数据必须以数据库为准；后续可替换为模型注册表、GitOps 或对象存储产物，不影响控制面契约。
6. **编排不等于页面画布。** 第一批只要求 DWD 模型可运行、可追踪、可重跑；拖拽画布和复杂 DAG 拓扑属于后续增强。

## 3.3 加工治理、流水线与算力资源兼容评审

用户从 ODS 派生 DWD 时，真实要完成的不是“复制一张表”，而是一组可审计的数据加工和治理操作：字段标准化、类型转换、去重、脏数据过滤、敏感字段脱敏/加密、质量门禁、异常隔离、DWD 写入和血缘回写。这部分与前端已有的流水线、算子市场、资源组能力有关，但三者职责不同：

| 概念 | OneLake 当前对应 | 在 ODS->DWD 中的职责 | 评审意见 |
| --- | --- | --- | --- |
| 流水线 | `orchestration.dag`、`job_run`、前端 `/orchestration/pipelines`、`DagCanvas` | 承载 ODS input、加工治理节点、dbt/Dagster 执行节点、DWD output 和调度触发 | 必须接入，DWD 模型不能只停留在 `module-modeling` |
| 算子市场 | 前端 `OperatorMarket`，当前是清洗、脱敏、加密、MDM 等算子展示 | 沉淀可复用加工治理能力，供 DWD 默认节点或后续画布拖拽复用 | 兼容，但第一批不要依赖拖拽；先以系统默认算子生成 DAG |
| 算力/资源组 | SQL 工作台已有 `resourceGroup` 选择，后端 DAG 还没有资源字段 | 控制 dbt/Trino/Dagster 运行使用的资源池、并发、成本阈值和重试策略 | 原方案缺口，需要补充资源契约；如果产品上叫“算力市场”，应落成资源画像/资源组，不要和算子市场混用 |

主流产品的共同做法也支持这个拆分：

| 产品/方案 | 观察 | 对 OneLake 的要求 |
| --- | --- | --- |
| Databricks Lakeflow Jobs / Pipelines | Jobs 负责编排多任务，任务可选择 serverless、classic jobs compute、SQL warehouse 等不同计算资源；Expectations 在流水线中做质量约束，Unity Catalog 自动记录表/字段级血缘 | DWD 模型需要同时绑定 job/pipeline、compute profile、quality gate 和 Catalog lineage |
| Dagster + dbt | dbt model 被表示为 Dagster asset，可跟踪单模型失败、日志、运行历史；dbt tests 可映射为 asset checks | DWD 模型应映射为 Dagster asset/job，质量规则不要只存在页面配置里 |
| dbt | `run_results.json` 记录每次执行的 model/test 状态、耗时和错误 | DWD 质量与运行观测应从 dbt artifacts 解析，而不是伪造控制面状态 |
| Airflow | DAG 表达任务依赖，Pools 用于限制一组任务的并发资源槽位 | OneLake 的 `resourceGroup` 应进入 DAG/model_run/job_run，支撑排队、限流和成本治理 |
| Azure Data Factory / Fabric Data Factory | 可视化 data flow 被作为 pipeline activity 运行，底层使用托管 Spark 集群，调度、监控由 pipeline 承载 | 可视化算子不是最终执行物，必须编译成可运行的数据面任务 |
| AWS Glue Data Quality | 质量规则既可作用于 Data Catalog，也可内嵌在 ETL job/可视化流水线中，支持识别失败记录和隔离 | DWD 质量门禁要支持 fail/drop/warn/quarantine，不只是跑后打分 |

### 兼容性结论

现有 ODS->DWD 方案与流水线和算子市场方向兼容，但还不充分。方案已经要求生成 `orchestration.dag` 草稿、接 Dagster/dbt、写质量和血缘；缺的是把“加工治理算子”和“算力资源”变成稳定契约。如果不补这层，后续会出现三个偏差：

1. DWD 模型是一个 SQL 草稿，流水线画布又是另一份 DAG，二者状态和版本会漂移。
2. 算子市场停留在前端展示，清洗/脱敏/质量门禁没有进入真实执行链路。
3. 资源组只在 SQL 工作台生效，DWD 生产运行没有资源隔离、成本估算和排队观测。

### 必须补充的契约

- `modeling.data_model` 增加或预留：
  - `pipeline_mode`：`SYSTEM_GENERATED/CANVAS_EDITED`
  - `orchestration_dag_id`
  - `operator_graph_version`
  - `resource_group`
  - `compute_profile`
  - `engine`：第一批固定 `TRINO_DBT`，后续扩展 Spark
  - `cost_policy`：大扫描确认、超时、并发和重试策略
- `orchestration.dag.definition.nodes[]` 统一保存节点契约：
  - `nodeType`：`INPUT/TRANSFORM/GOVERN/MASK/ENCRYPT/QUALITY_GATE/DBT_MODEL/OUTPUT`
  - `operatorRef/operatorVersion`
  - `config`：字段表达式、去重键、脱敏策略、质量规则、异常处理动作
  - `inputRefs/outputRefs`
  - `resourceProfile`
  - `policy.actionOnViolation`：`WARN/DROP/FAIL/QUARANTINE`
  - `emitsLineage/emitsQualityResult`
- `modeling.model_run` 和 `orchestration.job_run` 增加或预留运行资源观测：
  - `resource_group/compute_profile`
  - `queued_at/started_at/finished_at`
  - `engine_run_id/dagster_run_id/trino_query_id`
  - `estimated_scan_bytes/actual_scan_bytes`
  - `cost_estimate/queue_reason/retry_count`
- 算子市场第一批只作为“算子定义来源”，不作为拖拽依赖：
  - DWD 向导默认生成清洗、标准化、质量门禁、输出节点。
  - 脱敏/加密节点由 ODS 字段安全标签自动建议。
  - 后续画布编辑时读取同一份 operator graph，不另起一套 DAG DSL。

## 4. 迭代 0：现状收口与样例数据基线

### 目标

在正式开发前固定一个可重复验证的样例链路，避免后续每个模块都用不同测试数据。

### 后端/数据面

- 复用现有真实联调链路：本地 Postgres 源表通过 Airbyte 同步到 Iceberg/Trino ODS。
- 固定一组样例：
  - 源端：`public.codex_orders`
  - ODS：`ods.ods_codex_orders`
  - DWD：`dwd.dwd_trade_order_df`
- 给 ODS 表补齐测试字段：`order_id/user_id/amount/status/order_time/updated_at`，至少 5-10 行，包含 1-2 条脏数据。
- 确认 `integration.table.loaded` 能生成 ODS Catalog 资产与字段 schema。

### 验收

- Airbyte run 为 `SUCCEEDED`，`rowsRead/rowsWritten > 0`。
- `catalog.asset` 中存在 ODS 表，字段非空。
- Trino 可查询 `select count(*) from ods.ods_codex_orders`。

### 不做

- 不改 UI。
- 不引入通用模型设计器。

## 5. 迭代 1：ODS 入湖契约与 DWD 派生入口

### 目标

把“DWD 从哪个 ODS 来”变成产品上明确的动作，而不是让用户新建一个空 DWD 表后自己猜。

### 前端

- 在 ODS 表详情页增加动作：`派生 DWD 明细表`。
- 点击后进入现有建表/建模向导的 DWD 模式，自动带入：
  - 上游 ODS FQN
  - 业务域
  - 字段 schema
  - 建议目标表名，如 `dwd_trade_order_df`
- 向导第一轮只支持单 ODS 表派生单 DWD 表。

### 后端/API

- 新增 DWD 模型草稿实体，建议落在 `module-modeling`：
  - `modeling.data_model`
  - `modeling.data_model_source`
  - `modeling.data_model_column_mapping`
- 最小字段：
  - `id/tenant_id/name/layer/domain/source_fqn/target_fqn/status`
  - `materialization`：`VIEW/TABLE/INCREMENTAL`
  - `unique_key/incremental_column/partition_expr`
  - `sql_text/compiled_sql/dbt_model_name`
  - `orchestration_dag_id/dagster_job/artifact_path/last_run_id`
  - `owner_id/owner_name/created_at/updated_at`
- API：
  - `POST /api/v1/modeling/models/dwd/draft`
  - `GET /api/v1/modeling/models/{id}`
  - `PUT /api/v1/modeling/models/{id}`
  - `POST /api/v1/modeling/models/{id}/validate`

### 校验

- `source_fqn` 必须存在于 Catalog 且 layer 为 ODS。
- `source_fqn` 对应资产字段不能为空；如果字段为空，应先调用 Catalog 字段补全或阻断派生。
- `target_fqn` 必须是 `dwd.<dwd_...>`，不允许 ODS/ADS 反向依赖。
- 字段映射中目标字段名、类型、主键、分区字段合法。
- 目标字段默认继承上游字段密级/PII 标签；如果字段表达式包含脱敏或 hash，可显式降低建议密级并记录转换说明。

### 验收

- 从 ODS 详情页能创建 DWD 模型草稿。
- 后端能返回草稿详情，且 source/target/layer 校验有效。
- 草稿中能看到关联的编排占位信息，例如 `dagsterJob/dbtModelName`，但状态仍为未发布。
- 不生成真实 dbt 文件，不执行数据写入。

### 不做

- 不做多源 Join。
- 不做复杂 SQL 编辑器。
- 不触发 Dagster。

### 实施状态（2026-06-22）

- 已完成 DWD 模型草稿持久化：`modeling.data_model`、`modeling.data_model_source`、`modeling.data_model_column_mapping`。
- 已完成 API：`POST /api/v1/modeling/models/dwd/draft`、`GET/PUT /api/v1/modeling/models/{id}`、`POST /api/v1/modeling/models/{id}/validate`。
- 已完成前端入口：ODS 表详情展示“派生 DWD”，向导支持 `derive=dwd&sourceAssetId=...`，保存到建模草稿而不是直接建物理表。
- 已通过样例验证：`ods.ods_codex_orders` 可派生 `dwd.dwd_trade_codex_orders_df` 草稿，字段映射 6 条，validate 返回 `ok=true`。
- 仍未完成：dbt 文件生成、orchestration DAG 草稿、Dagster/dbt 执行、DWD Catalog 回写；这些进入迭代 2/3。

## 6. 迭代 2：DWD SQL/dbt 生成与静态校验

### 目标

把模型草稿转换成可运行的 dbt 模型，先做到“生成正确、校验可解释”。

### 后端

- 新增 `DwdModelCompiler`：
  - 根据字段映射生成 `select ... from {{ source('ods', '<table>') }}`
  - 生成基础清洗表达式：类型转换、trim、空值过滤、状态字段标准化
  - 生成目标模型名：`dwd_trade_order_df`
- 生成或更新 dbt 产物：
  - `onelake-app/dbt/models/sources.yml`
  - `onelake-app/dbt/models/intermediate/<model>.sql`
  - `onelake-app/dbt/models/intermediate/schema.yml`
- 生成或更新编排草稿：
  - `orchestration.dag` 记录 DWD 模型的 input/output 节点
  - `enabled=false`
  - `dagster_job` 先指向后续统一 job：`onelake_dbt_model_run`
- 新增静态校验：
  - SQL 单语句 SELECT
  - source 表存在
  - 输出字段和 DWD 表字段一致
  - `INCREMENTAL` 必须有 `unique_key` 或明确 append-only 策略

### dbt 策略

- 第一版默认 `materialized='table'` 或 `view`。
- 增量模型在下一轮接入，但字段先预留：
  - `unique_key`
  - `incremental_column`
  - `incremental_strategy`

### 验收

- 调用 validate 后能得到：
  - compiled SQL
  - 依赖 ODS 表
  - 输出字段列表
  - 可读错误列表
- 本地执行 `dbt parse` 或等价命令通过。
- 文件生成幂等：同一个模型重复保存不会追加重复 YAML。
- `orchestration.dag.definition` 中能看到 ODS input、DWD output 和 dbt model 节点。

### 不做

- 不执行 `dbt run`。
- 不更新 Catalog DWD 资产为可用。

### 实施状态（2026-06-22）

- 已完成 `POST /api/v1/modeling/models/{id}/compile`，在模型 validate 通过后生成 dbt 产物。
- 已生成并验证样例产物：
  - `onelake-app/dbt/models/generated/sources.yml`
  - `onelake-app/dbt/models/intermediate/dwd_trade_codex_orders_df.sql`
  - `onelake-app/dbt/models/intermediate/dwd_trade_codex_orders_df.yml`
- 已创建/更新 disabled `orchestration.dag` 草稿，`dagsterJob=onelake_dbt_model_run`，不触发 Dagster。
- 已通过 `uvx --from dbt-trino dbt parse --profiles-dir .`；当前仅有既有 staging 配置未命中 warning。
- 实施差异：source YAML 先写到 `models/generated/sources.yml`，避免覆盖手写 `models/sources.yml`；后续多模型阶段再做集中合并生成。

## 6.5 迭代 2.5：加工治理算子与算力资源契约

### 目标

在 DWD 模型真正执行前，把“这个 DWD 做了哪些加工治理操作、使用哪类计算资源、失败时如何处理”固定为可持久化、可校验、可编排的契约，避免后续流水线、算子市场和 DWD 模型各自维护一套定义。

### 后端

- 在 DWD 模型保存/校验阶段生成逻辑 operator graph：
  - `INPUT`：上游 ODS 表。
  - `TRANSFORM`：字段选择、重命名、类型转换、trim、枚举标准化。
  - `GOVERN`：去重、空值过滤、异常值过滤。
  - `MASK/ENCRYPT`：敏感字段脱敏、hash 或加密；由 ODS 字段 `classification/piiType/suggestLevel` 自动建议。
  - `QUALITY_GATE`：主键、唯一性、枚举、金额范围、新鲜度、空表检查。
  - `DBT_MODEL`：编译后的 dbt model。
  - `OUTPUT`：DWD 目标表。
- `DwdModelCompiler` 负责把 operator graph 编译为：
  - dbt SQL 表达式。
  - dbt tests/schema YAML。
  - `orchestration.dag.definition` 节点和边。
  - 字段级血缘映射和安全标签继承说明。
- 新增或预留资源画像字段：
  - DWD 模型、DAG、model run 都保存 `resource_group/compute_profile/engine`。
  - 第一批可只支持 `rg-default` 和 `rg-big`，含并发、超时、扫描阈值和重试策略。
  - 后续如果建设“算力市场”，应注册这些 compute profile，而不是让每个页面私有定义。
- 校验服务增加：
  - 算子输入/输出字段是否闭合。
  - 敏感字段是否被透传、脱敏或加密，不能无说明降级。
  - 质量门禁 action 是否明确。
  - resource group 是否存在、是否支持当前 engine。

### 前端

- DWD 向导增加两个轻量步骤，不做完整拖拽画布：
  - `加工治理`：展示系统生成的清洗、脱敏、质量门禁、输出节点，可调整必要参数。
  - `运行资源`：选择资源组/计算画像，展示并发、超时、扫描阈值和适用引擎。
- 保存后跳转到流水线画布时，画布读取真实 `orchestration.dag.definition` 渲染，而不是本地 mock 节点。
- 算子市场继续作为算子定义展示入口；DWD 第一批只消费内置算子定义，不要求用户手动拖入。

### 数据面

- 清洗/标准化/脱敏算子优先编译为 dbt SQL 或 dbt macro。
- 质量门禁优先编译为 dbt tests；后续可映射为 Dagster asset checks。
- `QUARANTINE` 行为第一批可先记录为策略，不落异常表；如果选择 `FAIL`，必须让 `dbt build` 失败并写入质量告警。

### 验收

- DWD 草稿详情能看到 operator graph 和 resource profile。
- `orchestration.dag.definition` 中包含 input、transform/govern、quality gate、dbt model、output 节点。
- validate 结果能返回算子编译错误、敏感字段透传风险和资源不兼容错误。
- SQL 工作台生成的流水线草稿和 DWD 生成的流水线草稿使用同一套 DAG definition 节点契约。

### 不做

- 不建设完整算力市场计费、配额售卖或资源申请审批。
- 不做任意多引擎路由；第一批固定 Trino/dbt/Dagster。
- 不要求拖拽画布编辑后反向生成复杂模型；可先标记 `pipeline_mode=CANVAS_EDITED` 并阻断自动覆盖。

### 实施状态（2026-06-22）

- 已为 `modeling.data_model` 增加 `pipeline_mode/operator_graph_version/operator_graph/resource_group/compute_profile/engine/cost_policy`。
- compile 阶段已生成系统默认 operator graph，并同步写入模型与 `orchestration.dag.definition.operatorGraph`。
- 样例节点链路为 `INPUT -> TRANSFORM -> GOVERN -> QUALITY_GATE -> DBT_MODEL -> OUTPUT`；存在敏感字段时会插入 `MASK` 节点。
- 默认资源画像为 `TRINO_DBT/default/trino-small`，默认 cost policy 为 1TB 扫描阈值、30 分钟超时、0 次重试、大扫描需确认。
- 仍未完成：资源组后台管理、算子市场真实 manifest、画布编辑反写、model run 资源观测；这些进入迭代 3 之后的运行态增强。

## 7. 迭代 3：Dagster dbt 执行最小闭环

### 目标

让 DWD 模型可以被控制面触发执行，并把运行状态写回 OneLake。

### Dagster

- 在 `onelake-app/dagster/definitions.py` 中引入 dbt 项目：
  - 使用 `dagster-dbt`
  - 将 dbt models 暴露为 Dagster assets
  - 新增 job：`onelake_dbt_model_run`
- run config 至少支持：
  - `model_name`
  - `model_id`
  - `tenant_id`
  - `trigger_type`
- 执行命令建议是 `dbt build --select <model_name>`，而不是只 `dbt run`，以便同时跑 tests。

### Java 编排

- 扩展 `DagsterClient.launch`，支持传 run config/tags。
- 新增模型运行表：
  - `modeling.model_run`
  - 字段：`model_id/status/trigger_type/source_integration_run_id/orchestration_dag_id/dagster_run_id/started_at/finished_at/error_msg/rows_read/rows_written/artifacts_path`
- API：
  - `POST /api/v1/modeling/models/{id}/run`
  - `GET /api/v1/modeling/models/{id}/runs`
  - `GET /api/v1/modeling/model-runs/{runId}`

### 状态回写

- 第一版可以由 Java 轮询 Dagster GraphQL。
- 后续再升级为 Dagster sensor/callback。
- 状态映射：`QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED`。
- 运行结束后解析 dbt artifacts：
  - `run_results.json`：模型/test 成败、错误信息、耗时
  - `manifest.json`：依赖、字段文档、测试定义
  - `catalog.json`：字段类型、行数/统计信息（可得时使用）
- 运行终态发布模型事件：
  - 成功：`modeling.model.loaded`
  - 失败：`modeling.model.failed`

### 验收

- 前端/接口触发模型运行后，Dagster 出现 run。
- dbt 成功生成 DWD 表或视图。
- OneLake `model_run` 状态从 `RUNNING` 变为 `SUCCEEDED/FAILED`。
- Trino 可查询 DWD 表。
- 成功/失败事件进入 outbox，并至少被本地日志或测试 consumer 验证。

### 不做

- 不做自动事件触发。
- 不做 Backfill。
- 不接 OpenMetadata 回写。

### 实施状态（2026-06-22）

- 已新增 `modeling.model_run`，记录 DWD 模型运行的 `status/trigger_type/orchestration_dag_id/dagster_run_id/resource_group/compute_profile/artifacts_path` 等运行态字段。
- 已新增 API：`POST /api/v1/modeling/models/{id}/run`、`GET /api/v1/modeling/models/{id}/runs`、`GET /api/v1/modeling/model-runs/{runId}`。
- `POST run` 只允许运行 `VALIDATED` 模型；会创建 `model_run`、传递 `model_name/model_id/run_id/tenant_id/trigger_type/source_fqn/target_fqn/resource` 到 Dagster，并把返回的 Dagster runId 写回。
- `GET model-runs/{runId}` 会按 Dagster runId 刷新运行终态，把 Dagster `SUCCESS/FAILURE/CANCELED` 映射为 OneLake `SUCCEEDED/FAILED/CANCELLED`。
- Dagster code location 已新增 `onelake_dbt_model_run` job，容器安装 `dbt-core/dbt-trino`，挂载 `onelake-app/dbt`，执行 `dbt build --select <model_name> --profiles-dir <profiles_dir>`。
- 已解析 dbt `target/run_results.json`，从模型结果写回 `rowsRead/rowsWritten/artifactsPath/errorMsg`；如果有 `target/catalog.json`，可用其中行数兜底。
- 已新增并发布 `modeling.model.loaded`、`modeling.model.failed` 事件，payload 包含 `modelId/runId/sourceFqn/targetFqn/dagsterRunId/rowsWritten/fieldMapping`。
- 已通过 `mvn -q -pl module-modeling -am test` 验证控制面 run API、Dagster runId 写回、状态刷新、artifact 解析和模型事件发布逻辑。
- 已完成真实环境验收：`make ods-dwd-baseline` 准备 ODS 10 行样例；重建 Dagster user-code 后，GraphQL 触发 `onelake_dbt_model_run`，run `10d81280-2cc5-480b-a763-e142a1aa04ec` 返回 `SUCCESS`；`dbt build` 产出 `dwd.dwd_trade_codex_orders_df`，模型和 2 个主键测试通过，Trino 查询 DWD 行数为 10。
- 修复运行验收中发现的两个数据面问题：移除 dbt Trino profile 中当前 Trino 不支持的 `iceberg.compression-codec` session property；新增 `generate_schema_name` 宏，确保 DWD 模型落到 `dwd` schema 而不是 dbt 默认拼接出的 `ods_dwd` schema。

## 8. 迭代 4：ODS 成功事件自动触发 DWD

### 目标

采集任务成功后，如果有 DWD 模型依赖该 ODS 表，自动运行 DWD。

### 事件链路

- 继续使用现有 `integration.table.loaded`。
- Orchestration/Modeling 消费事件时：
  - 读取 `targetTable`，只处理 layer=ODS 的资产。
  - 查找 enabled 的 DWD 模型，条件是 `source_fqn = targetTable`。
  - 校验模型已发布、dbt 产物已生成、编排 DAG/Asset 可触发。
  - 为每个模型创建 `model_run`。
  - 调用 Dagster 启动 dbt job。

### 幂等

- 同一个 `integration run id + model id` 只能触发一次。
- 如果已有运行中 model run，默认跳过或标记为 `SKIPPED_DUPLICATE`。
- 如果 ODS 事件缺字段 schema，只记录可观测 warning，不硬跑 DWD。
- DWD 运行失败不能反写影响 ODS sync_run 结果；失败归属在 `model_run` 和质量/告警域。

### 验收

- 重新触发 Airbyte ODS 采集，成功后自动出现 DWD model run。
- DWD 表行数随 ODS 数据更新。
- 事件、run、日志能串起来：
  - sync_run -> integration.table.loaded -> model_run -> Dagster run -> DWD asset

### 不做

- 不支持一个 ODS 扇出几十个模型的并发治理。
- 不做跨模型 DAG 拓扑排序；第一版只处理 ODS -> DWD 一跳。

### 实施状态（2026-06-22）

- 已新增 `DwdOdsLoadedEventHandler`，订阅 `integration.table.loaded`，仅处理 ODS 表事件，并按 `tenantId + source_fqn` 查找 `VALIDATED` DWD 模型。
- 已实现自动触发：命中模型后创建 `triggerType=ODS_EVENT` 的 `modeling.model_run`，复用迭代 3 的 Dagster/dbt run 路径启动 `onelake_dbt_model_run`。
- 已实现幂等与保护：
  - 同一 `modelId + sourceIntegrationRunId` 已触发时跳过。
  - 模型存在 `QUEUED/RUNNING` 活跃运行时跳过。
  - 缺少 `targetTable/tenantId`、非 ODS 表、非法 tenantId 的事件跳过且不阻塞 Redis Stream 消费。
  - 新 consumer group 回放历史事件时，如果 ODS 事件早于模型验证/更新时间，则跳过，避免旧事件误触发新模型。
- 已完成真实环境验收：重启当前后端后，Redis Stream 出现 `modeling` consumer group；历史基线 `integration.table.loaded` 事件触发 DWD model run `3496d80b-28c4-4392-9325-23c5327c78b8`，Dagster run `a7d9a59a-7f00-4807-a28f-a4d4ae018c1e` 返回 `SUCCESS`。
- 已通过 API 轮询刷新终态：`GET /api/v1/modeling/model-runs/3496d80b-28c4-4392-9325-23c5327c78b8` 返回 `status=SUCCEEDED`、`rowsWritten=10`、`artifactsPath=target/run_results.json`。
- 已验证数据面结果：`dbt/target/run_results.json` 中模型 `dwd_trade_codex_orders_df` 为 `success`，2 个主键测试均 `pass`；Trino 查询 `iceberg.dwd.dwd_trade_codex_orders_df` 行数为 10。
- 已验证事件总线结果：`modeling.model.loaded` 已写入并发布到 outbox；`stream:integration.table.loaded` 的 `modeling` consumer group pending 为 0。
- 仍未完成：DWD 成功后的 Catalog DWD 资产回写、质量结果落库、ODS->DWD 血缘回写；这些进入迭代 5。

## 9. 迭代 5：质量门禁与 Catalog/血缘回写

### 目标

让 DWD 产出不是“跑完就算成功”，而是经过质量门禁并进入资产目录。

### 质量

- 对 DWD 模型生成 dbt tests：
  - 主键 not_null/unique
  - 金额范围、状态枚举、时间字段合法性
  - 行数漂移或空表检查
- dbt build 失败时：
  - `model_run.status=FAILED`
  - 写 `quality.run_result`
  - 生成 `quality.alert`
  - DWD Catalog 资产不标记为可用或保留上一版本状态
- dbt build 成功但 tests 失败时，运行应视为门禁失败；Catalog 可以记录最新尝试时间，但不能刷新为“健康可用”。

### Catalog

- DWD 成功后 upsert Catalog asset：
  - `omFqn=target_fqn`
  - `layer=DWD`
  - `columns`
  - `rowCount`
  - `lastSyncAt`
  - `qualityScore`
  - `sourceModelId/sourceRunId`
  - `upstreamIntegrationRunId/modelRunId`
- 写血缘：
  - `ods.xxx -> dwd.xxx`
  - 字段级映射从模型 column mapping 或 dbt manifest 提取。
- 合并字段安全标签：
  - 默认继承 ODS 字段 `classification/piiType/suggestLevel`
  - 已脱敏字段记录转换说明和新的建议密级

### 验收

- DWD 成功后湖仓表管理能看到 DWD 表。
- DWD 详情页能看到字段、血缘、质量状态、新鲜度。
- 质量失败时能在质量门禁页看到真实告警。

### 不做

- 不把 OpenMetadata 作为本地闭环阻塞项。
- 不做质量豁免审批全流程，先复用现有告警关闭能力。

### 实施状态（2026-06-22）

- 已新增 `DwdModelLoadedEventHandler`，订阅 `modeling.model.loaded`，在 DWD 运行成功后自动 upsert `catalog.asset`，写入 `layer=DWD`、`assetType=TABLE`、`columns`、`rowCount`、`format=ICEBERG`、`lastSyncAt/syncedAt` 等资产字段。
- 已新增 ODS->DWD 血缘回写：同一 handler 按事件中的 `sourceFqn/targetFqn/fieldMapping/runId` upsert `catalog.lineage_edge`，记录表级边 `ods.ods_codex_orders -> dwd.dwd_trade_codex_orders_df` 与 6 条字段级映射。
- 已扩展 `DwdRunArtifactReader` 与 `DwdModelService`，从 dbt `target/run_results.json` 解析 `test.*` 结果，并在 `modeling.model.loaded/failed` payload 中携带 `qualityChecks`。
- 已新增 `DwdModelQualityEventHandler`，订阅 `modeling.model.loaded/failed`，把 dbt build/test 结果落到 `quality.rule` 与 `quality.run_result`；失败时生成 `quality.alert`，并发布 `quality.check.completed/failed` 供 Catalog 更新质量分。
- 已完成真实环境验收：手动触发 DWD model run `37b715ee-2ae1-4d8c-b479-ab9cf12615e4`，Dagster run `17516091-2afa-4cfd-a229-2e871e4e523a` 返回成功，`rowsWritten=10`，事件 payload 中 `qualityChecks=2`（`not_null(order_id)`、`unique(order_id)` 均 pass）。
- 已验证 Catalog 结果：`catalog.asset` 中 `dwd.dwd_trade_codex_orders_df` 为 `layer=DWD`、`row_count=10`、`quality_score=100.00`、`columns_count=6`；`catalog.lineage_edge` 的 `job_ref` 更新为本次 model run，字段级血缘数为 6。
- 已验证 Quality 结果：`quality.rule/run_result` 生成 `NOT_NULL` 与 `UNIQUE` 两条规则结果，`job_run_id=37b715ee-2ae1-4d8c-b479-ab9cf12615e4`，均 `passed=true`、`pass_rate=100.00`、`failed_rows=0`；本次 DWD 目标无新增 open alert。
- 已验证事件链：`modeling.model.loaded` 共 2 条均为 `PUBLISHED`；`quality.check.completed` 新增 2 条并均被 Catalog 消费，`stream:modeling.model.loaded` 与 `stream:quality.check.completed` consumer group pending/lag 均为 0。
- 仍未完成：质量豁免审批、行数漂移/空表等更多质量规则、字段安全标签继承的复杂脱敏说明、OpenMetadata 外部同步；这些不阻塞第一批 DWD MVP。

## 10. 迭代 6：前端运行可观测闭环

### 目标

用户可以从 ODS 表一路看到 DWD 是怎么来的、跑没跑、为什么失败。

### 页面

- ODS 详情页：
  - 下游 DWD 模型列表
  - 最近一次 DWD 运行状态
  - “派生 DWD 明细表”
- DWD 模型详情页：
  - 模型配置
  - 上游 ODS
  - 输出 DWD
  - SQL/dbt 预览
  - 运行历史
  - 质量结果
  - 血缘入口
- DWD 表详情页：
  - 来源模型
  - 最近运行
  - 质量门禁
  - 字段级血缘

### API 接入

- 新增 `ModelingAPI`，不要把 DWD 模型接口塞进 `CatalogAPI`。
- 页面保持 OneLake 现有 Ant Design/SectionCard 风格，不做视觉重构。

### 验收

- 浏览器完成真实路径：
  - ODS 详情 -> 派生 DWD -> 保存模型 -> 运行 -> 查看 DWD 表 -> 查看血缘/质量。
- 所有失败都展示后端真实错误，不显示泛化 `Request failed`。

### 不做

- 不做拖拽式建模画布。
- 不做营销式页面或大改 UI。

### 实施状态（2026-06-22）

- 已新增后端只读查询面：`GET /api/v1/modeling/models?sourceFqn=...` 与 `?targetFqn=...`，用于从 ODS 资产反查下游 DWD 模型、从 DWD 资产反查来源模型；`GET /models/{id}/runs` 与 `POST /models/{id}/run` 继续作为运行历史和触发入口。
- 已扩展前端 `ModelingAPI`，新增 `listModels`、`runModel`、`listModelRuns`、`getModelRun`，并补齐 `DwdModelRun` 类型；DWD 模型接口保持在 ModelingAPI 内，没有塞进 CatalogAPI。
- 已增强 `TableDetail`：
  - ODS 详情页新增 `DWD 模型` tab，展示下游 DWD 模型列表、模型状态、最近 DWD run、Dagster runId、dbt artifact 路径，并提供 `派生 DWD`、`编译校验`、`运行`、`刷新模型状态`。
  - DWD 表详情页同一 tab 展示来源模型、上游 ODS、输出 DWD、运行历史摘要、SQL/dbt 预览、字段映射。
  - 新增 `质量` tab，展示资产质量分、规则总数、失败数、最近检查时间，以及 `NOT_NULL/UNIQUE/DBT_BUILD` 等真实规则结果。
  - 血缘 tab 增加字段级血缘表，展示 `ods.ods_codex_orders` 到 `dwd.dwd_trade_codex_orders_df` 的 6 条字段映射。
- 已完成真实 API 验收：
  - `GET /modeling/models?sourceFqn=ods.ods_codex_orders` 返回 3 个 DWD 模型，其中 `dwd_trade_codex_orders_df` 为 `VALIDATED`。
  - `GET /modeling/models?targetFqn=dwd.dwd_trade_codex_orders_df` 返回 1 个来源模型。
  - `GET /modeling/models/346b0f13-712b-41a2-8749-a25f96c19924/runs` 返回 2 条历史运行（后续浏览器运行后为 3 条），均有真实 Dagster runId 与 `target/run_results.json`。
- 已完成浏览器路径验收：
  - ODS 详情 `/lakehouse/tables/a5e6f4aa-7ef7-4acd-8406-c5ebd07f6499` 显示 3 个下游 DWD 模型，草稿模型的运行按钮禁用，已验证模型展示最近成功 run。
  - DWD 详情 `/lakehouse/tables/70946dc2-d533-42b0-a94f-3af7e73d7b84` 显示质量分 100、质量规则 3、来源模型 1、SQL/dbt 预览、6 条字段映射、表级和字段级血缘。
  - 在 DWD 详情页点击 `运行` 触发新 model run `15a32214-e9bd-4fbc-b4f7-ec6f3b00861a`，Dagster run `20a13846-9fbd-4971-9938-b9ee302673ba` 成功，页面刷新后显示 `SUCCEEDED`、`MANUAL`、`写入 10`。
- 已验证浏览器 console：修复 `antd message` 静态 API 警告后，刷新 DWD 详情与打开 DWD 模型 tab 均为 0 error。
- 已验证事件尾链：前端触发的 run 发布第 3 条 `modeling.model.loaded`；`stream:modeling.model.loaded` 的 `catalog/quality` group pending/lag 均为 0；`quality.run_result` 为该 run 生成 `NOT_NULL` 与 `UNIQUE` 两条通过结果；`stream:quality.check.completed` 的 `catalog` group pending/lag 为 0。
- 仍未完成：拖拽建模画布、专门的 DWD 模型详情独立路由、复杂失败诊断视图、质量豁免审批入口；这些不阻塞当前 ODS->DWD 可观测闭环。

## 11. 迭代 7：增量、Backfill 与 Schema 变更

### 目标

让闭环具备生产可持续运行能力。

### 增量

- DWD 模型支持：
  - append-only
  - merge/upsert
  - 按 `updated_at` 或 CDC 元数据过滤
- dbt incremental 使用：
  - `materialized='incremental'`
  - `unique_key`
  - `is_incremental()`

### Backfill

- 新增模型重跑：
  - 全量重建
  - 按分区/日期范围重跑
  - 指定 ODS sync run 重跑
- Backfill run 与普通 run 共用 `model_run`，但 `trigger_type=BACKFILL`。

### Schema 变更

- ODS 新字段：
  - 提示可加入 DWD 模型
  - 默认不自动透传敏感字段
- ODS 字段删除/类型变化：
  - 标记受影响 DWD 模型
  - 阻断自动运行，要求确认或修复映射

### 验收

- 新增源端数据后，只处理增量。
- 对一个日期范围能重跑 DWD。
- ODS schema drift 后，DWD 模型状态变为 `NEEDS_REVIEW`。

### 不做

- 不做复杂数据修复工单。
- 不做全企业级变更审批矩阵。

### 实施状态（2026-06-22）

- 已扩展 DWD run 请求契约：`DwdModelRunRequest` 支持 `fullRefresh`、`partitionStart`、`partitionEnd` 与 `sourceIntegrationRunId`；`trigger_type=BACKFILL` 会写入 `model_run.queue_reason`，便于区分普通运行和回灌运行。
- 已增强 Dagster/dbt run config：`run_dwd_model` 支持 `backfill` 配置；`fullRefresh=true` 时向 dbt build 追加 `--full-refresh`，日期范围与指定 ODS sync run 会通过 `--vars` 传入 dbt。
- 已增强 dbt 增量编译：当 DWD 模型声明 `materialization=INCREMENTAL` 时，生成 `materialized='incremental'`，带 `unique_key` 时生成 merge 策略，并在 SQL 中写入 `is_incremental()` 水位过滤。
- 已新增 schema drift 处理链：
  - `SourceSchemaSnapshotServiceImpl` 发布 `integration.schema.drift` 时补充 `tenantId` 与受影响 `targetTables`。
  - `DwdSchemaDriftEventHandler` 订阅该事件，将受影响且非草稿的 DWD 模型标记为 `NEEDS_REVIEW`，从而阻断后续自动/手动运行。
- 已完成真实回灌验收：
  - BACKFILL run `5924c511-ea08-413e-8a6b-0ecec00a6243` 成功，Dagster run `1857887f-2a18-4216-babd-dcf6e8f9bc3e`，`rows_written=10`，`queue_reason=BACKFILL fullRefresh=true range=2026-06-01..2026-06-07`。
  - 成功事件被 Catalog/Quality 消费后，DWD 资产 `dwd.dwd_trade_codex_orders_df` 恢复 `row_count=10`、`quality_score=100`；`stream:modeling.model.loaded` 与 `stream:quality.check.completed` pending/lag 均为 0。
  - 验证中发现 Dagster user-code 镜像未重建会导致旧 config schema 拒绝 `backfill` 字段；已通过重建 `dagster-user-code` 修复，并关闭本轮调试产生的两条旧失败告警。
- 已完成 schema drift 验收：
  - 人工发布 `integration.schema.drift` 事件后，`stream:integration.schema.drift` 的 `modeling` group pending/lag 为 0。
  - 模型 `346b0f13-712b-41a2-8749-a25f96c19924` 从 `VALIDATED` 自动降级为 `NEEDS_REVIEW`。
  - 对 `NEEDS_REVIEW` 模型调用运行 API 返回 `40055 / DWD 模型必须先完成 compile/validate 后才能运行`。
  - 重新调用 compile 后模型恢复 `VALIDATED`。
- 仍未完成：日期范围变量已经传入 dbt，但通用生成 SQL 还没有按 `backfill_start/backfill_end` 做范围裁剪；源端 schema snapshot 的定时/自动采样仍需接入真实调度；前端尚无 Backfill 表单和 drift 复核工作台；复杂修复工单与审批矩阵仍按本轮“不做”处理。

## 12. 迭代 8：生产运维与 Iceberg 维护

### 目标

解决 DWD 长期运行后的性能、存储和可维护性。

### 能力

- DWD 表小文件检测。
- Iceberg compaction 任务：
  - rewrite data files
  - expire snapshots
  - remove orphan files
- DWD 新鲜度 SLA：
  - ODS < 5min
  - DWD < 1h
- 运行失败重试策略：
  - 自动重试 N 次
  - 人工重跑
  - 失败告警

### 验收

- 湖仓优化中心能看到 DWD 小文件风险。
- compaction 运行后文件数下降。
- DWD 超过新鲜度 SLA 时产生告警。

### 不做

- 不把 compaction 放进第一批 DWD MVP。

### 实施状态（2026-06-22）

- 已新增 DWD 运维评估后端：
  - `GET /api/v1/catalog/assets/maintenance` 返回当前租户 DWD 资产的运维评估。
  - `GET /api/v1/catalog/assets/{id}/maintenance` 返回单资产小文件、总大小、新鲜度 SLA、风险与建议动作。
  - `POST /api/v1/catalog/assets/{id}/maintenance` 提交 Iceberg 维护动作，当前支持 `OPTIMIZE`、`EXPIRE_SNAPSHOTS`、`REMOVE_ORPHAN_FILES`。
- 已接入 Trino/Iceberg 真实维护能力：
  - 通过 Iceberg `$files` 元数据表统计 `fileCount`、`smallFileCount`、`totalBytes`。
  - `OPTIMIZE` 实际执行 `ALTER TABLE iceberg."schema"."table" EXECUTE optimize`；样例 DWD 表已通过 API 和浏览器触发成功。
  - DWD 新鲜度 SLA 默认 60 分钟，超时标记 `FRESHNESS_SLA_BREACHED`，小文件风险按阈值和文件数判断。
- 已增强前端：
  - `存储优化中心` 从 mock 数据切换到真实 `CatalogAPI.listMaintenance()`，展示待优化表、小文件数、DWD 数据量、SLA 违约数，以及每张 DWD 表的状态、文件数、大小、风险和操作。
  - 表详情 `优化` tab 展示当前资产的真实运维状态，并提供 `Compaction`、`清理快照`、`清理孤儿文件` 操作。
  - 表详情顶栏 `立即优化` 已从禁用占位改为真实 `OPTIMIZE` 入口。
- 已修复质量门禁汇总污染：
  - `DwdModelQualityEventHandler` 现在每次 DWD loaded/failed 都记录 `DBT_BUILD` 基础结果；成功运行不会只写 dbt tests 而遗漏 build 本身。
  - Catalog 详情质量汇总按 `(rule_type, target_column)` 去重并用最新结果，避免历史调试失败的重复 `DBT_BUILD` 规则继续把质量分显示为 0。
- 已完成真实验收：
  - `GET /catalog/assets/70946dc2-d533-42b0-a94f-3af7e73d7b84/maintenance` 返回 `status=OK`、`freshnessStatus=OK`、`fileCount=1`、`smallFileCount=1`、`totalBytes=1246`、`freshnessSlaMinutes=60`。
  - 浏览器打开 `/lakehouse/optimize`，可见 `dwd.dwd_trade_codex_orders_df`、`OK`、小文件统计与 `Compaction` 操作，console error 为 0。
  - 新 DWD run `e8783676-9d5e-47b3-a816-a13f2166c0b9` 成功后，`modeling.model.loaded` 的 `catalog/quality` consumer group pending/lag 均为 0；表详情质量分恢复为 100、失败规则 0、`DBT_BUILD/NOT_NULL/UNIQUE` 均通过。
- 仍未完成：维护任务尚未进入独立运行实例表，当前 API 为同步提交 Trino 维护 SQL；小文件风险尚未按 Iceberg snapshot 历史趋势分析；失败自动重试策略仍只在 DWD run 记录中保留 `retryCount`，还没有调度级自动重试；运维告警还未进入统一监控告警中心。

## 13. 推荐落地顺序

第一批建议只做到迭代 5，形成真正可演示的主链路：

```text
迭代 0 -> 迭代 1 -> 迭代 2 -> 迭代 2.5 -> 迭代 3 -> 迭代 4 -> 迭代 5
```

到此为止，应该能完成：

```text
源端 codex_orders
  -> Airbyte 入 ODS
  -> integration.table.loaded
  -> 自动触发 dwd_trade_order_df dbt build
  -> 质量门禁
  -> Catalog DWD 资产
  -> ODS 到 DWD 表级/字段级血缘
  -> 前端可查看运行结果
```

迭代 6 是产品可用性增强，迭代 7/8 是生产化增强。不要在第一批就做可视化建模、多表复杂 Join、全量 Backfill、完整审批和 Iceberg 运维，否则主链路会被横向能力拖散。

评审后建议把第一批内部再拆成两个发布点：

| 发布点 | 包含迭代 | 验收目标 |
| --- | --- | --- |
| MVP-A 手动闭环 | 迭代 0-3，含 2.5 | 用户能从 ODS 创建 DWD 模型，模型带加工治理图和资源画像，手动运行 dbt/Dagster，Trino 查到 DWD 表，model_run 有状态 |
| MVP-B 自动治理闭环 | 迭代 4-5 | ODS 入湖成功自动触发 DWD，DWD 成败进入质量/Catalog/血缘，前端至少能看到资产与运行状态 |

不要在 MVP-A 前就要求自动触发，也不要在 MVP-B 前声称完成标准 ODS->DWD 闭环。

## 14. 首批实施任务清单

1. 建立样例 ODS/DWD 基线数据。
2. 新增 DWD 模型实体和迁移。
3. 新增 DWD 模型 API 与校验服务。
4. 前端 ODS 表详情增加派生入口。
5. DWD 向导保存模型草稿。
6. 定义 DWD operator graph 与 resource profile 契约。
7. 生成默认加工治理节点：input、transform/govern、mask/encrypt、quality gate、dbt model、output。
8. 生成 orchestration DAG 草稿，建立 DWD 模型与数据开发编排的一对一映射。
9. 将 `resource_group/compute_profile/engine` 写入模型、DAG 和运行记录。
10. dbt 产物生成与 `dbt parse` 验证。
11. Dagster 接入 dbt asset/job。
12. 控制面触发 DWD model run，并解析 dbt artifacts。
13. 新增 `modeling.model.loaded/failed` 事件。
14. 消费 `integration.table.loaded` 自动触发 DWD。
15. DWD 成功后更新 Catalog、血缘、质量结果。
16. 用浏览器、流水线画布和 Trino 完成真实端到端验证。

## 15. 关键验收命令

```bash
cd onelake-app
make up-core
make dagster-up
make airbyte-status
mvn -q -pl module-modeling,module-orchestration,module-catalog,module-quality -am test -Djacoco.skip=true
```

```bash
cd onelake-app/web-console
pnpm exec tsc --noEmit
pnpm build
```

```bash
cd onelake-app/dbt
dbt parse --profiles-dir .
dbt build --select dwd_trade_order_df --profiles-dir .
```

最终验收不以“代码编译通过”为准，而以 Trino 查询、model_run 状态、Catalog 资产、血缘、质量门禁和前端页面可见性共同确认。

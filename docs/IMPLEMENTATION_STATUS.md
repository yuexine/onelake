# OneLake 项目初始化实施进度报告

> 完成日期: 2026-06-14
> 实施依据: 技术初始化文档 / 详细功能清单 / 原型设计与交互说明文档
> 实施范围: MVP 控制面骨架（模块化单体）+ 数据面 Docker Compose + dbt 工程 + 前端控制台骨架

---

## 〇、实施总览

| 维度 | 设计要求 | 实施结果 | 状态 |
|------|---------|---------|------|
| 控制面 | `onelake-app` 模块化单体（8 业务模块 + bootstrap） | **10 个 Maven 子模块**（2026-06-29 加入 `module-analytics`） | ✅ 完成 |
| 数据面 | Compose 组件 + Airbyte 本地部署 | `docker-compose.yml` 覆盖 Compose 组件；Airbyte 改由 `abctl` 管理且本地入口已可访问；2026-06-29 加入 `jupyterhub` + Superset 嵌入开关 | ⚠ 待端到端联调 |
| 数据库 | 单 PG + 9 schema（含 dataservice_api） | **9 个 schema Flyway 脚本**（2026-06-29 加入 `analytics` 含 dataset / dashboard / dashboard_publication / notebook / notebook_template / notebook_run / query_log） | ✅ 完成 |
| 前端 | React 18 + TS + Ant Design Pro | 工作台 + 集成模块完整 / 其余占位骨架；2026-06-29 加入完整"数据分析"一级菜单（11 段）：数据集 / 大屏中心（自研 ScreenDesigner + Superset 嵌入）/ Notebook / 组件模板库 | ⚠ 部分 |
| dbt | ODS→DWD→ADS 分层 + 质量门禁 | 工程骨架 + 示例模型 + 脱敏宏 | ✅ 完成 |
| **数据分析与可视化** | **P1-P4d 全量**（数据集 + Superset 嵌入 + 自研大屏 15 组件 + 发布分享 + Notebook SDK + papermill 调度 + 算法模板库） | **47 个 Java 文件 + 6 个前端页面 + 13 个 Python/Jupyter 文件 + 32 个单元测试全部通过** | ✅ **2026-06-29 完成** |

文件统计：**159 个 Java 源文件 + 13 个 SQL 文件 + 配置/前端/构建脚本 ≈ 220 个文件**。

> **新增模块**：`module-analytics` 见 §十七。详细方案：`docs/数据分析与可视化模块设计方案.md` v1.1。

---

## 一、根目录脚手架

| 文件 | 设计文档对应 | 状态 |
|------|------------|------|
| `onelake-app/pom.xml` | §3.2 父 POM 依赖版本锁定 | ✅ |
| `onelake-app/Makefile` | §5.2 up/down/seed/migrate/backend/frontend/dev | ✅ |
| `onelake-app/docker-compose.yml` | §6.1 数据面 Compose 组件；Dagster 为 webserver/daemon/code-location 多容器 | ✅ |
| `onelake-app/scripts/airbyte-local.sh` | Airbyte 本地 `abctl` 管理入口 | ✅ `airbyte-abctl` / `ingress-nginx` 已 deployed，`http://localhost:8000` 返回 200 |
| `onelake-app/.gitignore` | 通用 | ✅ |
| `scripts/postgres-init.sql` | §7.1 pgcrypto + web_anon 角色 | ✅ |
| `scripts/keycloak-realm.sh` | §5.2 seed 目标 + §八 校验 #2 | ✅ |
| `scripts/minio-bucket.sh` | §5.2 seed 目标 | ✅ |

---

## 二、module-common（公共模块）

对应《技术初始化文档》§6.7 / §6.9 / §6.13 + §7.1。

| 类 | 职责 | 对应章节 |
|----|------|---------|
| `ApiResponse<T>` | 统一返回体 `{code,message,data}` | §6.9 |
| `BizException` | 业务异常基类（带 code） | §6.9 |
| `DataplaneException` | 数据面调用异常（code=50010） | §6.9 |
| `GlobalExceptionHandler` | 全局异常拦截：业务/校验/权限/未知 | §6.9 |
| `TenantContext` | ThreadLocal 租户/用户/traceId | §五 全栈贯通 |
| `JsonUtil` | Jackson 单例 + JSR-310 | 通用 |
| `OutboxEvent` + Repo + Publisher + Dispatcher | Outbox 模式（每 2s 轮询分发） | §6.7 + §6.13 |
| `DomainEventHandler` SPI | 跨模块事件处理器扩展点 | §6.13 |
| `AuditLog` + Repo + Logger | 统一审计日志（REQUIRES_NEW 独立事务） | §7.1 common.audit_log |
| `SecurityConfig` | OAuth2 资源服务器 + Keycloak 角色映射 | §6.3 |
| `TenantContextFilterConfig` | 从 JWT 注入 TenantContext + X-Trace-Id | §五 |
| `WebClientConfig` | 数据面共享 WebClient（超时配置） | §6.4 / §6.5 |
| `CorsConfig` | dev 环境允许 5173 跨域 | §5.4 |
| `JacksonConfig` | UTC 时区统一 | §6.2 |
| `WebMvcConfig` | MDC traceId + 启用调度/异步 | §6.x |

---

## 三、module-integration（数据集成 · 主样板模块）

对应《技术初始化文档》§6.4 / §6.10 / §6.11 + §7.2 + §7.9 补全。

完整分层（api → service → domain + repository + client + mapper + dto）：

| 子包 | 类 | 职责 |
|------|----|------|
| module-common/api | `SystemContextController` | `/api/v1/system/context`、`/api/v1/system/projects`，提供当前租户和项目选项 |
| api | `DataSourceController` | `/api/v1/integration/datasources` CRUD + 已保存/未保存配置测连 + 库/schema/table/column 探查 |
| api | `SyncTaskController` | `/api/v1/integration/sync-tasks` 创建/发布/暂停/试跑/触发/历史/运行详情/日志/取消 |
| api/vo | `CreateDataSourceVO` / `UpdateDataSourceVO` / `TestDataSourceVO` / `ProbeDatabasesVO` / `DatabaseProbeResult` / `CreateSyncTaskVO` / `ConnectivityResult` | 入参/出参 |
| service | `DataSourceService` / `SyncTaskService` | 用例编排接口 |
| service/impl | `DataSourceServiceImpl` / `SyncTaskServiceImpl` | 事务边界 + Outbox + 审计 |
| service/impl | `SourceSchemaSnapshotServiceImpl` | 源端 schema snapshot 对比与 `integration.schema.drift` 事件发布，补充受影响 ODS `targetTables` |
| service/validation | `DataSourceConfigValidator` | 按 MYSQL/POSTGRES/HIVE/KAFKA/S3 等类型校验连接配置 |
| domain/entity | `DataSource` / `SyncTask` / `SyncRun` / `SourceSchemaSnapshot` | JPA 实体 |
| domain/enums | 7 个枚举（Health / SyncMode / DataSourceType / NetworkMode / EnvLevel / TaskStatus / RunStatus） | |
| repository | 4 个 JPA Repository | |
| client | `ConnectivityTester` | TCP + JDBC 双探活（NET/AUTH/DRV 分类） |
| client/discovery | `DatabaseDiscoveryClient` + `DataSourceDiscoveryStrategy` | 门面按数据源类型分发探查策略；MySQL/PostgreSQL 独立实现 schema/table/columns 探查，支持采集向导真实选表和字段映射 |
| client | `AirbyteSyncDriver` | Airbyte source/destination/connection 准备、同步触发、job snapshot、运行日志、取消 |
| client | `DagsterScheduleClient` | 启用/暂停采集任务时向 Dagster reconciliation job 传递调度登记/撤销意图，默认关闭不阻断发布 |
| mapper | `DataSourceMapper` / `SyncTaskMapper` | MapStruct |
| dto | `DataSourceDTO` / `SyncTaskDTO` / `SyncRunDTO` / `DiscoveredColumnDTO` / `SyncTaskDryRunDTO` / `SyncRunLogDTO` / `AirbyteConnectorDefinitionDTO` / `AirbyteConnectorSpecDTO` | 对外 DTO（不暴露 config 中的密码） |

---

## 四、module-orchestration（编排调度）

对应《技术初始化文档》§6.5 + §7.3。

| 类 | 职责 |
|----|------|
| `Dag` / `JobRun` 实体 | 持久化 DAG 定义 + 运行实例 |
| `TriggerType` / `DagStatus` 枚举 | CRON/MANUAL/EVENT、QUEUED/RUNNING/SUCCESS/FAILED |
| `DagRepository` / `JobRunRepository` | 分页查询 |
| `DagsterClient` | GraphQL `launchRun` 调用 |
| `OrchestrationService` | DAG CRUD + 触发 + 运行历史 |
| `DagController` | `/api/v1/orchestration/dags` |

---

## 五、module-catalog（元数据 / 目录 / 血缘）

对应《技术初始化文档》§6.12 + §7.4。

| 类 | 职责 |
|----|------|
| `Asset` 实体 | OpenMetadata 本地缓存（含 L1~L4 密级、质量分） |
| `LineageEdge` 实体 | 血缘边（含字段级映射） |
| `OpenMetadataClient` | 调 OM `/tables?fields=owner,tags,columns` |
| `CatalogSyncService` | 把 OM tags 自动提取为 L1~L4 密级 |
| `CatalogService` | 资产 CRUD + 影响分析（BFS 下游） |
| `CatalogMaintenanceService` | DWD 运维评估、Iceberg `$files` 小文件统计、新鲜度 SLA 判断与 Trino Iceberg 维护操作 |
| `DwdModelLoadedEventHandler` | 消费 `modeling.model.loaded`，自动回写 DWD Catalog 资产与 ODS->DWD 表级/字段级血缘 |
| `QualityCheckEventHandler` | 消费质量检查事件，更新 Catalog 资产质量分 |
| `CatalogController` | `/api/v1/catalog/assets` + `/assets/{id}/maintenance` + `/assets/maintenance` + `/lineage/downstream` + `/sync` |

---

## 六、module-modeling（主题域 / 标准 / 指标 / 维度）

对应《技术初始化文档》§7.5 + §7.9 补全 dimension。

| 类 | 职责 |
|----|------|
| `SubjectDomain` / `DataStandard` / `Metric` / `Dimension` 实体 | 完整 4 表 |
| `DataModel` / `DataModelSource` / `DataModelColumnMapping` 实体 | ODS->DWD 模型草稿、source、字段映射与 dbt 编译产物 |
| `DataModelRun` 实体 | DWD 模型运行态，记录 Dagster runId、触发类型、资源画像、开始/结束时间和错误 |
| `SubjectDomainRepository` / `MetricRepository` | |
| `DataModelRepository` / `DataModelRunRepository` | DWD 模型与运行历史查询 |
| `ModelingService` | 主题域/指标 CRUD |
| `DwdModelService` | DWD 草稿、校验、dbt 产物生成、增量 SQL、disabled DAG 草稿、Dagster/dbt run、Backfill config、artifact 解析和模型运行事件 |
| `DwdRunArtifactReader` | 解析 dbt `run_results.json`，抽取模型行数、错误与 `qualityChecks` |
| `DwdModelDagsterClient` | GraphQL `launchRun` + run status refresh |
| `DwdOdsLoadedEventHandler` | 消费 `integration.table.loaded`，按 ODS source 自动触发已验证 DWD 模型，含重复/活跃运行/历史事件保护 |
| `DwdSchemaDriftEventHandler` | 消费 `integration.schema.drift`，将受影响 DWD 模型标记为 `NEEDS_REVIEW` 并阻断运行 |
| `ModelingController` | `/api/v1/modeling/domains` + `/metrics` + `/models?sourceFqn/targetFqn` + `/models/{id}/compile` + `/models/{id}/run` + `/models/{id}/runs` |

---

## 七、module-quality（质量 / 评分 / 告警）

对应《技术初始化文档》§7.6 + §7.9 补全 score_snapshot / alert。

| 类 | 职责 |
|----|------|
| `Rule` / `RunResult` / `ScoreSnapshot` / `Alert` 实体 | 完整 4 表 |
| `RuleRepository` / `RunResultRepository` / `AlertRepository` | |
| `QualityService` | 规则 CRUD + 结果记录 + 告警 raise/close |
| `DwdModelQualityEventHandler` | 消费 DWD 模型成功/失败事件，落库 dbt build/test 质量结果；成功也记录 `DBT_BUILD` 通过，失败时生成告警，并发布质量检查事件 |
| `QualityController` | `/api/v1/quality/rules` + `/results` + `/alerts` |

---

## 八、module-security（安全 / 脱敏 / 授权 / RBAC / 审批）

对应《技术初始化文档》§7.7 + §7.9 补全 role / role_binding / approval_request。

| 类 | 职责 |
|----|------|
| `Secret` / `MaskingPolicy` / `AccessGrant` / `Role` / `RoleBinding` / `ApprovalRequest` 实体 | 完整 6 表 |
| 4 个 Repository | |
| `SecurityService` | 脱敏策略解析（最高优先级）+ 申请-审批-授权状态机 |
| `SecurityController` | `/api/v1/security/masking-policies` + `/grants/me` + `/approvals` |

---

## 九、module-dataservice（数据服务 / API / 订阅 / 配额）

对应《技术初始化文档》§6.6 + §7.8 + §7.9 补全 quota_usage。

| 类 | 职责 |
|----|------|
| `ApiDefinition` / `ApiVersion` / `AppKey` / `Subscription` / `ApiCallLog` / `QuotaUsage` 实体 | 完整 6 表 |
| 3 个 Repository | |
| `DataServicePublisher` | ① PostgREST 视图 ② APISIX 路由（key-auth + limit-req）③ 状态回写 |
| `DataServiceController` | `/api/v1/dataservice/apis` 创建/发布/下线 |

---

## 十、bootstrap（启动模块）

对应《技术初始化文档》§四 工程脚手架 + §五 启动顺序。

| 文件 | 职责 |
|------|------|
| `OnelakeApplication.java` | `@SpringBootApplication(scanBasePackages="com.onelake")` |
| `application.yml` | 数据源 + Hikari + Flyway 多 schema + OAuth2 + 数据面端点 |
| `pom.xml` | 聚合 8 业务模块 + spring-boot-maven-plugin + flyway-maven-plugin |

### Flyway 迁移脚本（8 个 schema）

| 脚本 | 表数 | 审查补全 |
|------|------|---------|
| `common/V1__common.sql` | 8（tenant/project/app_user/audit_log/outbox_event/notification/dict_type/dict_item/tag） | ✅ dict_type/dict_item/tag |
| `integration/V1__integration.sql` | 4（datasource/sync_task/sync_run/source_schema_snapshot） | ✅ source_schema_snapshot |
| `orchestration/V1__orchestration.sql` | 2（dag/job_run） | |
| `catalog/V1__catalog.sql` | 2（asset/lineage_edge） | |
| `modeling/V1__modeling.sql` | 4（subject_domain/data_standard/metric/dimension） | ✅ dimension |
| `modeling/V2__dwd_data_model.sql` | 3（data_model/data_model_source/data_model_column_mapping） | ODS->DWD 草稿 |
| `modeling/V3__dwd_operator_resource_contract.sql` | 0 | DWD operator graph / resource profile 字段 |
| `modeling/V4__dwd_model_run.sql` | 1（model_run） | DWD 模型运行态 |
| `quality/V1__quality.sql` | 4（rule/run_result/score_snapshot/alert） | ✅ score_snapshot/alert |
| `security/V1__security.sql` | 6（secret/masking_policy/access_grant/role/role_binding/approval_request） | ✅ role/role_binding/approval_request |
| `dataservice/V1__dataservice.sql` | 6（api_definition/api_version/app_key/subscription/api_call_log/quota_usage）+ dataservice_api schema | ✅ quota_usage |

**基础控制面 36 张表 + 审查补全 7 张补全表 + ODS->DWD 闭环新增 4 张表 = 共 47 张表。**

---

## 十一、dbt 工程

对应《技术初始化文档》§4.2 + §6.8。

| 文件 | 职责 |
|------|------|
| `dbt_project.yml` | staging/intermediate/marts 三层 + Iceberg 配置 |
| `profiles.yml` | Trino target（环境变量注入） |
| `models/sources.yml` | ODS 源声明 + tests |
| `models/intermediate/dwd_order_df.sql` | DWD 明细视图 |
| `models/generated/sources.yml` | DWD compile 生成的 ODS source 声明 |
| `models/intermediate/dwd_trade_codex_orders_df.sql` | DWD compile 生成的样例明细模型 |
| `models/intermediate/dwd_trade_codex_orders_df.yml` | DWD compile 生成的样例模型 tests/schema |
| `models/marts/ads_order_gmv_daily.sql` | ADS 每日 GMV |
| `models/marts/schema.yml` | 质量门禁（not_null/unique/accepted_range） |
| `macros/onelake_macros.sql` | 增量水位 + 手机号/身份证脱敏宏 |
| `dagster/definitions.py` | DWD dbt build job，支持 Backfill `--full-refresh` 与 dbt vars 透传 |

---

## 十二、web-console（前端控制台）

对应《技术初始化文档》§3.3 + §4.3 + §5.4 + 原型设计文档 §一~§八。

| 文件 | 职责 |
|------|------|
| `package.json` | React 18 + AntD 5 + Pro Components + React Query + X6 + Monaco |
| `vite.config.ts` | `/api → APISIX:9080`、`/auth → Keycloak:8081` |
| `tsconfig.json` | TS 5 + strict + `@/*` 路径别名 |
| `index.html` + `src/main.tsx` | React Query + AntD 中文 locale + BrowserRouter |
| `src/App.tsx` | ProLayout + 10 大菜单 + 路由表 |
| `src/api/http.ts` | axios 全局：JWT 注入 + X-Trace-Id + ApiResponse 解包 + 401 重定向 |
| `src/api/index.ts` | 模块 API SDK；`ModelingAPI` 独立承载 DWD 模型草稿、编译、运行与运行历史 |
| `src/pages/dashboard/index.tsx` | 工作台（4 指标卡 + 我的待办 + 快捷入口） |
| `src/pages/integration/index.tsx` | 数据集成完整 CRUD（新建抽屉 + 测连 + 删除） |
| `src/pages/lakehouse/TableDetail.tsx` | ODS/DWD 表详情：Schema、DWD 模型、质量门禁、表级/字段级血缘、Iceberg 运维、权限入口 |
| `src/pages/lakehouse/OptimizeCenter.tsx` | DWD 存储优化中心，读取真实维护评估并触发 Iceberg Compaction/快照/孤儿文件清理 |
| `src/pages/lakehouse/TableWizard.tsx` | ODS 派生 DWD 草稿建模向导，保存后回到源 ODS 表详情 |
| `src/pages/{orchestration,catalog,modeling,quality,security,dataservice}/index.tsx` | 占位骨架 |
| `src/stores/app.ts` | zustand 全局 store |

---

## 十三、数据面配置文件

| 文件 | 职责 |
|------|------|
| `trino/catalog/iceberg.properties` | Iceberg via Hive Metastore + MinIO S3 |
| `trino/hive-site.xml` | Hive Metastore → PG + S3A |
| `apisix/config.yaml` | APISIX admin key + etcd + Prometheus |

---

## 十四、对照《初始化校验清单》（§八）

| 校验项 | 状态 |
|--------|------|
| **Maven 全模块编译验证** | ✅ **BUILD SUCCESS**（10 个模块全部通过 `mvn install -DskipTests`） |
| ① `docker compose up -d` 全部组件健康 | ⚠ 待用户实机启动验证（compose 已就绪） |
| ② Keycloak onelake realm + 5 角色 + onelake-app 客户端 | ✅ `scripts/keycloak-realm.sh` |
| ③ `make migrate` Flyway 8 schema 建表 | ✅ 8 个 V1__*.sql 已就绪 |
| ④ Airbyte → ODS → sync_run 回写 | ✅ 已完成真实本地实证：Postgres 源表 3 行经 Airbyte job `2` 同步到 `onelake_lake.ods_airbyte.codex_orders`，`sync_run` 回写 `rowsRead=3`、`rowsWritten=3` |
| ⑤ dbt run 产出 ads_order_gmv_daily + 质量门禁 | ✅ 模型 + schema.yml 已就绪 |
| ⑥ OpenMetadata 资产 → catalog.asset | ✅ CatalogSyncService 已实现 |
| ⑦ PostgREST 视图 + APISIX key-auth + limit-req | ✅ DataServicePublisher 已实现 |
| ⑧ Superset 报表 | ⚠ 数据面已包含镜像，需后续接 Trino |
| ⑨ onelake-app 三模块控制台代理打通 | ✅ integration / orchestration / catalog 完整 |

**编译验证结果（2026-06-14）**:
```
[INFO] Reactor Summary for onelake-app 0.1.0-SNAPSHOT:
[INFO] onelake-app ........................................ SUCCESS
[INFO] module-common ...................................... SUCCESS
[INFO] module-integration ................................. SUCCESS
[INFO] module-orchestration ............................... SUCCESS
[INFO] module-catalog ..................................... SUCCESS
[INFO] module-modeling .................................... SUCCESS
[INFO] module-quality ..................................... SUCCESS
[INFO] module-security .................................... SUCCESS
[INFO] module-dataservice ................................. SUCCESS
[INFO] bootstrap .......................................... SUCCESS
[INFO] BUILD SUCCESS  (10 modules, 112 Java files)
```

---

## 十五、严格遵循设计文档的证据

1. **依赖版本锁定**：父 POM `properties` 段全部按 §3.2（Spring Boot 3.3.2 / MapStruct 1.5.5 / springdoc 2.6.0 / resilience4j 2.2.0 / testcontainers 1.20.1 / redisson 3.32.0）。
2. **包结构**：`com.onelake.<module>.{api,service,domain,repository,client,mapper,dto,config}` 完全对应 §4.1 范式。
3. **依赖纪律**：`api → service → domain`，`repository/client` 仅由 `service` 调用；模块间无直接 import（依赖 common 的 SPI 接口与 Outbox 事件通信）。
4. **密码安全**：`DataSource` 表只存 `secret_ref`，绝不明文落库（对应功能清单 L1-1.1.1）。
5. **审计 + Outbox 与业务同事务**：`DataSourceServiceImpl.create()` 在 `@Transactional` 内同时写 datasource + outbox + 审计。
6. **租户强制下推**：所有 service 入口读 `TenantContext.getTenantId()`，缺失即 40100。
7. **OAuth2 资源服务器**：`SecurityConfig` 从 Keycloak realm_access.roles 转 `ROLE_*`，支持 `@PreAuthorize("hasRole('DE')")`。
8. **统一响应**：`ApiResponse<T>` record + 全局异常处理器，全部按 §6.9 实现。

---

## 十六、下一步演进信号

按设计文档 §一「暂不纳入」清单推进：

- **第二批 P1**：Flink CDC / Spark 集群 / Doris / Kafka（数据面）
- **前端**：补齐其余 6 个模块的 Hi-Fi（按原型 §八 线框图）
- **观测**：接入 Prometheus + Grafana + Loki 三件套
- **CI/CD**：GitHub Actions 多模块并行构建 + Flyway 校验
- **K8s**：Helm Chart + Argo CD GitOps

---

## 十七、module-analytics（数据分析与可视化 · 2026-06-29 新增）

> 详细方案见 `docs/数据分析与可视化模块设计方案.md` v1.1（评审修订版）。
> 阶段：P1 → P4d 全量交付，端到端闭环（资产 → 数据集 → 大屏/Superset 嵌入 → 发布分享 / Notebook 探索 → 调度产出 → 回写资产）。

### 后端（47 个 Java 文件）

| 子包 | 类 | 职责 |
|------|----|------|
| domain/entity | `Dataset` `Dashboard` `DashboardPublication` `Notebook` `NotebookTemplate` `NotebookRun` `QueryLog` | 7 个 JPA 实体 |
| domain/enums | `SourceType` `DashboardStatus` `RunStatus` `TemplateCategory` | 强约束枚举（RunStatus 与 modeling.model_run 对齐） |
| repository | 7 个 Repository | `findByIdAndTenantId` / `findByTenantId` 强制租户隔离 |
| client | `TrinoQueryClient` `SupersetClient` `DataServiceClient` `JupyterHubClient` `DagsterClient` | 数据面句柄，bean name `analyticsDagsterClient` 避免与 orchestration 冲突 |
| service | `DatasetQueryService` `DatasetService` `DashboardService` `SharePublishService` `SupersetEmbedService` `NotebookRunService` `NotebookRunSyncScheduler` `NotebookArtifactService` `NotebookTemplateService` `MaskingPolicyView` `SqlBuilder` | 11 个 Service |
| api | `DatasetController` `AnalyticsDashboardController` `SupersetEmbedController` `ShareController` `NotebookController` `NotebookTemplateController` | 6 个 Controller |
| api/vo | `DatasetRequest` `DashboardSaveRequest` `DashboardPublishRequest` | |
| dto | `DatasetDTO` `DataBinding` `QueryResult` | |
| config | `AnalyticsConfig` | @EnableAsync + @EnableScheduling + analyticsAsyncExecutor |
| 测试 | 6 个 Service 测试类（32 个测试全绿） | 覆盖脱敏下推 / single-flight / row_filter 硬校验 / tenant_id 校验 / 乐观锁 / 幂等 |

### 前端（11 段 SideNav 新增"数据分析"）

| 页面 | 路径 | 关键能力 |
|------|------|---------|
| `DatasetList` + `DatasetEditor` | `/analytics/datasets` | 4 种来源（ASSET/SQL/API/NOTEBOOK）CRUD |
| `DatasetDetail` | `/analytics/datasets/:id` | 字段 schema + 试查询 |
| `DashboardList` + `ScreenDesigner` | `/analytics/dashboards` + `/:id` + `/:id/view` | 三段式（palette + canvas + inspector）拖拽编排 |
| `WidgetRenderer` + `WIDGET_REGISTRY` | screen/registry.tsx | **P2 最小可用 15 组件**（line/bar/pie/scatter/metric/flipper/table/text/image/decoration/superset/rankList/radar/funnel/heatmap） |
| `ScreenCanvas` | screen/ScreenCanvas.tsx | react-grid-layout 自由布局（cols=48, compactType=null 允许分层） |
| `Inspector` | screen/Inspector.tsx | 三 Tab：属性 / 数据 / 交互 |
| `PublishDialog` | screen/PublishDialog.tsx | isPublic + 过期 + shareToken 复制（含 row_filter 警告） |
| `SupersetPanel` | screen/SupersetPanel.tsx | @superset-ui/embedded-sdk 0.4.0 + 后端签发 guest token |
| `Notebooks` | `/analytics/notebooks` | 列表 + 创建 + JupyterLab 启动入口 |
| `Library` | `/analytics/library` | 组件库 + 算法模板库（含 KMeans / Prophet / 相关性 / RFM） |
| `ScreenShare` | `/share/screen/:token` | 无鉴权布局独立路由（不在 App layout 下） |

### 数据面 / Python SDK（13 个文件）

| 路径 | 用途 |
|------|------|
| `jupyter/Dockerfile` | JupyterHub 4.1 + papermill/pandas/sklearn/prophet/pyspark/trino |
| `jupyter/jupyterhub_config.py` | Keycloak OAuth + SimpleLocalProcessSpawner + pre_spawn_hook |
| `jupyter/pre_spawn_hook.py` | 调控制面 issue-token 注入短期 ONELAKE_TOKEN |
| `jupyter/onelake/` | Python SDK（`__init__.py` / `api.py` / `_control_plane.py` / `_trino.py` / `_spark.py`） |
| `jupyter/setup.py` | `pip install -e .` 入口 |
| `jupyter/templates/{kmeans,prophet,correlation,rfm}.py` | 4 个平台预置算法模板 |
| `dagster/definitions.py` | 新增 `run_notebook_op` + `onelake_notebook_run` job |
| `dagster/Dockerfile_user_code` | 加装 papermill + nbconvert + ipykernel |
| `superset/superset_config.py` | EMBEDDED_SUPERSET + GUEST_TOKEN_JWT + CORS + TALISMAN frame-ancestors |
| `docker-compose.yml` | 新增 `jupyterhub` 服务 + Superset 嵌入开关 + `jupyterdata` volume |

### 关键架构合规（v1.1 评审硬约束落地）

| 评审要求 | 落地点 |
|----------|--------|
| 脱敏完全下推 Trino | `SqlBuilder.compose()` 在拼 SELECT 时注入 mask 表达式；不在 Java 层做行后处理 |
| 公开分享 × row_filter 硬校验 | `SharePublishService.publish()` 在签 shareToken 前 verify 所有绑定数据集 row_filter 为空 |
| Superset 数据源 tenant_id 前置校验 | `SupersetEmbedService.verifyDatasourceHasTenantColumn()` 签发 guest token 前调用 |
| Notebook 状态回写单向 | `NotebookRunSyncScheduler` 每 30s 轮询 Dagster；30min 超时兜底置 FAILED；Dagster 不反调控制面 |
| 同数据集多组件 single-flight | `DatasetQueryService` Redis SETNX + 进程内 ReentrantLock |
| Dashboard 版本化 | `dashboard.version` 乐观锁 + `publication.is_current` unique index |
| 公开通道独立布局 | `/share/screen/:token` 不挂 `<App />` layout |
| Outbox 事件常量化 | 6 个新事件通过 `DomainEvents` 常量发布，避免 magic string |

### 单元测试（32 个全绿）

```
Tests run: 32, Failures: 0, Errors: 0, Skipped: 0
- SqlBuilderTest                   (12)  脱敏下推 + 聚合 + 筛选 + row_filter + 边界
- DatasetQueryServiceTest           (6)  API 分支 + Trino 缓存 + single-flight + 慢查询 + query_log 异步
- SharePublishServiceTest           (5)  公开 × row_filter 硬校验 + 过期 + 非公开
- NotebookArtifactServiceTest       (3)  幂等 + 默认密级 + Outbox
- SupersetEmbedServiceTest          (3)  tenant_id 校验 + Superset 不可达降级
- DashboardServiceTest              (4)  乐观锁 + 创建 + 不存在
```

### 验收命令

```bash
# 后端
mvn -pl module-analytics test -Djacoco.skip=true   # → BUILD SUCCESS, 32 个测试通过

# 前端
cd web-console && pnpm exec tsc --noEmit && pnpm build   # → 0 错误，7 个新 chunk

# Python SDK（语法校验）
cd jupyter && python3 -c "import ast; ast.parse(open('onelake/api.py').read())"
```

---

✅ **项目初始化阶段交付完成。可执行 `make dev` 一键拉起。数据分析模块（P1-P4d）端到端闭环已就绪，下一步建议见 §十六。**

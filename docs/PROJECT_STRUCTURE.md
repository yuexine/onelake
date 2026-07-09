# OneLake 项目结构与代码索引

> 修订日期：2026-07-09
>
> 口径：本文件描述当前代码事实。若与代码不一致，以当前代码为准，并同步修订本文和 `docs/IMPLEMENTATION_STATUS.md`。

## 1. 总览

OneLake 当前是一个 Java/Spring Boot 模块化单体控制面，加一组开源数据面服务与 React 控制台。

| 区域 | 当前事实 |
| --- | --- |
| 后端 | `onelake-app` Maven reactor，Java 17，Spring Boot 3.3.2 |
| Maven 子模块 | 10 个：`module-common`、8 个业务模块、`module-analytics`、`bootstrap` |
| Java 源文件 | 当前约 406 个 `src/main/java` 文件、61 个测试类 |
| 数据库 | Spring Flyway 配置 10 个 schema；9 个迁移目录建表，当前约 73 张表 |
| 前端 | `web-console`，React 18 + Vite 5 + TypeScript + Ant Design 5 |
| 前端页面 | `src/pages` 当前 76 个 `.tsx`，路由覆盖 SSO、公开分享和 11 个顶层业务入口 |
| 数据面 | Docker Compose 管理核心服务；Airbyte 由 `abctl` 单独管理 |

## 2. 顶层目录

```text
onelake/
  AGENTS.md                  # Agent 入口，只指向 RTK.md
  RTK.md                     # 开发、运行、验证事实入口
  CLAUDE.md                  # 薄入口，避免重复事实
  docs/                      # 当前事实、验证、历史方案和模块附录
  onelake-app/
    pom.xml                  # Maven 父工程
    Makefile                 # 本地生命周期入口
    docker-compose.yml       # Compose 数据面，不含 Airbyte
    bootstrap/               # Spring Boot 启动模块、配置、Flyway
    module-common/           # 公共能力
    module-integration/      # 数据集成
    module-orchestration/    # 编排和 Spark pipeline
    module-catalog/          # 目录、血缘、SQL 工作台
    module-modeling/         # 建模、术语、码表
    module-quality/          # 质量规则、结果、告警
    module-security/         # 安全、授权、PII、审批
    module-dataservice/      # 数据服务/API
    module-analytics/        # 数据分析、大屏、Notebook
    dbt/                     # dbt 样例与生成产物
    dagster/                 # Dagster user-code / webserver / daemon 配置
    trino/                   # Trino + Hive Metastore + Iceberg 配置
    apisix/                  # APISIX 配置
    scripts/                 # Airbyte/Flyway/Keycloak/MinIO/样例数据脚本
    web-console/             # React 控制台
```

## 3. 后端模块索引

| 模块 | 代表性包 | 当前能力 |
| --- | --- | --- |
| `module-common` | `api`、`audit`、`outbox`、`notification`、`system`、`task`、`security` | 统一响应/异常、租户上下文、OAuth2、审计、通知、全局任务、Outbox -> Redis Stream、消费幂等 |
| `module-integration` | `api`、`client`、`client/discovery`、`service`、`mapper` | 数据源 CRUD/测连/探测、MySQL/Postgres discovery、Airbyte source/destination/connection/run/log/cancel、采集任务生命周期、CDC、文件源、schema drift、监控 |
| `module-orchestration` | `api`、`service`、`service/spi`、`event`、`config` | DAG/Run、Spark-only pipeline、任务/边/运行态、算子市场、资源组、运行契约、回填、`pipeline.*` 事件 |
| `module-catalog` | `api`、`api/sql`、`service/sql`、`event` | 资产、字段 schema、血缘、影响分析、schema change、SQL Workbench、查询模板/历史、Iceberg 维护、事件消费 |
| `module-modeling` | `api`、`service`、`event` | 主题域、指标、DWD 模型定义/校验/编译/发布、业务术语表、绑定/版本/影响、码表 |
| `module-quality` | `api`、`service`、`event` | 质量规则、结果、评分/告警、DWD/模型质量事件处理、`quality.check.*` 事件 |
| `module-security` | `api`、`service`、`event` | 密钥、脱敏策略、授权、审批、ACL、PII 扫描、采集任务事件响应 |
| `module-dataservice` | `api`、`service`、`backfill`、`event` | API 定义/版本、草稿/发布/下线、SQL API 调试、AppKey、订阅、配额、调用日志、PostgREST/APISIX 集成、资产消费投影 |
| `module-analytics` | `api`、`client`、`llm`、`service` | 数据集、查询、仪表盘/发布/分享、Superset guest token、Notebook/模板/运行/产物、NL2SQL、慢查询事件 |
| `bootstrap` | `OnelakeApplication`、`application.yml`、`db/migration` | 聚合启动、配置绑定、组件扫描、JPA/Flyway、数据面默认端点 |

## 4. 数据库与迁移

`application.yml` 中的 Flyway schema 范围：

```text
common
integration
orchestration
catalog
modeling
quality
security
dataservice
dataservice_api
analytics
```

迁移目录位于 `onelake-app/bootstrap/src/main/resources/db/migration/<schema>/`。当前建表数量按迁移目录大致为：

| schema | 建表数 | 说明 |
| --- | ---: | --- |
| `common` | 12 | 租户、项目、审计、Outbox、通知、告警、任务等 |
| `integration` | 6 | 数据源、采集任务、运行、CDC、文件源、schema snapshot |
| `orchestration` | 11 | DAG、job/task run、pipeline task/edge、算子、资源组 |
| `catalog` | 6 | 资产、血缘、SQL 历史/保存查询、模板、资产消费 |
| `modeling` | 13 | 主题域、指标、DWD 模型、术语、码表等 |
| `quality` | 4 | 规则、运行结果、评分、告警 |
| `security` | 8 | 密钥、脱敏、授权、角色、审批、PII、ACL |
| `dataservice` | 6 | API、版本、AppKey、订阅、调用日志、配额 |
| `analytics` | 7 | 数据集、仪表盘、发布、Notebook、模板、查询日志 |

`dataservice_api` 没有独立迁移目录，作为 PostgREST/API 视图 schema 使用。

## 5. 前端路由索引

真实路由来自 `onelake-app/web-console/src/routes.tsx`，菜单来自 `src/App.tsx`。

| 路由组 | 主要路径 |
| --- | --- |
| SSO/公开页 | `/sso/login`、`/sso/callback`、`/share/screen/:token` |
| 工作台 | `/dashboard` |
| 数据集成 | `/integration/datasources`、`/integration/sync-tasks`、`/integration/cdc`、`/integration/files`、`/integration/templates`、`/integration/monitor` |
| 湖仓与建模 | `/lakehouse/tables`、`/lakehouse/sql`、`/lakehouse/optimize`、`/lakehouse/governance-factory` |
| 数据开发/编排 | `/orchestration/pipelines`、`/orchestration/operators`、`/orchestration/runs` |
| 数据质量 | `/quality/rules`、`/quality/results`、`/quality/gate` |
| 目录与血缘 | `/catalog/search`、`/catalog/assets/:id`、`/catalog/lineage`、`/catalog/glossary` |
| 资产与安全 | `/security/map`、`/security/pii`、`/security/masking`、`/security/kms` |
| 数据服务 | `/dataservice/apis`、`/dataservice/appkeys`、`/dataservice/gateway`、`/dataservice/subscriptions` |
| 数据分析 | `/analytics/dashboards`、`/analytics/notebooks`、`/analytics/datasets`、`/analytics/library` |
| 运营与监控 | `/monitor/overview`、`/monitor/alerts`、`/monitor/incidents`、`/monitor/sla` |
| 系统管理 | `/system/tenants`、`/system/rbac`、`/system/approvals`、`/system/audit`、`/system/channels` |

## 6. 数据面索引

| 组件 | 位置 | 当前口径 |
| --- | --- | --- |
| Compose | `onelake-app/docker-compose.yml` | Postgres、Redis、MinIO、Hive Metastore、Trino、Keycloak、OpenMetadata、PostgREST、APISIX、Dagster、Superset、JupyterHub、Spark、Flink、Kafka/Zookeeper、etcd |
| Airbyte | `scripts/airbyte-local.sh` | `abctl local install --port 8000`，不属于 Compose |
| Dagster | `onelake-app/dagster/` | webserver、daemon、user-code；当前 jobs 包含 schedule reconcile、Spark pipeline、Notebook |
| Trino/Iceberg | `onelake-app/trino/` | Hive Metastore catalog + MinIO warehouse |
| dbt | `onelake-app/dbt/` | 当前作为样例模型、生成产物和静态校验背景；ODS -> DWD 主运行闭环以 Spark pipeline 为准 |
| APISIX | `onelake-app/apisix/` + `scripts/apisix-routes.sh` | 本地 `/api/*` 转发到宿主机后端 `8080` |

## 7. 文档分层

| 层级 | 文档 |
| --- | --- |
| 开发入口 | `RTK.md` |
| 当前事实 | `docs/PROJECT_STRUCTURE.md`、`docs/IMPLEMENTATION_STATUS.md` |
| 运行验证 | `docs/本地开发环境完整部署指南.md`、`docs/FRONTEND_VERIFICATION.md`、`docs/ANALYTICS_VERIFICATION.md` |
| 模块附录/历史 ADR | 其余模块方案和实施计划文档 |

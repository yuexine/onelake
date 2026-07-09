# 数据面开发指南：部署、Airbyte、Spark、Dagster、Trino、dbt

> 修订日期：2026-07-09
>
> 本文回答“数据面的开发在哪里”。当前 OneLake 控制面不搬运业务数据，负责创建配置、触发运行、记录状态和消费事件；真实执行交给 Airbyte、Spark/Dagster、Trino、Iceberg、Superset、JupyterHub 等数据面组件。

## 1. 边界

| 层 | 当前产物 | 位置 |
| --- | --- | --- |
| 部署编排 | Compose 核心数据面 | `onelake-app/docker-compose.yml` |
| Airbyte | abctl/kind 本地部署 | `onelake-app/scripts/airbyte-local.sh` |
| 调度/执行 | Dagster webserver/daemon/user-code | `onelake-app/dagster/` |
| Spark | Spark master/worker + pipeline job | `docker-compose.yml`、`dagster/definitions.py` |
| 查询 | Trino on Iceberg/Hive Metastore | `onelake-app/trino/` |
| 对象存储 | MinIO warehouse | Compose + `scripts/minio-bucket.sh` |
| API 网关 | APISIX 本地路由 | `onelake-app/apisix/`、`scripts/apisix-routes.sh` |
| dbt | 样例模型、宏、生成产物 | `onelake-app/dbt/` |

## 2. 当前主线

### 2.1 数据采集

控制面 `module-integration` 创建数据源、探测 schema/table/columns、管理 Airbyte source/destination/connection，触发并 reconcile Airbyte job。

Airbyte 启动：

```bash
cd onelake-app
AIRBYTE_LOW_RESOURCE_MODE=true make airbyte-up
make airbyte-status
make airbyte-credentials
```

关键边界：

- Airbyte UI/API `8000` 是 abctl ingress。
- 后端默认 `AIRBYTE_URL=http://localhost:18001/api/v1`，需要 port-forward。
- 发布采集任务需要 workspace、source/destination definition 或已有 Airbyte resource id。

### 2.2 ODS -> DWD

当前运行主线是 Spark-only pipeline：

- `module-modeling`：DWD 模型定义、字段映射、校验、编译、发布。
- `module-orchestration`：pipeline task/edge、job run、task run、资源画像、回填、Spark run config。
- `dagster/definitions.py`：`onelake_pipeline_run` 执行 Spark SQL/PySpark/Quality Gate task。

旧的 dbt-on-Trino、`onelake_dbt_model_run`、`dagster-dbt` 主闭环属于历史方案，不再作为当前运行事实。

### 2.3 SQL 与数据服务

SQL Workbench 使用 Trino JDBC，默认宿主端口：

```text
jdbc:trino://localhost:18080/iceberg
```

数据服务模块目前支持：

- PostgREST/APISIX 发布路径。
- SQL API 草稿与调试路径。
- AppKey/订阅/配额/调用日志实体。

完整 Trino-backed 外部 API 网关仍是后续生产化项。

## 3. Compose 服务和端口

| 服务 | 端口 |
| --- | --- |
| Postgres | `5432` |
| Redis | `6379` |
| MinIO API/Console | `9000/9001` |
| Hive Metastore | `9083` |
| Trino | `18080` |
| Spark Master/Worker UI | `18081/18082` |
| Keycloak | `8081` |
| OpenMetadata | `8585` |
| PostgREST | `3001` |
| APISIX gateway/admin | `9080/9180` |
| Dagster | `3000` |
| Superset | `8088` |
| JupyterHub | `18000` |
| Kafka/Zookeeper | `9092/2181` |
| Flink UI | `8082` |

## 4. Dagster

本地 Dagster 是多容器形态：

```bash
cd onelake-app
make dagster-up
```

当前 user-code 主要 job：

| Job | 用途 |
| --- | --- |
| `onelake_sync_task_schedule_reconcile` | 接收控制面的采集任务调度登记/撤销意图 |
| `onelake_pipeline_run` | Spark-only pipeline 运行 |
| `onelake_notebook_run` | Notebook/papermill 运行 |

Dagster user-code 镜像当前装载 Spark/papermill/notebook 运行依赖，不应在文档中再写成 `dagster-dbt/dagster-airbyte` asset 主工程。

## 5. dbt 当前定位

`onelake-app/dbt/` 仍有价值，但定位是：

1. 样例模型和宏。
2. DWD 编译/生成产物的参考结构。
3. 静态校验和历史 ADR 背景。

直接在宿主机运行 dbt 时注意 `profiles.yml` 默认端口是 `8080`；本地 Compose Trino 是 `18080`：

```bash
cd onelake-app/dbt
TRINO_PORT=18080 dbt run
```

不要把 `dbt build` 写成当前 ODS -> DWD 运行验收主路径。

## 6. APISIX

本地路由初始化：

```bash
cd onelake-app
make apisix-routes
```

当前脚本注册 `/api/*` 到宿主机后端 `8080`。Vite 默认不走 APISIX；只有设置 `VITE_API_PROXY_TARGET=http://localhost:9080` 时前端经 APISIX 验证。

## 7. 验证策略

验证必须分层报告：

| 层 | 命令/方式 | 说明 |
| --- | --- | --- |
| 进程 | `make ps` | 只确认容器启动 |
| 探活 | HTTP/CLI curl、Trino CLI、Dagster GraphQL | 确认服务可访问 |
| 控制面 | Spring Boot health/OpenAPI/API smoke | 确认控制面可用 |
| 业务链路 | Airbyte run、Spark pipeline、SQL query、API publish | 确认端到端 |

`make up` 不能单独作为“全栈就绪”的证据。

# OneLake 项目结构与索引

> 本文件作为 `onelake-app` 工程地图，按模块 + 设计文档章节双向索引，供新成员快速定位代码。

```
onelake-app/
├── pom.xml                                 # 父 POM，依赖版本锁定（§3.2）
├── Makefile                                # up/down/seed/migrate/backend/dev（§5.2）
├── docker-compose.yml                      # Compose 数据面组件（§6.1，完整部署见 docs/本地开发环境完整部署指南.md）
├── .gitignore
│
├── module-common/                          # 公共模块（§6.7/6.9/6.13）
│   └── src/main/java/com/onelake/common/
│       ├── api/ApiResponse.java
│       ├── audit/{AuditLog, AuditLogRepository, AuditLogger}.java
│       ├── config/{CorsConfig, JacksonConfig, TenantContextFilterConfig, WebClientConfig, WebMvcConfig}.java
│       ├── context/TenantContext.java
│       ├── exception/{BizException, DataplaneException, GlobalExceptionHandler}.java
│       ├── outbox/{OutboxEvent, OutboxEventRepository, OutboxPublisher, OutboxDispatcher, DomainEventHandler}.java
│       ├── security/SecurityConfig.java
│       └── util/JsonUtil.java
│
├── module-integration/                     # 数据集成（§6.4/6.10/6.11）
│   └── src/main/java/com/onelake/integration/
│       ├── api/{DataSourceController, SyncTaskController}.java + vo/
│       ├── client/{ConnectivityTester, AirbyteSyncDriver}.java
│       ├── domain/{entity, enums}/
│       ├── mapper/{DataSourceMapper, SyncTaskMapper}.java
│       ├── repository/
│       ├── service/{DataSourceService, SyncTaskService} + impl/
│       └── dto/
│
├── module-orchestration/                   # 编排调度（§6.5）
│   └── src/main/java/com/onelake/orchestration/
│       ├── api/DagController.java
│       ├── client/DagsterClient.java
│       ├── domain/{entity, enums}/
│       ├── repository/
│       ├── service/OrchestrationService.java
│       └── dto/
│
├── module-catalog/                         # 元数据/目录/血缘（§6.12）
│   └── src/main/java/com/onelake/catalog/
│       ├── api/CatalogController.java
│       ├── client/OpenMetadataClient.java
│       ├── domain/entity/{Asset, LineageEdge}.java
│       ├── repository/
│       └── service/{CatalogService, CatalogSyncService}.java
│
├── module-modeling/                        # 主题域/指标/维度（§7.5 + §7.9）
│   └── src/main/java/com/onelake/modeling/
│       ├── api/ModelingController.java
│       ├── domain/entity/{SubjectDomain, DataStandard, Metric, Dimension}.java
│       ├── repository/
│       └── service/ModelingService.java
│
├── module-quality/                         # 质量（§7.6 + §7.9）
│   └── src/main/java/com/onelake/quality/
│       ├── api/QualityController.java
│       ├── domain/entity/{Rule, RunResult, ScoreSnapshot, Alert}.java
│       ├── repository/
│       └── service/QualityService.java
│
├── module-security/                        # 安全/RBAC/审批（§7.7 + §7.9）
│   └── src/main/java/com/onelake/security/
│       ├── api/SecurityController.java
│       ├── domain/entity/{Secret, MaskingPolicy, AccessGrant, Role, RoleBinding, ApprovalRequest}.java
│       ├── repository/
│       └── service/SecurityService.java
│
├── module-dataservice/                     # DaaS（§6.6 + §7.8 + §7.9）
│   └── src/main/java/com/onelake/dataservice/
│       ├── api/DataServiceController.java
│       ├── domain/entity/{ApiDefinition, ApiVersion, AppKey, Subscription, ApiCallLog, QuotaUsage}.java
│       ├── repository/
│       └── service/DataServicePublisher.java
│
├── bootstrap/                              # 启动模块（§四 + §五）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/onelake/OnelakeApplication.java
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               ├── common/V1__common.sql           # 8 张表 + 3 审查补全
│               ├── integration/V1__integration.sql # 3 表 + 1 审查补全
│               ├── orchestration/V1__orchestration.sql
│               ├── catalog/V1__catalog.sql
│               ├── modeling/V1__modeling.sql       # 3 表 + 1 审查补全 dimension
│               ├── quality/V1__quality.sql         # 2 表 + 2 审查补全
│               ├── security/V1__security.sql       # 3 表 + 3 审查补全
│               └── dataservice/V1__dataservice.sql # 5 表 + 1 审查补全 quota_usage
│
├── dbt/                                    # dbt 工程（§4.2 + §6.8）
│   ├── dbt_project.yml
│   ├── profiles.yml
│   ├── macros/onelake_macros.sql           # 增量水位 + 脱敏宏
│   └── models/
│       ├── sources.yml
│       ├── intermediate/dwd_order_df.sql
│       └── marts/{ads_order_gmv_daily.sql, schema.yml}
│
├── web-console/                            # React 控制台（§3.3 + §4.3）
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx                         # ProLayout + 10 菜单
│       ├── api/{http.ts, index.ts}         # axios + 7 模块 SDK
│       ├── pages/{dashboard, integration, orchestration, catalog, modeling, quality, security, dataservice}/
│       └── stores/app.ts                   # zustand
│
├── scripts/                                # 数据面初始化脚本
│   ├── postgres-init.sql                   # pgcrypto + web_anon 角色
│   ├── keycloak-realm.sh                   # 创建 onelake realm + 5 角色
│   └── minio-bucket.sh                     # 创建 warehouse bucket
│
├── trino/
│   ├── catalog/iceberg.properties          # Iceberg via HMS + MinIO
│   └── hive-site.xml                       # HMS → PG + S3A
│
└── apisix/
    └── config.yaml                         # APISIX admin + etcd + Prometheus
```

## 设计文档章节 ↔ 代码位置

| 设计文档章节 | 代码位置 |
|------------|---------|
| §3.2 父 POM | `pom.xml` |
| §3.3 前端依赖 | `web-console/package.json` |
| §4.1 模块内分层包结构 | 各 `module-*/src/main/java/com/onelake/<module>/` |
| §4.2 dbt 工程结构 | `dbt/` |
| §4.3 前端控制台结构 | `web-console/src/` |
| §5.2 Makefile | `Makefile` |
| §6.1 docker-compose | `docker-compose.yml` |
| §6.2 application.yml | `bootstrap/src/main/resources/application.yml` |
| §6.3 SecurityConfig | `module-common/src/main/java/.../security/SecurityConfig.java` |
| §6.4 AirbyteSyncDriver | `module-integration/.../client/AirbyteSyncDriver.java` |
| §6.5 DagsterClient | `module-orchestration/.../client/DagsterClient.java` |
| §6.6 DataServicePublisher | `module-dataservice/.../service/DataServicePublisher.java` |
| §6.7 OutboxEvent + Dispatcher | `module-common/.../outbox/` |
| §6.8 dbt ads_order_gmv_daily | `dbt/models/marts/ads_order_gmv_daily.sql` |
| §6.9 ApiResponse / 异常 | `module-common/.../api/ApiResponse.java` + `exception/` |
| §6.10 DataSourceController 完整分层 | `module-integration/` 全部 |
| §6.11 SyncTaskServiceImpl | `module-integration/.../service/impl/SyncTaskServiceImpl.java` |
| §6.12 CatalogSyncService | `module-catalog/.../service/CatalogSyncService.java` |
| §6.13 OutboxPublisher + Dispatcher | `module-common/.../outbox/` |
| §7.1 ~ §7.9 数据库表 | `bootstrap/src/main/resources/db/migration/*/V1__*.sql` |
| §八 初始化校验清单 | 见 `IMPLEMENTATION_STATUS.md` 第十四节 |

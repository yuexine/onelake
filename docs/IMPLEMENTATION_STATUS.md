# OneLake 当前实现状态

> 修订日期：2026-07-09
>
> 本文是当前代码对齐快照，不是历史验收报告。状态来自静态代码审查和子 Agent 分片审查；本轮没有启动完整数据面或执行端到端服务验证。

## 1. 状态词表

| 状态 | 含义 |
| --- | --- |
| 已实现 | 当前代码中存在 Controller/Service/Repository/迁移/前端入口等可定位实现 |
| 已接入 API | 前端主数据或主操作走 `src/api`/`http` 调后端 |
| 混合数据源 | 页面存在真实 API，但仍保留 mock 初始值、失败 fallback、静态 tab 或假按钮 |
| 原型待接 | 页面主要是 mock 或静态交互，尚未形成后端闭环 |
| 未运行验证 | 依赖外部服务或长链路，本轮仅静态确认代码存在 |
| 历史方案 | 旧计划、早期 ADR 或已被当前主线替代的描述 |

## 2. 全局快照

| 维度 | 当前状态 |
| --- | --- |
| 后端模块 | 已实现：10 个 Maven 子模块，8 个业务模块 + common + bootstrap |
| 后端代码量 | 约 406 个主 Java 文件，61 个测试类 |
| 数据库 | Flyway 配置 10 个 schema；9 个迁移目录创建约 73 张表 |
| 前端 | 11 个顶层业务入口 + SSO + 公开分享；76 个 `src/pages/**/*.tsx` |
| 数据面 | Compose 核心服务 + `abctl` Airbyte；Spark pipeline 是 ODS -> DWD 当前运行主线 |
| 文档状态 | 2026-07-09 已重新收敛事实层、验证层和模块附录口径 |

## 3. 后端模块状态

| 模块 | 状态 | 当前能力 | 主要缺口/风险 |
| --- | --- | --- | --- |
| `module-common` | 已实现 | 统一响应/异常、租户上下文、OAuth2、审计、通知、全局任务、Outbox/Redis Stream、消费幂等 | 任务投影和通知投影需要持续由各模块补齐 |
| `module-integration` | 已实现，部分链路已联调过历史记录 | 数据源、schema/table/column discovery、Airbyte 资源与运行、日志/取消、采集任务、CDC、文件源、drift、监控 | Airbyte 依赖 workspace/definition/destination 配置；调度、容错、日志体验仍需生产化 |
| `module-orchestration` | 已实现 | Spark-only pipeline、DAG/run、pipeline task/edge、task run、算子市场、资源组、运行契约、回填 | 旧 dbt-on-Trino DWD 运行口径已替代；需补端到端自动化与更多任务类型 |
| `module-catalog` | 已实现 | 资产、字段 schema、血缘、影响分析、schema change、SQL Workbench、查询模板、Iceberg 维护、事件消费 | 权限/脱敏/成本估算仍是逐步增强；部分前端血缘仍 fallback |
| `module-modeling` | 已实现 | 主题域、指标、DWD 定义/校验/编译/发布、术语、绑定/版本/影响、码表 | DWD 运行不在 modeling 中承载；文档必须避免再写 `/models/{id}/run` 主线 |
| `module-quality` | 已实现，主页面已接 API | 规则、结果、评分/告警、质量事件 | 真实数据采样/稽核运行器、质量分自动计算和门禁生产策略仍需加强 |
| `module-security` | 已实现，部分前端接 API | 密钥、脱敏策略、授权、审批、ACL、PII 扫描、采集任务事件响应 | 脱敏/KMS 页面仍多为原型；动态脱敏执行链路需持续验证 |
| `module-dataservice` | 已实现，部分主线已接 API | API 定义、草稿/发布/下线、SQL API 调试、AppKey/订阅/配额/日志、PostgREST/APISIX、资产消费投影 | AppKey/订阅/网关前端仍多为 mock；Trino SQL 对外 API 还不是完整网关 |
| `module-analytics` | 已实现，未运行本轮 E2E | 数据集、查询、Dashboard/发布/分享、Superset token、Notebook/模板/运行、NL2SQL | Superset/JupyterHub/OpenAI 等外部依赖需单独验收；部分 Notebook endpoint 有降级 |

## 4. 前端状态

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| Dashboard | 混合数据源 | 待办接审批 API，KPI/动态存在硬编码 |
| Integration | 已接 API + 混合 | 数据源、采集任务、向导、运行日志主链路接 API；CDC、模板、部分诊断仍混合 mock |
| Lakehouse | 已接 API + 混合 | 表、SQL、治理、优化接 Catalog/SQL/Modeling/Security/Orchestration API；部分功能仍需后端链路 |
| Orchestration | 已接 API | 主路由使用 `UnifiedPipelineEditor`；历史 graph 路由重定向到统一编辑器 |
| Quality | 已接 API | 规则、结果、门禁接 `QualityAPI`，联动 Catalog/Glossary |
| Catalog | 已接 API + 混合 | 资产和术语接 API；全局搜索、血缘 fallback、部分详情仍有 mock |
| Security | 混合数据源 | PII 扫描接 API + fallback；脱敏策略、KMS 仍偏原型 |
| DataService | 混合数据源 | API 市场/详情/草稿/调试/发布/下线有 API；AppKey、订阅计量、网关多为 mock |
| Analytics | 已接 API + 未运行验证 | 数据集、大屏、分享、模板、NL2SQL、Notebook 主入口接 API；外部依赖需单独验收 |
| Monitor | 原型待接 | 总览、告警、复盘、SLA 基本是 mock |
| System | 混合/原型 | 审批中心接 API；租户、RBAC、审计、渠道仍多为 mock |

前端构建脚本注意事项：`pnpm gen:api` 当前输出到 `src/api`，而 `src/api/index.ts` 是手写 API facade；执行 `make frontend` 前必须确认不会覆盖人工维护文件。

## 5. 数据面状态

| 组件 | 当前口径 |
| --- | --- |
| Compose | `make up` 启动核心服务，但不等于业务 ready；需要结合 HTTP/CLI 探活 |
| Airbyte | 使用 `abctl` 单独启动；UI/API 在 `8000`，后端默认 Airbyte API fallback 是 `18001/api/v1`，需要 `kubectl port-forward` |
| Dagster | Compose 多容器，当前 user-code job 包含 schedule reconcile、Spark pipeline、Notebook |
| Trino/Iceberg | Trino 宿主端口 `18080`，Iceberg 使用 Hive Metastore + MinIO |
| Spark | 当前 ODS -> DWD 运行主线是 Spark-only pipeline |
| dbt | 保留样例、宏和生成产物；不再作为当前 DWD 运行主闭环 |
| Superset/JupyterHub | 容器和配置存在；账号/bootstrap/SSO 需要按 runbook 单独验收 |

## 6. 当前文档决策

1. `PROJECT_STRUCTURE.md` 和本文作为当前事实层。
2. `FRONTEND_VERIFICATION.md` 只描述前端实现/数据来源/验证方式，不再把 mock 页面写成后端完成。
3. 计划类文档统一改成“当前状态 + 历史 ADR + 剩余缺口”。
4. 所有 API 路径统一写 `/api/v1/...`；数据集成是 `/api/v1/integration`，数据服务是 `/api/v1/dataservice`。
5. DWD 统一写法：`module-modeling` 管定义，`module-orchestration` 管 Spark 运行。

## 7. 推荐验证

| 变更类型 | 推荐验证 |
| --- | --- |
| 后端全工程 | `cd onelake-app && mvn -q install -DskipTests -Djacoco.skip=true` |
| 后端模块 | `cd onelake-app && mvn -q -pl <module> -am test -Djacoco.skip=true` |
| 前端类型/构建 | `cd onelake-app/web-console && pnpm exec tsc --noEmit && pnpm build` |
| Markdown 文档 | `git diff --check`，并用 `rg` 检查旧路径/旧状态词 |
| 数据面 | `make ps` + HTTP/CLI 探活 + 业务 smoke，三者分开报告 |

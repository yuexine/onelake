# 任务计划：数据集成模块后端迭代调研与开发计划

## 目标
基于当前数据集成后端、数据面开发指南和前端页面代码，形成可执行的后端迭代开发计划，并评估实施路线可行性。

## 当前阶段
阶段 33

## 各阶段

### 阶段 1：上下文定位与范围确认
- [x] 读取项目入口 `RTK.md`
- [x] 定位数据集成模块后端代码、文档和前端页面
- [x] 将初步发现记录到 `findings.md`
- **状态：** complete

### 阶段 2：后端现状调研
- [x] 阅读 `module-integration` 控制器、服务、领域对象、Repository、DTO
- [x] 阅读 Flyway 数据库表结构与当前测试
- [x] 识别已实现能力、缺口和技术债
- **状态：** complete

### 阶段 3：数据面与技术方案调研
- [x] 阅读数据面开发指南/技术方案相关文档
- [x] 对照 Docker 数据面服务和配置
- [x] 判断后端与数据面集成路径可行性
- **状态：** complete

### 阶段 4：前端契约与页面流程调研
- [x] 阅读数据集成相关前端路由、页面、API 调用和 mock/prototype 数据
- [x] 梳理前端期望的接口、字段、状态机和操作
- [x] 标记需要后端优先补齐的接口契约
- **状态：** complete

### 阶段 5：制定计划与可行性评估
- [x] 输出分阶段开发计划
- [x] 输出实施路线、依赖、风险和验证策略
- [x] 更新 `findings.md`、`progress.md`
- **状态：** complete

### 阶段 6：第一轮迭代实现
- [x] 后端补齐 DTO、列表查询、任务生命周期接口
- [x] 前端连接管理与采集任务主页面接入真实接口
- [x] 保持前端样式不变，仅替换数据/动作来源
- [x] 运行后端测试与前端构建验证
- **状态：** complete

### 阶段 7：采集任务创建闭环现状检查与迭代计划
- [x] 重新读取采集任务创建相关前后端代码
- [x] 校准 Airbyte、schema 快照、运行回写、Outbox 事件现状
- [x] 运行后端模块测试与前端构建验证
- [x] 新增聚焦闭环迭代计划文档
- **状态：** complete

### 阶段 8：采集任务创建闭环下一轮实现
- [x] 后端新增 MySQL/Postgres schema/table/column discovery 接口
- [x] schema 快照改为读取真实 discovery columns
- [x] 发布任务时缺少 connection 则调用 Airbyte `ensureConnection`
- [x] 采集向导接入真实 schema/table/columns，保存草稿调用真实接口
- [x] 补充测试并运行后端测试与前端构建
- **状态：** complete

### 阶段 9：数据源探查策略化重构
- [x] 将 `DatabaseDiscoveryClient` 从类型分支实现改为策略分发门面
- [x] 抽象 `DataSourceDiscoveryStrategy` 和 JDBC 通用探查基类
- [x] 独立实现 MySQL/Postgres 探查策略，保留现有对外 API 和错误语义
- [x] 补充分发与未支持类型单测
- **状态：** complete

### 阶段 10：阶段 A 创建表单真实化收口
- [x] 移除采集任务向导中的 mock 数据源和样例字段映射兜底
- [x] 数据源、Schema、表、字段均改为真实接口加载失败即显式错误/重试
- [x] 收敛为单表任务创建，避免多选表与单任务 payload 不一致
- [x] 保存草稿/发布前要求真实字段映射已生成
- [x] 运行后端模块测试、前端构建和浏览器冒烟
- **状态：** complete

### 阶段 11：创建表单错误提示视觉优化
- [x] 移除 Schema/Table/DataSource 探查失败的页面内错误框
- [x] 探查失败统一使用 AntD 全局 `message` 提示
- [x] 将 500/timeout 等技术错误转成面向用户的恢复建议
- [x] 运行前端构建和 diff 空白检查
- **状态：** complete

### 阶段 12：Schema 探查接口调用错误排查与修复
- [x] 沿前端代理到后端日志定位 `/datasources/{id}/schemas` 500 根因
- [x] 刷新本地 Maven SNAPSHOT，排除代码已存在但运行时未加载接口映射的问题
- [x] 修复后端启动暴露出的质量告警与通用告警 Repository/Entity 命名冲突
- [x] 重启本地后端并验证健康检查、OpenAPI mapping 与前端代理路径响应
- **状态：** complete

### 阶段 13：发布按钮 500 与试跑功能状态检查
- [x] 沿发布按钮 `create -> enable` 请求链路定位 500 根因
- [x] 修复 `sync_task.field_mapping` 写入 PostgreSQL `jsonb` 的实体映射
- [x] 同步补齐同模块 `source_schema_snapshot.columns`、`sync_run.checkpoint` 的 `jsonb` 写入映射
- [x] 补齐本地 `common.outbox_event` 缺失的 Outbox V4 表结构，恢复事件发布
- [x] 修复 Redis Stream 事件消费时缺少 `TenantContext` 导致异步审计失败的问题
- [x] 补齐本地 PII 扫描表，并修复 security 种子数据非法 UUID
- [x] 验证创建任务接口 200，发布 enable 阶段返回可解释业务错误而非 500
- [x] 检查试跑功能开发状态
- **状态：** complete

### 阶段 14：发布错误提示复测与前端错误解包
- [x] 使用浏览器登录本地 Keycloak 开发账号进入新建采集任务向导
- [x] 复现发布失败时 toast 显示 `Request failed with status code 400`
- [x] 修复全局 HTTP 拦截器，对非 2xx 的 `ApiResponse.message` 做统一错误解包
- [x] 复测发布失败 toast 显示后端业务文案
- [x] 清理测试生成的草稿任务
- **状态：** complete

### 阶段 15：数据面执行闭环第一批实现
- [x] Airbyte 驱动补齐 source/destination 动态创建、connection 检查、job 快照和日志读取
- [x] 发布任务时支持从数据源配置创建并回写 Airbyte source/destination id，再创建 connection
- [x] 新增 dry-run、run 详情、run logs、run cancel 后端接口
- [x] `trigger` 先落本地 `QUEUED` run，触发成功后更新 `RUNNING`，失败时保留失败运行实例
- [x] `reconcile` 显式映射 Airbyte 状态，回写行数、错误和 checkpoint
- [x] 前端采集向导试跑、任务详情试跑、日志、取消运行接真实接口
- [x] 运行后端模块测试和前端构建
- **状态：** complete

### 阶段 16：调度与 Connector 配置闭环推进
- [x] 新增 Airbyte source/destination connector definition 与 spec 后端发现接口
- [x] 数据源创建表单接入 Airbyte source definition 辅助选择，并保存 workspace/source/destination 元信息
- [x] 新增 Dagster 调度意图登记客户端，任务启用/暂停时按开关触发 reconciliation job
- [x] 运行后端模块测试、全工程编译、前端构建和 diff 检查
- [x] 尝试启动本地数据面并记录 Airbyte/Dagster compose 镜像 blocker
- **状态：** complete

### 阶段 17：数据面阻断项修正
- [x] 将 Airbyte 从无效 `airbyte/airbyte:latest` Compose 服务改为官方 `abctl` 本地部署入口
- [x] 将 Dagster 从无效单镜像改为 webserver/daemon/code-location 多容器 Compose 部署
- [x] 新增 Dagster 最小 code location，包含 `onelake_sync_task_schedule_reconcile` job
- [x] 更新 Makefile 和 RTK 运行入口
- [x] 实际启动并验证 Dagster 数据面
- [x] 安装 `abctl` 并验证 Airbyte 入口；新增 chart 仓库预检，避免失败后留下半成品 kind 集群
- [x] 网络恢复后重新执行 Airbyte 本地部署；`make airbyte-status` 显示 `airbyte-abctl` 与 `ingress-nginx` Helm release 均已 deployed，`http://localhost:8000` 返回 200
- **状态：** complete

### 阶段 18：数据集成全链路实施现状复核
- [x] 复核后端 create/enable/dry-run/trigger/reconcile/logs/cancel 主链路
- [x] 复核前端采集向导、任务详情、数据源 Airbyte 配置区的真实接口接入
- [x] 复核本地 Airbyte/Dagster 数据面运行状态
- [x] 运行 `module-integration` 测试
- [x] 准备真实源库与 Airbyte destination 配置，完成发布 -> 触发 -> reconcile 的端到端实证
- **状态：** complete

### 阶段 19：真实端到端联调闭环收口
- [x] 修复 Airbyte 2.1 OAuth client credentials 鉴权接入
- [x] 修复 Airbyte workspace-scoped spec/list 接口调用兼容
- [x] 为采集任务持久化 `sourceTable`，保证发布阶段能构造真实源表 catalog
- [x] 发布阶段优先使用 Airbyte discovery catalog，并按 `targetTable` 设置目标 namespace/alias
- [x] 修复 Airbyte 2.x nested attempt 统计解析，reconcile 回写 `rowsRead/rowsWritten`
- [x] 用本地 Postgres 源表完成创建数据源 -> 探查 -> 创建任务 -> 发布 -> 触发 -> reconcile -> 目标库查数
- **状态：** complete

### 阶段 20：Integration → Catalog 联动进展检查与下一步计划
- [x] 检查 Catalog 后端 API、资产模型、同步服务和事件消费实现
- [x] 检查 Catalog 前端页面与 CatalogAPI 接入状态
- [x] 查询本地 DB 验证 `integration.table.loaded` 与 `catalog.asset` 当前状态
- [x] 形成下一步迭代计划，约束前端只做 API 对接、不改样式
- **状态：** complete

### 阶段 21：Integration → Catalog 最小可见闭环实现
- [x] Catalog 消费 `integration.table.loaded` 时自动 upsert 本地资产
- [x] Catalog API 返回前端可用 DTO
- [x] CatalogSearch 仅替换为真实 API 数据源，不改样式
- [x] 补充最小测试并运行后端/前端验证
- **状态：** complete

### 阶段 22：部署最小闭环并执行全链路测试
- [x] 编译安装后端新代码并重启本地 backend
- [x] 发布/触发采集任务并完成 Airbyte 同步
- [x] reconcile 后验证 sync_run 指标和目标库数据
- [x] 验证 `integration.table.loaded` 触发 `catalog.asset` 自动建档
- [x] 验证 Catalog API/目录页面可见新资产
- [x] 测试成功后进入第二轮字段 Schema、血缘、OpenMetadata 回写
- **状态：** complete

### 阶段 23：字段 Schema、血缘、OpenMetadata 回写第二轮实现与验证
- [x] `integration.table.loaded` payload 携带 `fieldMapping`
- [x] Catalog 资产落库 `columns` 字段 schema
- [x] Catalog 写入源表到目标表血缘和字段映射
- [x] OpenMetadata 回写接入配置开关，默认关闭且不阻塞本地 Catalog
- [x] 资产详情页接入 `CatalogAPI.getAsset`，不修改样式
- [x] 修复详情页 tabs 无法切换的受控状态问题
- [x] 运行后端/前端测试并完成真实采集全链路验证
- **状态：** complete

### 阶段 24：创建采集任务后自动触发目标表 PII 扫描实施计划
- [x] 扩展 `integration.sync_task.created` 事件 payload，携带 `taskId/sourceTable/targetTable/fieldMapping/tenantId`
- [x] 将 Security PII 扫描输入从“表名猜测”升级为“目标字段 schema/fieldMapping 驱动”
- [x] 为 `security.pii_scan_record` 增加幂等约束或仓储查重，避免重复事件/重复任务产生重复记录
- [x] `SyncTaskCreatedEventHandler` 解析事件字段并触发目标表字段扫描；字段为空时记录可观测日志并降级
- [x] 扩展 PII 类型识别规则，覆盖 hash 后字段、手机号、邮箱、身份证、姓名、银行卡等常见命名
- [x] 补齐 module-security 单元测试和 module-integration 事件 payload 测试
- [x] 本地创建测试采集任务，验证事件发布、Security 消费、PII 扫描记录写入
- **状态：** complete

### 阶段 25：PII 扫描结果反哺 Catalog 字段安全标签
- [x] 新增 `security.pii.detected` 领域事件，Security 扫描新增记录后发布字段检测结果
- [x] Catalog 消费 `security.pii.detected`，按目标表 FQN 预登记或更新资产
- [x] 将字段级 `piiType/suggestLevel/classification/piiConfidence` 合并到 `catalog.asset.columns`
- [x] 处理事件顺序：后续 `integration.table.loaded` 刷新字段 schema 时保留已有 PII 标签
- [x] Catalog 资产详情 API 返回字段级 PII 标签，不修改前端 UI
- [x] 补齐 Security/Catalog 单元测试与本地真实 API/DB 验证
- **状态：** complete

### 阶段 26：Catalog 前端接入字段级 PII 标签
- [x] 校验 Catalog API 已返回 `piiType/suggestLevel`
- [x] 补齐前端 `AssetColumn` 类型，兼容后端中文 PII 类型并新增 `suggestLevel`
- [x] 资产详情 Schema 表格展示 `PII类型` 与 `建议密级`
- [x] 保持现有页面样式与布局体系，仅做字段对接
- [x] 运行前端构建并用浏览器验证真实资产详情页
- **状态：** complete

### 阶段 27：数据质量模块真实化实施计划与 UI 完整性检查
- [x] 检查 `module-quality` 后端 API、Service、Entity 与表结构
- [x] 检查质量模块前端路由、API 封装、类型和三张页面
- [x] 使用浏览器打开规则配置、稽核结果、质量门禁页面，核对 UI 完整性和 mock/真实接口边界
- [x] 查询本地质量库与后端 API，确认当前真实数据为空
- [x] 制定分阶段实施计划：规则真实化、结果记录、门禁闭环、Catalog 回写、自动触发
- **状态：** complete

### 阶段 28：数据质量规则与稽核结果最小闭环实施
- [x] 后端质量规则创建/列表/详情改为 DTO 契约，补齐 `targetColumn` 与 `schedule`
- [x] 新增规则试跑接口，生成运行结果、质量告警和 `quality.check.*` 领域事件
- [x] 为 `quality.run_result.sample` 补齐 `jsonb` 写入映射，新增质量规则字段迁移
- [x] 前端 `QualityAPI` 补齐规则创建、试跑、结果、按目标查询接口
- [x] `/quality/rules` 从 mock 切到真实规则 API，创建弹窗接入 Catalog 资产字段，试跑调用后端
- [x] `/quality/results` 从 mock 切到真实规则与运行结果 API，支持按规则查看通过率、失败行和异常样例
- [x] 保持现有前端样式与布局体系，仅做 API 对接和已有字段展示
- [x] 运行后端单测、前端构建、数据库迁移、后端重启、真实 API E2E 与浏览器验证
- **状态：** complete

### 阶段 29：数据质量门禁失败处理最小闭环实施
- [x] 后端质量告警列表改为 `QualityAlertDTO`，携带规则、字段、最近结果和异常样例摘要
- [x] 关闭质量告警时增加租户隔离校验，避免跨租户处理告警
- [x] 前端 `QualityAPI` 增加 `getRule` 与 `closeAlert`
- [x] `/quality/gate` 从 mock 切到真实开放告警 API，展示最新失败规则、异常样例和影响资产
- [x] 门禁处理动作接入 `closeAlert`：修复、豁免、降级会关闭当前待处理告警；阻断发布保持开放状态
- [x] 移除 Gate 页 mock 审批记录依赖，避免展示伪造处理记录
- [x] 保持现有前端样式与布局体系，仅做 API 对接和真实数据替换
- [x] 运行后端单测、前端构建、后端重启、真实 API E2E 与浏览器点击验证
- **状态：** complete

### 阶段 30：SQL 工作台开发现状检查与后续计划
- [x] 检查 SQL 工作台后端 Controller、Service、DTO、Entity、Repository 和 Flyway 迁移
- [x] 检查 Trino JDBC 配置、Docker 端口映射和 module-catalog 依赖
- [x] 检查前端 `/lakehouse/sql` 页面、API 封装、历史/保存查询/表树/结果区交互
- [x] 检查 SQL 工作台与数据服务发布、编排流水线的衔接边界
- [x] 运行 `module-catalog` 测试和前端 TypeScript 检查
- [x] 输出后续迭代计划
- **状态：** complete

### 阶段 31：SQL 工作台查询生命周期最小闭环
- [x] 后端新增异步提交、查询状态、取消查询接口，保留旧同步执行接口兼容
- [x] 后端运行态增加租户隔离校验，查询完成结果短期保留，取消时同步取消 JDBC Statement
- [x] 前端运行 SQL 改为提交后轮询状态，支持取消当前查询
- [x] 前端移除暂未支持的 Spark 选项，默认 SQL 改为 `SHOW SCHEMAS`
- [x] 表树点击生成 `SELECT * FROM <fqn> LIMIT 100`
- [x] 运行后端模块测试、全工程跳测编译、前端 TypeScript 检查和 diff 空白检查
- **状态：** complete

### 阶段 32：SQL 工作台到 API 草稿联动
- [x] 数据服务后端新增 API 草稿创建接口，避免 Trino SQL 误走 PostgREST 发布链路
- [x] SQL 工作台“发布为 API”携带当前 SQL、来源资产和结果字段跳转 API 向导
- [x] API 构建向导接收 SQL 工作台上下文并预填 API 路径、视图名、SQL、参数和返回字段
- [x] API 向导保存草稿调用真实后端 `DataserviceAPI.createDraft`
- [x] API 市场和详情页接真实数据服务 API，失败时回退 mock
- [x] 运行数据服务模块测试、全工程跳测编译、前端 TypeScript 检查和前端构建
- **状态：** complete

### 阶段 33：SQL API 草稿 Trino 调试运行
- [x] 数据服务模块新增 Trino-backed API 调试服务，读取草稿 `selectSql` 执行预览
- [x] 调试服务支持 `:param` 命名参数绑定到 PreparedStatement
- [x] 调试服务保留只读 SQL 与单语句校验，缺参数/写 SQL 在连接 Trino 前失败
- [x] 数据服务 Controller 新增 `POST /api/v1/dataservice/apis/{id}/debug`
- [x] API 详情页调试区从 mock 响应切到真实 `DataserviceAPI.debugApi`
- [x] 补充调试服务单测并运行数据服务模块测试、全工程跳测编译、前端 TypeScript 检查、前端构建和 diff 空白检查
- **状态：** complete

### 阶段 34：SQL 工作台真实边界与 Trino 观测收口
- [x] 表树移除 Catalog 加载失败时的 mock fallback，失败显示真实错误和重试，空结果显示真实空态
- [x] 查询结果与历史 DTO 增加 `trinoQueryId`
- [x] `catalog.sql_query_history` 增加 `trino_query_id` 字段和索引
- [x] Trino JDBC Statement 接入 progress monitor，采集 query id 与 processed/physical input bytes
- [x] 查询成功、失败、取消时写回 Trino query id 与 scan bytes
- [x] 前端类型补齐 `trinoQueryId`，保持现有 SQL 工作台 UI 结构不变
- [x] 运行 `module-catalog` 测试、前端 TypeScript 检查和 diff 空白检查
- **状态：** complete

## 关键问题
1. 当前 `module-integration` 已落库和暴露的能力边界是什么？
2. 前端数据集成页面实际需要哪些后端接口和异步状态？
3. 数据面开发指南要求后端对接哪些运行时组件，哪些可先做控制面闭环？
4. 第一轮迭代如何切片，才能尽快从 prototype/mock 走向可验证的后端能力？

## 已做决策
| 决策 | 理由 |
|------|------|
| 使用文件规划工作流 | 用户要求多文档、多代码调研并制定详细计划，需要持久记录发现与进度 |
| 暂不修改业务代码 | 当前任务是技术方案和实施路线调研，不是直接实现 |
| 首轮推荐先做控制面主链路 | DTO/列表/状态机/前端真实接入可快速验证，避免被完整 Airbyte/Dagster 数据面联调拖住 |
| 第一轮前端只做接口集成 | 用户明确要求不要修改前端样式 |
| 闭环计划另建聚焦文档 | 原计划偏数据集成模块整体，本次需要专门回答“采集任务创建”从向导到入湖回写的端到端流程 |
| Airbyte 分两步推进 | 先支持已有 source/destination id 的可验证闭环，再在阶段 15 补动态 source/destination 创建和运行诊断能力 |
| Dagster 调度登记默认关闭 | Dagster schedule 通常由 Python repository 定义，本轮 Java 控制面只传递 reconciliation 意图，避免本地数据面缺失时阻断发布 |

## 遇到的错误
| 错误 | 尝试次数 | 解决方案 |
|------|---------|---------|
| 运行中的后端将 `/api/v1/integration/datasources/{id}/schemas` 当作静态资源处理并返回 500 | 1 | 执行 `mvn -q install -DskipTests -Djacoco.skip=true` 刷新本地 SNAPSHOT，并重启 `onelake-backend` |
| 刷新模块后启动失败：`alertRepository` Bean 命名冲突 | 1 | 将质量模块仓储重命名为 `QualityAlertRepository` |
| 刷新模块后启动失败：两个 `Alert` Entity 默认实体名冲突 | 1 | 为通用告警和质量告警实体分别指定 `CommonAlert`、`QualityAlert` |
| 发布按钮创建任务阶段 500：`field_mapping` 是 `jsonb` 但 Hibernate 以 varchar 写入 | 1 | 为 `SyncTask.fieldMapping` 增加 `@ColumnTransformer(write = "?::jsonb")` |
| Outbox 定时任务报 `aggregate_type` 缺列 | 1 | 本地执行 `common/V4__outbox_stream_contract.sql` 补齐缺失列 |
| `make migrate` 因 Flyway 多目录重复 `V1` 与环境变量解析问题不可用 | 2 | 本轮仅手工应用缺失的 common V4 DDL，后续需单独修复迁移命令 |
| 异步消费 `integration.sync_task.created` 时审计日志 `tenant_id` 为空 | 1 | 在 `RedisStreamDomainEventConsumer` 中按事件 envelope 临时恢复 `TenantContext` |
| 安全模块 PII 扫描表缺失且 V3 种子 UUID 非法 | 1 | 应用 security V2，并将 V3 种子 UUID 改为合法 UUID 后重新执行 |
| 发布失败 toast 显示 `Request failed with status code 400` | 1 | 前端全局 axios 错误拦截器提取 `error.response.data.message` 后显示后端业务文案 |
| `docker compose up airbyte dagster` 拉取 `airbyte/airbyte:latest`、`dagster/dagster:latest` 失败 | 1 | 阶段 17 修正：Airbyte 改用 `abctl`；Dagster 改为本地构建 webserver/daemon/code-location |
| `abctl local install` 下载 `https://airbytehq.github.io/charts/index.yaml` 曾失败，报 `SSL_ERROR_SYSCALL` / `EOF` | 2 | 当前已重新部署成功：Airbyte `airbyte-abctl` 2.1.0 与 `ingress-nginx` 4.15.1 release 均 deployed，`http://localhost:8000` 返回 200 |
| 数据集成全链路尚未完成真实源端到湖仓的端到端实证 | 1 | 已完成：本地 Postgres 源表 `public.codex_orders` 经 Airbyte job 2 同步到 `onelake_lake.ods_airbyte.codex_orders`，3 行落地 |
| Airbyte 2.1 `/connections/list` 空 body 返回 500 | 1 | `AirbyteSyncDriver` 在配置 workspace 时为 connection list 请求带上 `workspaceId` |
| 手工构造 syncCatalog 导致 job 成功但同步 0 行、目标表落到 `public` | 1 | 发布阶段优先使用 Airbyte discover catalog，并按 `targetTable` 设置 `namespaceDefinition=customformat` 与 `namespaceFormat` |
| Airbyte 2.x job 统计位于 `attempt.attempt`，OneLake run 行数回写为 0 | 1 | 修复 `getJobSnapshot` 兼容 nested attempt stats，reconcile 后 `rowsRead=3`、`rowsWritten=3` |
| 详情页 Tab 点击后仍停留在第一个 tab | 1 | `DetailPageLayout` 增加内部 active tab 状态，未传 `activeTab` 时按非受控模式切换 |
| 第二轮首次验证事件 payload 缺少 `fieldMapping` | 1 | 发现运行进程未完整加载 module-integration 新 jar，执行进程级后端重启后新事件携带 20 个字段映射 |

## 备注
- 重要发现写入 `findings.md`，阶段进展写入 `progress.md`。
- 若发现文档与代码不一致，按 `RTK.md` 要求以当前代码为准。

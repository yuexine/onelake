# 任务计划：数据集成模块后端迭代调研与开发计划

## 目标
基于当前数据集成后端、数据面开发指南和前端页面代码，形成可执行的后端迭代开发计划，并评估实施路线可行性。

## 当前阶段
流水线与算子市场 G2 后端闭环：已安装 Palette + Manifest 创建标准 pipeline_task + 二轮 Review 契约修复（阶段 112 已完成；前端拖拽与 Inspector 进入 9.4）

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

### 阶段 35：SQL 安全网关 parser 校验底座
- [x] 引入 JSqlParser 作为共享 SQL parser 依赖
- [x] 在 `module-common` 新增 `ReadOnlySqlValidator`，统一只读和单语句校验
- [x] 校验器仅允许 `SELECT/WITH`、`SHOW`、`DESCRIBE` 和 `EXPLAIN SELECT`
- [x] 拒绝多语句、写操作、CTAS、`SELECT INTO` 和解析失败 SQL
- [x] SQL 工作台和 SQL API 调试共用 parser 校验器，保留原业务错误码与文案
- [x] 补充 common 单测并运行 common、catalog、dataservice 模块测试
- **状态：** complete

### 阶段 36：SQL 执行前 Catalog 资产授权
- [x] `ReadOnlySqlValidator` 增加 SQL AST 表引用提取能力
- [x] Security 授权服务增加 `requireQueryAccess`，按租户、用户、ACTIVE 状态、过期时间和 `query=true` 校验
- [x] SQL 工作台执行/提交/异步 worker 在连接 Trino 前校验引用资产已登记到 Catalog
- [x] SQL 工作台对非资产 owner 的表要求 Security 查询授权
- [x] SQL API 草稿调试在参数绑定后、连接 Trino 前执行同样的 Catalog 资产授权校验
- [x] 补充 common、security、catalog、dataservice 单测
- **状态：** complete

### 阶段 37：SQL 结果字段脱敏与密级策略
- [x] Catalog 资产校验服务返回字段保护上下文，基于 `asset.columns` 中的 `classification`、`piiType` 和 `suggestLevel` 识别敏感字段
- [x] Security 服务增加 `maskRows`，返回结果前按字段 FQN 解析显式脱敏策略
- [x] 无显式策略时，对 PII 字段或 L3/L4 字段默认执行部分脱敏，避免敏感数据明文返回
- [x] 多表 join 同名字段保守合并候选字段 FQN，只要任一来源敏感或配置策略就执行保护
- [x] SQL 工作台同步执行、异步执行和 SQL API 草稿调试均在结果返回前调用脱敏
- [x] 补充 security、catalog、dataservice 单测，运行全量跳测编译和 diff 空白检查
- **状态：** complete

### 阶段 38：SQL 安全边界前端真实表达
- [x] SQL 工作台错误 Alert 透传后端真实错误，并对 Catalog 未登记、无查询授权、只读校验失败给出边界说明
- [x] SQL 工作台结果区在疑似敏感/脱敏字段返回时提示“预览结果不代表源表明文”
- [x] SQL 工作台结果列对疑似敏感列增加轻量策略标记和 tooltip
- [x] SQL 工作台查询历史增加失败原因列，保留后端错误信息
- [x] API 详情调试区透传真实后端调试错误，并在疑似脱敏响应时显示安全策略提示
- [x] 保持现有页面布局和视觉风格不变，运行前端 TypeScript、build、浏览器冒烟和 diff 空白检查
- **状态：** complete

### 阶段 39：SQL 工作台 P1/P2 效率、估算与联动
- [x] SQL estimate 接入 Trino `EXPLAIN (TYPE IO, FORMAT JSON)`，可解析扫描量时返回真实 `estimatedScanBytes`
- [x] 执行/提交前按扫描阈值做资源控制，超阈需前端确认并由后端二次校验
- [x] AUTO 路由继续真实指向 Trino，Spark batch 明确为尚未接入可执行运行时，不重新暴露假 Spark 执行
- [x] 表树支持 table/columns 两级，点击字段插入 SQL；Catalog 无字段时保持真实空子树
- [x] Monaco 增加 Catalog 表/字段 completion、常用 snippet 和格式化按钮
- [x] 保存查询增加显式更新、共享切换和删除接口及前端操作
- [x] API 草稿保存 `requestParams` 与 `responseSchema`，形成参数化 SQL 契约
- [x] SQL 工作台“加入流水线”创建真实 orchestration DAG 草稿，并在画布显示 SQL 节点
- [x] 修复 orchestration DAG `definition` jsonb 写入映射
- **状态：** complete

### 阶段 40：SQL 工作台 P0.5/P1 生产化收口
- [x] SQL 工作台与 SQL API 调试结果 DTO 增加 `maskedColumns`、`securityNotices`，前端改为读取后端明确安全提示
- [x] Security 脱敏服务返回结构化脱敏结果，保留原 `maskRows` 兼容调用
- [x] 基于 JSqlParser 增加简单列别名保护映射，覆盖 `select phone as p` 这类确定来源别名
- [x] SQL estimate 失败时返回 EXPLAIN 不可用/不可解析的真实原因，不伪造扫描量
- [x] Catalog 增加从 Trino `information_schema.columns` 刷新缺失字段的后端能力，前端表树加载时自动尝试补全字段
- [x] 数据服务增加已发布 SQL API 的 AppKey 运行时入口，校验 AppKey、租户、订阅、状态、日配额并写调用日志
- [x] 修复 Flyway 迁移入口：按 schema 顺序迁移，integration 重复 V2 改为幂等 V6
- [x] 跑通 security/catalog/dataservice 模块测试、全量后端跳测编译、前端 TypeScript、前端 build、diff 空白检查
- **状态：** complete

### 阶段 41：ODS 到 DWD 标准闭环实施方案细化
- [x] 复核当前采集、Catalog、质量、SQL 工作台、dbt、Dagster 代码边界
- [x] 对照 Medallion/dbt/Dagster/Airbyte/OpenMetadata 标准链路提炼 OneLake 闭环目标
- [x] 输出按迭代拆分的详细实施方案，明确每轮交付、改动点、验证与不做事项
- [x] 更新 `findings.md`、`progress.md`
- **状态：** complete

### 阶段 42：ODS 到 DWD 方案链路匹配评审
- [x] 按源端入湖、ODS 资产、DWD 模型、编排运行、dbt 执行、质量门禁、Catalog/血缘、前端可观测逐段评审
- [x] 检查 DWD 模型与数据开发编排的关系是否明确
- [x] 补强模型运行事件、dbt artifacts 解析、质量真实来源、字段安全标签继承和 MVP 发布点
- [x] 更新 `docs/ODS到DWD标准闭环实施方案.md`、`findings.md`、`progress.md`
- **状态：** complete

### 阶段 43：ODS 到 DWD 加工治理与算力/流水线兼容评审
- [x] 调研 Databricks、Dagster/dbt、Airflow、Azure Data Factory/Fabric、AWS Glue 等主流产品在加工、治理、编排、计算资源上的设计方式
- [x] 检查 OneLake 当前前端流水线、算子市场、SQL 工作台资源组和后端 orchestration DAG 的真实边界
- [x] 评审现有 ODS->DWD 方案是否覆盖加工治理操作、是否兼容流水线与算力/资源组能力
- [x] 在方案文档中补充 `加工治理、流水线与算力资源兼容评审` 和 `迭代 2.5：加工治理算子与算力资源契约`
- [x] 更新 `findings.md`、`progress.md`
- **状态：** complete

### 阶段 44：全局任务条开发进展检查与实施方案
- [x] 检查原型设计、前端验证报告和全局任务条组件接入状态
- [x] 检查前端 store/mock/API、通知中心、采集/SQL/编排/质量运行模型
- [x] 判断全局任务条与整体项目目标的差距和实施边界
- [x] 新增 `docs/全局任务条实施方案.md`
- [x] 更新 `findings.md`、`progress.md`
- **状态：** complete

### 阶段 45：ODS 到 DWD 迭代 0 样例数据基线实施
- [x] 新增 `scripts/ods-dwd-baseline.sh`，幂等准备 `onelake_src.public.codex_orders` 与 `iceberg.ods.ods_codex_orders`
- [x] 新增 Makefile 入口 `ods-dwd-baseline` 与 `ods-dwd-verify`
- [x] 固定样例数据为 10 行、6 个字段、3 条脏数据，满足后续 DWD 清洗/质量门禁验证
- [x] 写入样例 `integration.table.loaded` Outbox 事件，验证 Catalog 消费后生成 ODS 资产、字段 schema 和字段级血缘
- [x] 运行脚本验证、Catalog 模块测试、Trino 查询和 diff 空白检查
- **状态：** complete

### 阶段 46：ODS 到 DWD 迭代 1 派生入口与模型草稿
- [x] 阅读 `module-modeling`、湖仓表详情/建表向导、Catalog 资产 API 和现有迁移
- [x] 新增 DWD 模型草稿实体、迁移、Repository、DTO、API 和校验服务
- [x] 前端从 ODS 表详情进入“派生 DWD 明细表”，并保存模型草稿
- [x] 补充测试，核对迭代 1 验收项后再进入迭代 2
- **状态：** complete

### 阶段 47：ODS 到 DWD 迭代 2 DWD SQL/dbt 生成与静态校验
- [x] 阅读当前 dbt 项目结构、profile、示例模型和 orchestration DAG 契约
- [x] 新增 DWD 模型编译服务，生成 dbt SQL/YAML 产物并保持幂等
- [x] 校验输出字段、source 依赖、目标模型命名和增量策略
- [x] 生成或关联 disabled orchestration DAG 草稿，保留后续 Dagster 运行入口
- [x] 运行 dbt parse、后端测试、前端/接口回归和 diff 空白检查
- **状态：** complete

### 阶段 48：ODS 到 DWD 迭代 2.5 加工治理算子与算力资源契约
- [x] 为 DWD 模型补充 pipeline mode、operator graph、resource group、compute profile、engine 和 cost policy 持久化字段
- [x] 编译时生成 INPUT/TRANSFORM/GOVERN/MASK/QUALITY_GATE/DBT_MODEL/OUTPUT 节点图，并同步到 orchestration DAG definition
- [x] 将默认资源画像写入模型、DAG 和 compile 返回结果，保证后续算力市场/资源组可兼容
- [x] 对敏感字段继承和直通/脱敏策略做静态提示，不把算子市场停留在前端展示
- [x] 补充测试并运行后端、dbt、前端与 diff 检查
- **状态：** complete

### 阶段 49：ODS 到 DWD 迭代 3 Dagster dbt 执行最小闭环
- [x] 检查 Dagster user-code 镜像、definitions.py 和 dbt project 挂载路径；当前采用 Dagster op 内 subprocess dbt，而非 dagster-dbt asset job
- [x] 新增 `onelake_dbt_model_run` job，接收 modelName/run config 并执行 `dbt build --select <model>`
- [x] 控制面新增 DWD model run 记录与触发链路，写入 Dagster run id、状态和日志入口
- [x] 解析 dbt artifacts 的最小 run result，形成质量/Catalog 回写输入
- [x] 运行 Dagster/dbt 本地验证、后端测试和接口回归
- **状态：** complete

### 阶段 50：全局任务条 P0 真实任务投影与前端接入
- [x] 新增 `common.running_task` 统一任务投影表和后端任务 API
- [x] 从 `integration.sync_run` 聚合采集运行状态，映射为全局任务条可展示的 `RunningTask`
- [x] 前端移除 `runningTasks` mock，新增 `TaskAPI` 与轮询 hook
- [x] 全局任务条展示真实运行/失败任务，支持查看详情、取消运行和忽略近期终态任务
- [x] 运行后端测试、前端类型检查/构建、迁移和真实浏览器验证
- **状态：** complete

### 阶段 51：全局任务条 P1 SQL/编排/质量多来源接入
- [x] `RunningTaskService` 同步 `catalog.sql_query_history`，将 SQL 查询映射为 `LAKEHOUSE/SQL` 任务并暴露取消端点
- [x] 同步 `orchestration.job_run`，将 DAG run 映射为 `ORCHESTRATION/DAG` 任务并跳转到编排详情
- [x] 同步 `quality.run_result`，将最近稽核结果映射为 `QUALITY/QUALITY` 任务，失败进入可关闭告警态
- [x] 限制 SQL/编排/质量源同步为运行中或最近 10 分钟结果，避免历史失败批量占满任务条
- [x] 补充 common 单测，完成真实 API 与浏览器任务条验证
- **状态：** complete

### 阶段 52：全局任务条 P2 通知中心真实化联动
- [x] 扩展 `common.notification` 契约，补充内容、级别和来源引用，支持任务通知幂等生成
- [x] 新增 Notification Entity/Repository/Service/Controller，提供通知列表、单条已读、全部已读 API
- [x] `RunningTaskService` 在失败任务投影保存后生成 `TASK/CRITICAL` 通知，避免任务条和通知中心割裂
- [x] 前端新增 `NotificationAPI` 与 `useNotifications`，通知中心从 mock 改为真实 API 数据
- [x] 保持通知中心视觉不变，补充通知按钮 aria label，并验证铃铛未读数、抽屉内容和已读接口
- **状态：** complete

### 阶段 53：流水线与算子市场阶段二后端市场底座
- [x] 新增 `orchestration.operator/operator_version/operator_install` 迁移表
- [x] 新增算子实体、枚举、Repository、DTO、Service 和 `/api/v1/orchestration/operators` API
- [x] 将设计方案中的 65 个内置算子落成统一 Manifest catalog，并通过启动 seeder 幂等写入
- [x] 支持列表/详情/Manifest 校验/自定义注册/版本发布/安装/弃用元信息更新
- [x] 补充 `OperatorServiceTest`，验证 65 内置 seed、质量门禁策略校验、注册、安装版本校验和列表 manifest
- [x] 运行 `mvn -q -pl module-orchestration -am test -Djacoco.skip=true` 与 `git diff --check`
- **状态：** complete

### 阶段 54：流水线与算子市场阶段二前端市场真实 API 接入
- [x] 扩展前端 Operator/Manifest 类型与 `OperatorAPI`
- [x] `OperatorMarket.tsx` 从 mock 切到真实算子市场 API，保留现有页面风格
- [x] 支持分类、scope、关键词筛选、详情 manifest/版本展示和安装调用
- [x] 补齐加载、错误、空态和真实 API 失败提示
- [x] 运行前端 TypeScript/build，并在浏览器验证 `/orchestration/operators`
- **状态：** complete

### 阶段 55：流水线与算子市场阶段三 OperatorCompiler 接入前置核对
- [x] 重新读取 DWD 编译服务、operator graph、dbt 产物和 Dagster `onelake_dbt_model_run` 现状
- [x] 判断阶段三可立即实施的最小切片：Manifest registry lookup、算子链编译校验、DWD 默认链改用市场 manifest
- [x] 明确与 ODS->DWD 迭代 3 Dagster dbt 执行的依赖先后
- [x] 形成并执行下一轮实现切片，完成后继续测试与缺口核对
- **状态：** complete

### 阶段 56：流水线与算子市场阶段三图级校验服务
- [x] 读取 orchestration DAG 服务、控制器、DTO 和算子市场 API 现状
- [x] 新增可复用 OperatorCompiler/GraphValidator，校验节点、边、operatorRef/version、Manifest required params、compileTarget 和环路
- [x] 暴露图级校验 API，供后续 DAG 画布和 DWD 向导复用
- [x] 补充单测、后端编译和 API 验证
- **状态：** complete

### 阶段 57：流水线与算子市场阶段三前端图级校验接入
- [x] 读取 DAG 画布页面、OrchestrationAPI、OperatorAPI 和 DagNode/DagEdge 类型现状
- [x] 前端补齐 `validateGraph` API 封装和校验结果类型复用
- [x] 在 DAG 画布的保存/校验路径接入真实图级校验，展示 errors/warnings 且保持现有布局风格
- [x] 运行前端 TypeScript/build，浏览器验证画布真实校验请求
- **状态：** complete

### 阶段 58：流水线与算子市场阶段三 DAG 草稿真实保存
- [x] 读取 Orchestration 后端 DAG 创建/查询契约、前端 PipelineList 和 DagCanvas 保存入口
- [x] 后端补齐 DAG 更新接口或复用创建接口保存画布 graph definition
- [x] 前端保存按钮在图级校验通过后持久化 DAG 草稿，并反馈真实保存结果
- [x] 补充后端/前端测试，浏览器验证保存后可重新加载同一 DAG definition
- **状态：** complete

### 阶段 59：流水线与算子市场阶段三流水线列表真实化
- [x] 读取 PipelineList 当前 mock 展示、OrchestrationAPI.listDags/triggerDag 和 Dag 类型
- [x] 将流水线列表从 mock 切到真实 DAG API，保留当前表格样式和入口
- [x] 真实展示草稿/启用状态、版本和最近运行占位，禁用不可触发草稿
- [x] 运行前端 TypeScript/build，浏览器验证列表可见保存的 DAG 并可打开画布
- **状态：** complete

### 阶段 60：流水线与算子市场阶段三运行实例真实化
- [x] 评估 RunInstances 页面与 `orchestration.job_run`、`common.running_task` 的真实边界
- [x] 补齐按 DAG 或全局查询运行实例的 API 契约
- [x] 将运行实例页面从 mock 切到真实运行数据
- [x] 运行后端/前端测试和浏览器验证
- **状态：** complete

### 阶段 61：流水线与算子市场阶段三流水线最近运行真实聚合
- [x] 读取 DAG DTO/list API 与 PipelineList 最近运行占位现状
- [x] 后端在 DAG 列表返回最近一次 `job_run` 摘要
- [x] 前端流水线列表展示真实最近运行状态、时间和 run id
- [x] 运行后端/前端测试和浏览器验证
- **状态：** complete

### 阶段 62：流水线与算子市场阶段三触发运行失败可观测
- [x] 读取 DAG trigger 当前落库顺序和 PipelineList 触发刷新逻辑
- [x] 后端触发时先创建 `QUEUED` 运行实例，Dagster 成功后更新 `RUNNING`
- [x] Dagster 触发失败时保留 `FAILED` 运行实例并返回清晰业务错误
- [x] 前端触发失败后刷新流水线最近运行，确保失败可见
- [x] 运行后端/前端测试和浏览器验证
- **状态：** complete

### 阶段 63：流水线与算子市场阶段三触发就绪真实表达
- [x] 后端 `DagDTO` 返回 `triggerable/triggerBlockedReason`，区分草稿、缺作业和可触发
- [x] `triggerDag` 在调用 Dagster 前拦截不可执行草稿，避免把本地前置条件伪装成运行失败
- [x] 前端流水线列表用后端就绪字段展示 `可触发/待绑定/草稿` 并禁用不可触发按钮
- [x] 运行后端/前端测试和浏览器验证
- **状态：** complete

### 阶段 64：流水线与算子市场阶段三下一轮执行闭环核对
- [x] 复核 `onelake_dbt_model_run` Dagster job、dbt 产物、DWD DAG 与当前 trigger/reconcile 的差距
- [x] 确定下一轮最小实现切片：编排触发 DWD DAG 时创建 `modeling.model_run` 并传递 Dagster runConfig/tags
- [x] 实施后运行后端测试、全量 install、diff 检查和真实 Dagster/DB 验证
- **状态：** complete

### 阶段 65：流水线与算子市场阶段三 Dagster 运行状态刷新
- [x] `DagsterClient` 增加 run 状态查询，映射 Dagster `STARTED/SUCCESS/FAILURE/CANCELED`
- [x] 编排运行历史读取时刷新非终态 `orchestration.job_run`
- [x] 对 DWD DAG 同步刷新 `modeling.model_run` 状态，保持三方状态一致
- [x] 运行后端测试和真实运行实例页/API 验证
- **状态：** complete

### 阶段 66：流水线与算子市场阶段三编排触发 DWD 尾链一致性
- [x] 抽取 common DWD model_run 同步接口，由 modeling 复用 artifact/event 尾链实现
- [x] orchestration 刷新 Dagster 终态时优先调用 modeling 同步器，缺失时保留 JDBC 兜底
- [x] 运行建模/编排模块测试、全工程 install、diff 检查和真实 Dagster/Catalog/Quality 验证
- **状态：** complete

### 阶段 67：流水线与算子市场阶段三 DWD 运行资源观测
- [x] 复核 `DwdModelRun` 已有资源画像、扫描量、成本和重试字段
- [x] 资产详情 DWD 模型区展示最近运行的 `resourceGroup/computeProfile/scan/cost/retry`
- [x] 运行前端构建、diff 检查和真实资产详情浏览器验证
- **状态：** complete

### 阶段 68：流水线与算子市场阶段三画布算子面板真实化
- [x] `DagCanvas` 左侧算子面板从 `OperatorAPI.listOperators` 读取真实可见算子
- [x] 按市场 category 分组展示 65 个内置算子，并让画布节点按 `operatorRef` 对齐市场元数据
- [x] 运行前端构建、diff 检查和真实 DWD DAG 画布浏览器验证
- **状态：** complete

### 阶段 69：流水线与算子市场阶段四自定义算子前端注册发布入口
- [x] 对照后端已具备的 validate/register/publish/update/install API，补齐前端自定义算子注册与发布入口
- [x] 复用 Manifest 校验结果展示，避免提交非法算子版本
- [x] 运行前端构建、后端 API 冒烟和浏览器验证
- **状态：** complete

### 阶段 70：资产发现与湖仓分层表管理边界升级
- [x] 导航与页面定位命名：将目录入口表达为“资产发现”，湖仓入口表达为“分层表管理”
- [x] 资产发现页差异化：突出找数、理解、申请、消费，弱化湖仓治理字段
- [x] 分层表管理页差异化：突出 ODS/DWD/DWS/ADS、建模链路、表治理和维护状态
- [x] 详情页互跳：资产画像与表治理详情互相可达，并保持主操作边界
- [x] 同步前端验证文档并运行构建/diff 核对
- **状态：** complete

### 阶段 71：流水线与算子市场阶段四画布从市场添加算子节点
- [x] 画布算子面板支持从真实市场算子添加节点到当前 DAG 草稿
- [x] 新节点携带 `operatorRef/operatorVersion/config`，并进入现有图级校验与保存链路
- [x] 运行前端构建、图级校验 API 冒烟和浏览器验证
- **状态：** complete

### 阶段 72：流水线与算子市场阶段四画布节点属性动态化
- [x] 右侧属性面板按选中算子 `paramsSchema` 动态生成参数输入
- [x] 编辑后的节点 `config` 进入图级校验 payload，并复用现有保存定义构造链路
- [x] 运行前端类型检查、构建、diff 检查和浏览器图级校验验证
- **状态：** complete

### 阶段 73：流水线与算子市场阶段四剩余增强核对
- [x] 核对 X6 真实拖拽、连线编辑、Spark/Python 算子扩展和完整编译器剩余边界
- [x] 继续按风险切分下一轮可实施项
- [x] 每轮继续执行构建、API/浏览器和缺口核对
- **状态：** complete

### 阶段 74：流水线与算子市场阶段四画布节点拖拽定位最小闭环
- [x] 画布节点支持拖拽移动，连线随节点位置实时更新
- [x] 节点 `x/y` 坐标进入图级校验和保存定义 payload
- [x] 运行前端类型检查、构建、diff 检查和浏览器图级校验验证
- **状态：** complete

### 阶段 75：流水线与算子市场阶段四端口连线与 X6 深化
- [x] 设计并实施端口级连线编辑、删边和连线合法性可视化
- [x] 评估是否从当前 SVG 最小闭环迁移到 X6 Graph 实例，避免一次性重写破坏保存/校验链路
- [x] 运行构建、图级校验 API 和浏览器交互验证
- **状态：** complete

### 阶段 76：业务术语表 M4/M5 影响分析与治理闭环
- [x] 新增术语影响分析接口，聚合绑定字段、下游资产、质量规则、DaaS API、DAG 和安全建议
- [x] 补齐术语版本 diff/影响展示，已审定术语变更进入可解释治理状态
- [x] 前端 Glossary 展示影响分析、版本历史和废弃风险提示
- [x] 运行后端测试、前端构建、API 冒烟和浏览器验证
- **状态：** complete

### 阶段 77：业务术语跨模块最小联动闭环
- [x] 建模向导字段支持选择术语，并在提交/编译后写入字段绑定
- [x] 质量规则按术语筛选字段并展示术语上下文
- [x] 敏感术语字段绑定触发安全建议或 PII 待确认记录
- [x] DaaS API 草稿/详情继承字段术语定义、口径和密级提示
- [x] 运行后端/前端检查和真实端到端验证
- **状态：** complete

### 阶段 78：流水线与算子市场阶段四算子生命周期治理入口
- [x] 对照市场 API，补齐前端算子废弃/恢复入口，限制内置算子误操作
- [x] 列表与详情展示 `DEPRECATED` 状态，并在安装/使用前给出真实状态反馈
- [x] 运行前端类型检查、构建、后端 API 冒烟和浏览器验证
- **状态：** complete

### 阶段 79：流水线与算子市场阶段四 Spark/Python 扩展边界与编译器深化
- [x] 对照 `compileTarget=SPARK/PYTHON` 扩展点，核对后端 Manifest 校验、图级校验和前端表达边界
- [x] 设计首个非 SQL_DBT 扩展的运行时/部署契约，避免在无 Dagster op 前伪装可执行
- [x] 评估字段 schema 闭合、敏感字段治理和资源组校验的图编译器深化切片
- [x] 运行后端/前端检查，并形成下一轮可实施项
- **状态：** complete

### 阶段 80：流水线与算子市场阶段四后端端口级图校验深化
- [x] 后端图级校验识别边 `targetPort`，兼容单输入端口旧边格式
- [x] 多输入算子强制声明 `targetPort`，校验端口缺失、未知端口和 `ONE` 基数冲突
- [x] 用 JOIN 内置算子运行单测和真实 API 验证
- **状态：** complete

### 阶段 81：流水线与算子市场阶段四字段 schema 闭合与治理校验
- [x] 核对当前 operator graph 中字段 schema 的真实来源和缺口，避免无 schema 时伪造闭合校验
- [x] 补齐可验证的字段引用/输出 schema 推导最小切片
- [x] 评估敏感字段透传与 MASK/ENCRYPT 治理校验的可接入数据源
- [x] 运行后端/前端测试、API 冒烟和浏览器验证
- **状态：** complete

### 阶段 82：流水线与算子市场阶段四资源组与执行资源契约校验
- [x] 核对资源组/计算画像当前真实来源，确认本地暂无业务侧资源组注册表
- [x] 在 Manifest 和 operator graph 校验中补齐受控 `engine/resourceGroup/computeProfile` 组合校验
- [x] 保持未声明资源时兼容默认值，不伪装资源组后台管理已完成
- [x] 运行后端/前端测试、API 冒烟和浏览器验证
- **状态：** complete

### 阶段 83：流水线与算子市场阶段四 DWD 编译产物与质量门禁算子对齐
- [x] 对照方案 §5.1，确认 `schema.yml` 质量测试产物仍由字段映射主键硬编码生成，未消费 operator graph 中 `QUALITY_GATE` 节点配置
- [x] 将 DWD compile 的 operator graph 生成提前，并让 `generateSchemaYaml` 从质量门禁节点 `config.columns/config.tests` 生成 dbt tests
- [x] 保留无质量门禁配置时的主键兜底，兼容历史草稿
- [x] 运行后端/前端测试、运行态 API 冒烟和缺口复核
- **状态：** complete

### 阶段 84：流水线与算子市场阶段四质量门禁 dbt generic tests 覆盖增强
- [x] 对照方案质量门禁清单，确认 `gate.enum` 与 `gate.referential` 可映射为 dbt generic tests，适合本轮落地
- [x] 将 `accepted_values` 与 `relationships` 从 operator graph 配置渲染到 `schema.yml`
- [x] 运行后端/前端测试、运行态 API 冒烟和缺口复核
- **状态：** complete

### 阶段 85：流水线与算子市场阶段四范围/正则门禁 dbt macro 落地
- [x] 对照方案质量门禁清单，确认 `gate.range/gate.regex` 需要 OneLake 自定义 dbt generic test macro 才能真实执行
- [x] 新增 `onelake_range/onelake_regex` dbt generic tests
- [x] 将 `gate.range/gate.regex` 从 operator graph 配置渲染到 `schema.yml`
- [x] 运行后端/dbt/前端测试、运行态 API 冒烟和缺口复核
- **状态：** complete

### 阶段 86：流水线与算子市场阶段四行数门禁模型级 dbt test 落地
- [x] 对照方案质量门禁清单，确认 `gate.row_count` 应作为模型级 dbt generic test 输出
- [x] 新增 `onelake_row_count` dbt generic test
- [x] 支持从 operator graph 渲染 `models[].tests` 模型级 tests
- [x] 运行后端/dbt/前端测试、运行态 API 冒烟和缺口复核
- **状态：** complete

### 阶段 87：流水线与算子市场阶段四 DWD sources.yml 聚合一致性修复
- [x] 复核完整 dbt project parse 失败原因，确认 `models/generated/sources.yml` 被单模型编译覆盖，导致历史 DWD 模型缺失 source
- [x] DWD compile 聚合当前模型与已验证 DWD 模型的 ODS sources，避免共享 source manifest 漂移
- [x] 补充单测覆盖历史已编译模型 source 保留
- [x] 运行后端/dbt/前端测试、运行态 API 冒烟和缺口复核
- **状态：** complete

### 阶段 88：流水线与算子市场阶段四 freshness 质量门禁 dbt source 产物落地
- [x] 对照方案质量门禁清单，确认 `gate.freshness` 应输出 dbt source freshness，而不是列级 generic test
- [x] 支持从 operator graph 读取 `column/maxDelay/actionOnViolation` 并渲染 `loaded_at_field/freshness.warn_after/error_after`
- [x] 覆盖聚合 sources.yml 中历史已验证 DWD 模型 freshness 配置不丢失
- [x] 运行后端/dbt/前端测试、运行态 API 冒烟和缺口复核
- **状态：** complete

### 阶段 89：流水线与算子市场阶段四 custom_sql 质量门禁只读断言落地
- [x] 对照方案质量门禁清单，确认 `gate.custom_sql` 需要 SQL 安全协议，不能裸写模板
- [x] 新增 `onelake_custom_sql` dbt generic test macro，断言 SQL 返回违规记录即失败
- [x] 编译阶段校验 custom SQL 为单条只读语句，且只能通过 `{{ model }}` 引用当前模型
- [x] 保留模型已保存的 `gate.freshness/gate.custom_sql` 扩展质量门禁节点，避免默认 graph 覆盖
- [x] 运行后端/dbt/前端测试、运行态 API 冒烟和缺口复核
- **状态：** complete

### 阶段 90：流水线与算子市场阶段四 dbt 校验噪声清理
- [x] 修复既有 `dbt_utils.accepted_range` 顶层参数写法，改为 dbt 1.11 推荐的 `arguments:`
- [x] 移除当前未命中的 `models.onelake.staging` 配置，避免 unused configuration warning
- [x] 运行完整 dbt parse、前后端检查和缺口复核
- **状态：** complete

### 阶段 91：流水线与算子市场阶段四资源组与计算画像注册表闭环
- [x] 新增 `orchestration.resource_group` 与 `orchestration.compute_profile` 注册表迁移，并种子化默认 Trino/Spark/Python 资源契约
- [x] 新增资源组/计算画像实体、仓库、DTO、服务和 API，支持租户自定义注册、更新与查询
- [x] 将 `OperatorService` 的 Manifest/graph 资源校验改为复用资源组注册表，不再依赖服务内静态 Map
- [x] 前端 API/type 层补齐资源组与计算画像契约，供后续画布/资源管理页复用
- [x] 运行后端/dbt/前端测试、真实 API 冒烟、临时资源清理和缺口复核
- **状态：** complete

### 阶段 92：流水线与算子市场阶段四 Spark/Python 运行契约就绪边界
- [x] 查询当前 Dagster repository jobs，确认仅 `onelake_dbt_model_run` 与 schedule reconcile 已暴露
- [x] 新增运行契约 API，返回 `SQL_DBT/SPARK/PYTHON` 的 Manifest 支持、图级执行支持和 Dagster job 可用性
- [x] 编排触发前识别 Spark/Python contract-only DAG，明确阻断且不创建 `job_run`
- [x] 前端 API/type 层补齐运行契约契约，供后续画布启用/禁用运行入口
- [x] 运行后端/dbt/前端测试、真实 API 冒烟、临时 DAG 清理和缺口复核
- **状态：** complete

### 阶段 93：治理表工厂迭代 1-4 最小建模闭环
- [x] 新增 `治理表工厂` 页面，作为“操作一张 ODS 表并治理生成新 DWD 表”的产品化入口
- [x] 支持源表选择、目标表名、业务域、物化方式、分区表达式和字段治理矩阵
- [x] 支持字段级直通、表达式、字典映射、关联补充等高级算子配置
- [x] 前端生成 DWD draft request 与 operator graph，后端保存治理图、编译 SQL/dbt 并保留高级算子节点
- [x] 后端支持 lookup join 编译为 dbt `source` + `left join`，字典映射通过字段表达式编译为 `CASE`
- [x] ODS 分层表管理新增 `治理成表` 入口跳转到治理表工厂
- [x] 运行建模单测、dbt parse、前端类型检查和生产构建
- **状态：** complete

### 阶段 94：治理表工厂迭代 5-9 运行发布与契约可见闭环
- [x] 治理表工厂新增本地异常预览：主键缺失、敏感字段直通、字典未配置、关联补充不完整
- [x] 页面接入模型运行接口，展示最近运行状态并复用现有 DWD/Dagster 执行链路
- [x] 后端新增模型发布接口，发布已校验模型并发出 `modeling.model.published` 事件
- [x] 前端接入发布动作，展示 `DRAFT/VALIDATED/PUBLISHED` 状态
- [x] 页面接入运行契约 API，展示 `SQL_DBT READY` 与 `SPARK/PYTHON` 未接入 Dagster job 的真实边界
- [x] 后端允许 `VALIDATED` 模型继续编辑并回退 `DRAFT`，已发布模型仍要求新建版本
- [x] 运行后端单测、整包编译、dbt parse、前端类型检查/构建、OpenAPI 冒烟和浏览器验证
- **状态：** complete

### 阶段 95：治理能力并入流水线最佳实践收敛
- [x] 将治理表工厂改为支持嵌入模式，作为字段治理节点的配置编辑器复用
- [x] 流水线画布新增 `ods-dwd` 模板，自动生成 ODS 输入、字段治理矩阵、DWD 输出三节点
- [x] 字段治理节点右侧属性面板展示模型状态、目标表和“配置字段治理”入口
- [x] 字段治理矩阵在流水线宽抽屉内完成源表、字段规则、高级算子、校验、编译、运行和发布
- [x] 分层表管理“治理成表”入口改为打开流水线 DWD 治理模板
- [x] 左侧菜单移除独立“治理表工厂”；旧路由兼容跳转到流水线模板
- [x] 运行前端类型检查、前端构建、建模模块测试和浏览器验证
- **状态：** complete

### 阶段 96：流水线与治理设计器产品边界重构
- [x] 将 `ods-dwd` 模板从三节点改为 `ODS 源表 -> DWD 治理模型 -> 治理质量门禁 -> DWD 治理表` 四节点编排
- [x] 顶层画布左侧面板改为“编排算子”，隐藏字段处理器类算子，并提示字段级算子进入治理设计器维护
- [x] DWD 治理模型属性面板改为模型契约表达，入口文案从“配置字段治理”改为“打开治理设计器”
- [x] 治理设计器内部将“字段治理矩阵”收敛为“字段映射与处理 Recipe”，字段级算子列改为“字段处理”
- [x] 算子市场新增前端推导的适用范围筛选：编排步骤、模型 Recipe、字段处理器、质量断言、复合模板
- [x] 治理模型保存后将真实 `sourceFqn/targetFqn` 同步回写到 ODS 输入和 DWD 输出节点
- [x] 运行前端类型检查、前端构建、图级校验浏览器实证和算子市场筛选浏览器实证
- **状态：** complete

### 阶段 97：DWD 治理流水线工作台主路径重构
- [x] 新增 `dwd-workbench` 页面组，将 DWD 治理流水线主路径升级为阶段化工作台
- [x] `/orchestration/pipelines/new?template=ods-dwd` 分流到 DWD 工作台，普通 `/orchestration/pipelines/new` 保持旧 DAG 画布
- [x] 新增 `/orchestration/pipelines/:id/workbench` 主路径和 `/orchestration/pipelines/:id/graph` 技术 DAG 辅助路径
- [x] 工作台串联源表与目标、治理模型、质量门禁、运行发布、监控血缘五阶段
- [x] 工作台嵌入现有 `GovernanceFactory`，保留字段映射、字段处理、高级算子、保存校验、编译、运行和发布能力
- [x] 流水线列表根据 DAG definition/operatorGraph 识别 DWD 治理流水线，打开文案从“画布”切换为“工作台”
- [x] 工作台详情态接入 `OrchestrationAPI.getDag` 与 `ModelingAPI.getModel`，从 DAG definition/operatorGraph 恢复模型上下文
- [x] `GovernanceFactory` 支持 `initialModel`，可按已有模型初始化目标表、字段映射、字段处理和模型状态
- [x] 运行前端类型检查、前端构建、空白检查和浏览器路由实证
- **状态：** complete

### 阶段 98：DWD 工作台新建到详情态闭环
- [x] 工作台新增统一 `handleModelChange`，模型保存/编译/发布后同步模型、DAG、source/target、dbt 和算力上下文
- [x] 新建工作台在模型编译生成 `orchestrationDagId` 后自动切换到 `/orchestration/pipelines/{dagId}/workbench`
- [x] 工作台顶部与运行发布阶段统一使用真实 `pipelineDagId` 打开技术 DAG
- [x] 运行发布阶段将占位按钮改为真实导航：技术 DAG、运行实例、观测血缘
- [x] 运行实例页支持 `?dagId=` 查询参数，进入后聚焦当前流水线并提供“查看全部”
- [x] 运行前端类型检查、前端构建、空白检查和登录态浏览器实证
- **状态：** complete

### 阶段 99：DWD 工作台质量门禁可编辑
- [x] 梳理后端 DWD `operatorGraph` 质量门禁契约，确认 `QUALITY_GATE` 与 `gate.*` 节点会编译为 dbt tests
- [x] 工作台新增质量门禁草稿模型，支持从已有模型 operatorGraph 还原门禁配置
- [x] 质量门禁阶段支持主键完整性、枚举值命中、数值范围、自定义 SQL 四类门禁配置
- [x] 保存门禁时更新模型 operatorGraph，触发模型校验，并刷新工作台模型状态
- [x] 运行前端类型检查、前端构建、空白检查和登录态浏览器质量阶段实证
- **状态：** complete

### 阶段 100：DWD 工作台监控血缘闭环
- [x] 复用现有目录血缘能力，不在工作台重复实现大图渲染
- [x] 监控血缘阶段新增字段级 lineage 摘要，按模型字段映射展示源字段、目标字段、转换表达式和治理标记
- [x] 监控血缘阶段新增目录血缘、资产详情、运行实例三个操作入口
- [x] 资产详情入口按目标表 FQN 查询 Catalog 资产，查不到时降级到目录血缘
- [x] 运行前端类型检查、前端构建、空白检查和登录态浏览器观测阶段实证
- **状态：** complete

### 阶段 101：DWD 工作台资源与算力配置闭环
- [x] 源表与目标阶段接入资源组注册表，展示资源组、计算画像和执行引擎
- [x] 支持在工作台保存模型级 `resourceGroup/computeProfile/engine`，并同步到后续编译 payload
- [x] 治理模型阶段将资源配置传入 `GovernanceFactory`，避免新保存模型退回硬编码默认算力
- [x] 运行前端类型检查、前端构建、空白检查和登录态浏览器资源控件实证
- **状态：** complete

### 阶段 102：DWD 工作台字典治理预设与版本化配置
- [x] 字典匹配字段处理支持选择标准字典预设，并自动填入映射内容
- [x] 字典配置在 UI 中展示字典名、版本、映射数量和未命中策略
- [x] `operatorGraph` 写入 `dictionaryRef/dictionaryName/dictionaryVersion/dictionarySource/pairs`，支持再次打开模型时回显
- [x] 保持当前前端 CASE 编译路径，不伪装后端字典主数据已完成
- [x] 运行前端类型检查、前端构建和登录态浏览器字典抽屉实证
- **状态：** complete

### 阶段 103：标准字典主数据事实源闭环
- [x] 新增 `modeling.codebook` 与 `modeling.codebook_version`，支持字典集、字典项、发布版本快照
- [x] 新增 Codebook 实体、仓储、DTO、服务、Controller 和领域事件常量
- [x] 支持查询、创建、更新、发布版本、废弃和版本历史接口
- [x] 前端 API/type 层接入已发布字典，DWD 治理设计器合并后端字典与内置预设
- [x] 运行后端建模模块测试、bootstrap 聚合编译、前端类型检查/构建、真实 API 和浏览器验证
- **状态：** complete

### 阶段 104：DWD 运行资源上下文透传到 Dagster tags
- [x] 核对 DWD 运行链路，确认 `resourceGroup/computeProfile` 已进入 Dagster op config 和运行记录
- [x] 将 `resourceGroup/computeProfile` 补入 Dagster execution tags，支持跨系统按资源画像检索
- [x] 补充单测捕获 launch tags，确认默认资源上下文进入 Dagster metadata
- [x] 明确本轮不是运行时队列/配额调度器，只完成资源观测透传
- **状态：** complete

### 阶段 105：DWD 编排触发资源上下文一致性闭环
- [x] 核对 Orchestration 触发 DWD DAG 链路，确认资源组和计算画像已进入 `run_config`
- [x] 将 `onelake.resource_group` 与 `onelake.compute_profile` 同步补入编排触发的 Dagster tags
- [x] 补充编排服务单测，同时断言 DWD DAG launch 的 op config 与 tags 资源上下文一致
- [x] 运行 `module-orchestration` 测试，确认编排入口不再丢失资源观测标签
- **状态：** complete

### 阶段 106：流水线模块重设计方案定稿
- [x] 通读流水线前后端实现（DagCanvas/DwdPipelineWorkbench/PipelineList + module-orchestration/modeling + Dagster）
- [x] 完成问题诊断：产品形态分裂、画布不可执行、算子图与执行脱节、跨 schema 直写等
- [x] 完成竞品调研（DataWorks/Databricks+DLT/Dagster/dbt Cloud/ADF·Fabric/Glue）
- [x] 重定义功能目标：数据开发与治理编排中枢，统一编辑器，任务类型化
- [x] 输出前端原型/交互、后端重构（任务一等实体表、引擎可插拔 SPI、含 Spark 扩展）设计
- [x] 与用户确认方向决策：统一编辑器 / 可扩展 Spark / pipeline_task 表 / P1-P4 全闭环 / 落文档
- [x] 写入 `docs/流水线模块重设计方案.md`
- **状态：** complete

### 阶段 107（P1）：流水线可执行底座
- [x] 新增迁移 `orchestration/V4__pipeline_task.sql` 等：pipeline_task/pipeline_task_edge/task_run、dag 扩字段、统一 RunStatus
- [x] 新增 Spark 主链路 `PipelineCompileService`，按边推导输入并生成 Spark 执行配置
- [x] Dagster 新增 `onelake_pipeline_run`，运行 Spark SQL / PySpark 任务
- [x] 后端统一 `triggerDag` 走 `onelake_pipeline_run`，节点级 task_run 回写
- [x] 本地用多源 Join + 治理 + 质量 + DWD Sink 流水线触发跑通并验证
- **状态：** complete（原 TRINO_DBT/dbt 子图方案已被阶段 109-110 Spark-only 决策替代）

### 阶段 108：数据流 DAG 契约化与多输入/多输出计算闭环
- [x] 将 `pipeline_task_edge` 从“先后依赖”升级为“数据流边”：明确 source output、target input、asset FQN、alias、join role、trigger policy 和 freshness policy
- [x] 建立流水线主链路节点端口契约：按任务类型校验输入/输出端口、Join left/right 基数、悬空输入、缺失 asset FQN 与 fan-out 合法性
- [x] 结构化 Join 最小节点契约：`SPARK_SQL` 通过 `dataflow.nodeKind=JOIN` 表达 left/right 输入、join type、condition 和 select
- [x] 编译期按边推导 Spark 下游输入：`from_tables`、`dataflow_inputs`、别名和 Join SQL 不再依赖重复手填
- [x] 画布展示节点输入/输出计数、依赖边端口与 alias，并提供“添加连线”表单维护数据流边契约
- [x] 补齐运行时多源就绪屏障（控制面最小实现）：多个 `SYNC_REF` fan-in 默认记录 readiness，全部输入就绪后触发；持久化水位/批次窗口后续深化
- [x] 扩展结构化 `DERIVE_COLUMN` 与 `SINK` 节点：支持按单入边生成 Spark 派生字段 SQL、脱敏表达式和 DWD 落表 SQL
- [ ] 继续扩展结构化 Union/Lookup/Branch 节点，支持更多 fan-in/fan-out 计算形态
- [x] 运行实例页展示真实数据流拓扑：节点输入、节点输出、行数、产物表、上游/下游链路和失败传播
- [x] 补齐运行态失败语义：新增 `UPSTREAM_FAILED`/`SKIPPED`，终态刷新时沿 PIPELINE 边传播上游失败
- [x] 创建 `task_run` 时按 DAG 拓扑初始化：`SYNC_REF` 作为已就绪观测节点，直接下游进入 `RUNNING`，未满足上游屏障的节点保持 `QUEUED`
- [x] 右侧节点面板补齐数据流可观测：展示“输入来自”“输出给”、端口、别名、表 FQN、触发策略和新鲜度策略
- [x] 使用“两 MySQL 源表 Join 生成 DWD 表 + 质量门禁”做真实数据面端到端验收
- **状态：** in_progress

### 阶段 109：流水线主链路 Spark-only 收敛
- [x] 新建流水线默认使用 `SPARK / spark-default / spark-small`，任务默认使用 Spark 家族引擎
- [x] 新建、模板、回填链路不再创建 `SQL_MODEL` / `FIELD_GOVERNANCE` / `TRINO_DBT` 主链路节点
- [x] `onelake_pipeline_run` 运行配置只生成 Spark op config，`dbtSelect` 不再作为流水线主链路输入
- [x] 运行契约接口只暴露 `SPARK -> onelake_pipeline_run`
- [x] 前端统一编辑器移除 `SQL 模型` 和 `Trino + dbt` 新建入口，字段治理改为 Spark 结构化节点表达
- [x] 旧 `SQL_MODEL` / `FIELD_GOVERNANCE` 不允许在新流水线主链路创建
- **状态：** complete

### 阶段 110：硬删历史枚举和旧 DWD/dbt 模型运行能力
- [x] 删除流水线历史任务枚举 `SQL_MODEL`、`FIELD_GOVERNANCE` 与旧运行引擎枚举 `TRINO_DBT`
- [x] 删除旧 DWD/dbt Dagster job/op、Dagster run config builder、建模侧 `POST /models/{id}/run` 和 DWD model run synchronizer
- [x] 运行契约、算子市场、资源组、前端画布和运行实例测试全部收敛为 Spark-only
- [x] 新增迁移清理已落库历史资源默认值：内置资源只保留 `spark-default`，DWD 模型默认资源改为 `spark-default/spark-small/SPARK`
- [x] 删除未接入 Spark 主链路的旧 dbt 质量门禁编译器
- **状态：** complete

### 阶段 111：G2 算子拖入生成可执行节点
- [x] 读取 RTK、M4 9.2 任务卡、当前 G1 实现和近期提交
- [x] 确认 manifest 默认 config 的取值口径
- [x] 比较方案并确认最小设计
- [x] 审阅并确认已提交的设计规格
- [x] TDD：补已安装算子集合与安装判定测试
- [x] TDD：补从 Manifest 创建 pipeline_task 字段/默认配置/失败语义测试
- [x] 实现 `GET /operators/installed` 与 OperatorService 安装集合能力
- [x] 实现 `POST /pipelines/{dagId}/tasks/from-operator` 与专用请求 DTO
- [x] 补从输入算子创建节点后进入 9.1 的编译闭环测试
- [x] Review 修复：Palette 过滤到 G1 可执行集合并与创建命令共享兼容策略
- [x] Review 修复：按 ref 可见性优先级去重并返回 pinnedVersion 对应 Manifest
- [x] Review 修复：编译图从锁定 Manifest 读取自定义输入端口契约
- [x] Review 修复：补同名、固定版本、自定义端口、源端口和多输入回归测试
- [x] 运行聚焦测试、module-orchestration 全量测试与 `git diff --check`
- **状态：** complete

### 阶段 112：G2 二轮 Review 修复与提交
- [x] 核对 pinnedVersion、同层同名解析、既有废弃节点更新和空 example 四项问题
- [x] 确认固定版本冲突返回业务错误，不静默改写请求
- [x] 更新并提交 G2 二轮修复设计规格
- [x] TDD：补四项问题的失败回归测试
- [x] 集中 OperatorService 已安装算子解析并统一稳定 ID 选择
- [x] 区分创建/改绑与保持既有锁定绑定的校验语义
- [x] 加固 Manifest example 校验和历史快照默认配置提取
- [x] 运行聚焦测试、module-orchestration 全量测试和 diff 检查
- [x] 按 G2 范围暂存并提交本轮代码
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
| 首次新增 G2 设计规格的 patch 有一行缺少新增标记，校验拒绝应用 | 1 | 修正 patch 格式后重新应用，规格文件已完整写入并通过占位符与空白检查 |
| G2 首次聚焦测试未带 `-am`，module-orchestration 编译时找不到 reactor 内 `InternalApiTokenFilter` | 1 | 改用 `-pl module-orchestration -am` 并关闭上游模块指定测试缺失失败，先构建依赖模块再进入 G2 红灯测试 |
| G2 首轮绿灯测试有两处预期偏差：Spark SQL FQN 会安全加反引号，Palette 按 OperatorCategory 枚举排序 | 1 | 保持现有安全生成与排序契约，修正测试断言为带引号 SQL 和真实分类顺序 |
| G2 二轮失败测试首次 testCompile 找不到 `getInstalledManifest`，且同名查询仍返回 Optional | 1 | 按确认设计新增集中安装 Manifest 解析，并将同层查询改为 List 后稳定排序 |
| G2 二轮首轮绿灯测试：同名夹具 UUID 高位导致自然序与字面序不同，编译 mock 缺少 getManifest | 1 | 使用无符号高位歧义的固定 UUID，并为 9.1 编译阶段单独提供锁定 Manifest mock |
| 测试汇总读取命令在 `onelake-app/` 下重复拼接 `onelake-app/` 路径 | 1 | 改用相对模块路径并以 XML 解析汇总 Surefire 结果 |
| 运行中的后端将 `/api/v1/integration/datasources/{id}/schemas` 当作静态资源处理并返回 500 | 1 | 执行 `mvn -q install -DskipTests -Djacoco.skip=true` 刷新本地 SNAPSHOT，并重启 `onelake-backend` |
| 刷新模块后启动失败：`alertRepository` Bean 命名冲突 | 1 | 将质量模块仓储重命名为 `QualityAlertRepository` |
| 刷新模块后启动失败：两个 `Alert` Entity 默认实体名冲突 | 1 | 为通用告警和质量告警实体分别指定 `CommonAlert`、`QualityAlert` |
| 发布按钮创建任务阶段 500：`field_mapping` 是 `jsonb` 但 Hibernate 以 varchar 写入 | 1 | 为 `SyncTask.fieldMapping` 增加 `@ColumnTransformer(write = "?::jsonb")` |
| Outbox 定时任务报 `aggregate_type` 缺列 | 1 | 本地执行 `common/V4__outbox_stream_contract.sql` 补齐缺失列 |
| `make migrate` 因 Flyway 多目录重复 `V1`、环境变量解析、seed 语法/UUID/索引幂等问题不可用 | 5 | 阶段 40 改为按 schema 顺序迁移，修复 integration/security seed 和重复索引，`make migrate` 已完整通过 |
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

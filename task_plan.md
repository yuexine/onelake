# 任务计划：数据集成模块后端迭代调研与开发计划

## 目标
基于当前数据集成后端、数据面开发指南和前端页面代码，形成可执行的后端迭代开发计划，并评估实施路线可行性。

## 当前阶段
阶段 14

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
| Airbyte 本轮只做 ensureConnection | 当前缺稳定 connector/source/destination 配置模型，先支持已有 source/destination id 的可验证闭环 |

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

## 备注
- 重要发现写入 `findings.md`，阶段进展写入 `progress.md`。
- 若发现文档与代码不一致，按 `RTK.md` 要求以当前代码为准。

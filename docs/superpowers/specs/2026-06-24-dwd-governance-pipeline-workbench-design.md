# DWD 治理流水线工作台前端重构设计

## 背景

当前 DWD 治理能力已经具备若干可运行的技术基础：DWD 草稿、字段映射、operator graph、dbt 编译、质量门禁、运行契约和发布入口。但前端体验仍围绕 `DagCanvas`、抽屉和算子节点拼装，导致用户无法顺着“一张 ODS 表治理成一张 DWD 表”的业务故事完成闭环。

本设计确认采用“DWD 治理流水线工作台”作为主体验。`DagCanvas` 保留为自定义流水线和技术视图，不再承担 DWD 治理主流程。

## 目标

1. 让用户在流水线模块内完成 ODS 到 DWD 的完整治理闭环。
2. 默认页面围绕业务流程组织，而不是围绕 DAG 技术对象组织。
3. 顶层流水线表达资产流转、治理模型、质量门禁、运行发布和监控。
4. 字段映射、字段处理器、字典匹配、关联查询和脱敏规则收敛在治理模型设计器内部。
5. 算子市场按适用范围分层，避免字段级能力混入顶层 DAG。

## 非目标

1. 本轮不重写后端建模、编排和算子市场 API。
2. 本轮不落地 Spark/Python 真实运行态。
3. 本轮不移除通用 `DagCanvas`，只调整 DWD 治理主入口。
4. 本轮不做全新的低代码 DAG 产品。

## 核心用户故事

作为数据开发或数据治理人员，我希望在流水线模块里选择一张 ODS 表，配置字段治理规则、字典匹配、关联补充、质量门禁和发布策略，最终生成并发布一张 DWD 表，并能看到运行结果、质量状态、血缘和版本。

闭环路径：

```text
选择 ODS 表
  -> 创建 DWD 治理流水线
  -> 配置目标 DWD 表
  -> 设计字段映射与字段处理
  -> 配置高级治理：字典、关联、脱敏、表达式
  -> 配置质量门禁
  -> 编译 SQL/dbt
  -> 试运行并查看样例结果
  -> 发布 DWD 表
  -> 查看运行、质量、血缘和版本
```

## 信息架构

### 路由

| 路由 | 用途 |
| --- | --- |
| `/orchestration/pipelines` | 流水线列表，包含普通流水线和 DWD 治理流水线入口 |
| `/orchestration/pipelines/new?template=ods-dwd` | DWD 治理流水线创建向导 |
| `/orchestration/pipelines/:id/workbench` | DWD 治理流水线工作台 |
| `/orchestration/pipelines/:id/runs/:runId` | 运行详情 |
| `/orchestration/pipelines/:id/graph` | 技术 DAG 视图，高级入口 |
| `/orchestration/operators` | 算子市场，按适用范围管理能力 |

旧 `/orchestration/pipelines/:id` 可以继续进入通用 `DagCanvas`，但 DWD 模板创建成功后应跳转到 `workbench`。

### 工作台结构

```text
DWD 治理流水线工作台

顶部：
  流水线名称 / 草稿状态 / 校验状态 / 发布状态 / 最近运行

左侧：
  1 源表与目标
  2 治理模型
  3 质量门禁
  4 运行发布
  5 监控血缘

中间：
  当前阶段主工作区

右侧：
  问题清单 / 产物摘要 / 下一步动作

底部：
  保存草稿 / 校验 / 编译 / 试运行 / 发布
```

## 页面设计

### 1. 创建向导

入口：流水线列表的 `新建 DWD 治理流水线`、分层表管理的 `治理成表`。

步骤：

1. 选择源 ODS 表。
2. 确认目标 DWD 表名、业务域、物化方式、分区策略。
3. 选择模板：标准 DWD、主数据治理、宽表构建。
4. 创建流水线草稿并进入工作台。

创建后默认生成一个业务流水线实体和一个 DWD 模型草稿。顶层技术图可由系统生成，不要求用户直接编辑。

### 2. 工作台总览

展示：

1. 源 ODS 表、目标 DWD 表、业务域、负责人。
2. 字段数量、治理字段数量、高级治理规则数量、质量问题数量。
3. 当前状态：草稿、已校验、已编译、试运行成功、已发布。
4. 最近运行状态和发布时间。

主要动作：

1. 继续配置。
2. 校验。
3. 编译。
4. 试运行。
5. 发布。

### 3. 治理模型设计器

治理模型设计器是工作台的核心页面，不再嵌在抽屉里。

布局：

```text
左：字段列表与筛选
中：字段映射与处理 Recipe
右：当前字段处理配置
底：SQL/dbt 预览与问题条
```

字段行包含：

1. 源字段。
2. 输出字段。
3. 类型。
4. 分类分级。
5. 字段处理器。
6. 主键/唯一键。
7. 质量状态。

右侧字段处理配置根据处理器变化：

1. 直通：展示 lineage 和字段类型。
2. 清洗：trim、大小写、空值填充、正则替换。
3. 脱敏/加密：掩码、哈希、AES、FPE。
4. 字典匹配：字典源、未命中策略、命中率门禁。
5. 关联查询：关联表、左右键、补充字段、未命中输出。
6. 表达式：受控 SQL 表达式和只读校验。

### 4. 质量门禁

质量门禁从 DAG 节点参数升级为业务配置页。

门禁组：

1. 主键与非空。
2. 唯一性。
3. 字段范围和正则。
4. 字典命中率。
5. 关联命中率。
6. 敏感字段直通拦截。
7. 行数波动。
8. 自定义只读 SQL 断言。

每个门禁包含：

1. 检查对象。
2. 阈值。
3. 失败策略：阻断、告警、隔离、丢弃。
4. 最近结果。

### 5. 运行发布

运行发布页串联状态机：

```text
草稿 -> 校验通过 -> 编译成功 -> 试运行成功 -> 待发布 -> 已发布
```

页面展示：

1. 编译 SQL/dbt 产物。
2. 试运行样例结果。
3. 运行日志和错误。
4. 发布策略。
5. 回滚点。
6. 下游影响。

发布动作应明确发布对象：发布 DWD 表和对应治理流水线版本。

### 6. 监控血缘

展示：

1. 资产流：ODS 表 -> DWD 治理模型 -> DWD 表 -> 下游。
2. 字段级血缘：输入字段、处理方式、输出字段。
3. 运行历史。
4. 质量趋势。
5. 版本 diff。

技术 DAG 作为只读或高级入口，展示编译后的 operator graph。

## 前端组件拆分

新增目录建议：

```text
src/pages/orchestration/dwd-workbench/
  DwdPipelineCreate.tsx
  DwdPipelineWorkbench.tsx
  components/
    WorkbenchShell.tsx
    StageNavigator.tsx
    PipelineSummaryBar.tsx
    IssuePanel.tsx
    ArtifactPanel.tsx
    SourceTargetStep.tsx
    GovernanceModelDesigner.tsx
    FieldRecipeTable.tsx
    FieldProcessorPanel.tsx
    QualityGateDesigner.tsx
    RunPublishPanel.tsx
    LineageVersionPanel.tsx
    TechnicalGraphView.tsx
  hooks/
    useDwdPipelineDraft.ts
    useGovernanceModel.ts
    useQualityGateDraft.ts
    useRunPublishState.ts
  adapters/
    dwdPipelineAdapter.ts
    operatorScopeAdapter.ts
```

现有 `GovernanceFactory.tsx` 不继续膨胀，应拆成 `GovernanceModelDesigner`、`FieldRecipeTable` 和 `FieldProcessorPanel`。

## 数据流

### 前端状态

工作台维护一个聚合视图模型：

```ts
interface DwdPipelineWorkbenchState {
  pipelineId?: string;
  dagId?: string;
  modelId?: string;
  sourceAssetId?: string;
  sourceFqn?: string;
  targetFqn?: string;
  currentStage: 'SOURCE_TARGET' | 'MODEL' | 'QUALITY' | 'RUN_PUBLISH' | 'OBSERVE';
  modelStatus: 'DRAFT' | 'VALIDATED' | 'PUBLISHED' | string;
  compileStatus: 'NOT_COMPILED' | 'COMPILED' | 'FAILED';
  runStatus?: string;
  publishStatus?: string;
  issues: WorkbenchIssue[];
}
```

### API 复用

1. CatalogAPI：读取 ODS 表和字段。
2. ModelingAPI：创建、更新、校验、编译、运行、发布 DWD 模型。
3. OrchestrationAPI：创建和保存流水线草稿，触发运行。
4. OperatorAPI：读取算子市场、运行契约和图级校验。

### 后端缺口

前端第一版可以通过现有 API 编排完成。后续建议补一个聚合 API：

```text
GET /api/v1/orchestration/dwd-pipelines/{id}/workbench
POST /api/v1/orchestration/dwd-pipelines
PATCH /api/v1/orchestration/dwd-pipelines/{id}/stage
```

但这不是第一轮前端重构的阻塞项。

## 算子市场分层

算子市场需要稳定展示适用范围：

| 适用范围 | 出现位置 |
| --- | --- |
| 编排步骤 | 流水线技术图、通用 DAG |
| 模型 Recipe | 治理模型设计器的表级处理区域 |
| 字段处理器 | 字段行和字段处理面板 |
| 质量断言 | 质量门禁设计器 |
| 复合模板 | 创建向导和模板中心 |

第一版可由前端基于 `category` 和 `manifest.template.kind` 推导。第二版应把 `usageScope` 固化到 operator manifest。

## 错误处理

工作台右侧统一展示问题清单：

1. 缺源表或目标表。
2. 字段映射为空。
3. 主键缺失。
4. 敏感字段直通。
5. 字典配置缺失。
6. 关联查询配置不完整。
7. 质量门禁未配置。
8. 编译失败。
9. 运行失败。
10. 发布阻断。

每个问题必须有定位动作，点击后切换到对应阶段和字段。

## 测试策略

### 单元和类型检查

1. `pnpm --dir onelake-app/web-console exec tsc --noEmit --pretty false`
2. 对 `operatorScopeAdapter`、`dwdPipelineAdapter`、问题清单生成逻辑增加单元测试，如项目测试基建允许。

### 构建

1. `pnpm --dir onelake-app/web-console build`

### 浏览器验证

1. 从 `/orchestration/pipelines` 点击 `新建 DWD 治理流水线`。
2. 创建向导选择 ODS 表后进入工作台。
3. 工作台阶段导航可切换。
4. 字段治理模型可保存并校验。
5. 质量门禁可配置并出现在问题清单中。
6. 编译、试运行、发布动作按状态启停。
7. 技术 DAG 视图可展示生成图，但默认不打断业务流程。
8. 算子市场字段处理器不再出现在顶层编排主路径。

## 分轮实施计划

### 迭代 1：Workbench 壳和路由

1. 新增 `dwd-workbench` 目录和 `DwdPipelineWorkbench`。
2. 新增 `DwdPipelineCreate` 创建向导。
3. 流水线列表和分层表管理入口改为进入向导或工作台。
4. 保留 `DagCanvas` 为技术视图。

验收：用户可以从流水线列表创建 DWD 治理流水线，并看到工作台五阶段结构。

### 迭代 2：治理模型设计器拆分

1. 从 `GovernanceFactory` 拆出字段矩阵和字段处理面板。
2. 在工作台模型阶段全屏展示。
3. 支持字段问题定位和右侧上下文配置。

验收：用户可在模型阶段完成字段映射、字段处理、字典和关联配置。

### 迭代 3：质量门禁设计器

1. 新增质量门禁阶段。
2. 接入主键、非空、唯一性、字典命中率、关联命中率、敏感字段阻断。
3. 问题清单统一展示门禁缺口。

验收：用户能配置门禁，并在校验时看到明确阻断原因。

### 迭代 4：运行发布闭环

1. 新增运行发布阶段。
2. 串联保存、校验、编译、试运行、发布状态。
3. 展示 SQL/dbt 产物、运行结果和发布影响。

验收：用户能从草稿推进到已发布，并知道每一步是否完成。

### 迭代 5：监控血缘和技术视图

1. 新增监控血缘阶段。
2. 展示资产流、字段血缘、运行历史、质量趋势。
3. 技术 DAG 视图作为高级入口。

验收：发布后用户能追踪 DWD 表来源、字段处理方式和运行质量。

## 迁移策略

1. 旧 `/lakehouse/governance-factory` 继续重定向。
2. 旧 `/orchestration/pipelines/new?template=ods-dwd` 进入创建向导或新工作台，不再直接打开 `DagCanvas`。
3. 已有 `GovernanceFactory` 在拆分完成前可以作为模型阶段内部临时组件，但不再以“工厂”命名暴露。
4. 旧 `DagCanvas` 保留给自定义流水线和技术 DAG。

## 验收标准

1. 用户不需要理解 operator graph 也能完成 DWD 治理发布。
2. 顶层不再出现“字段治理矩阵作为流水线节点”的主体验。
3. 字段处理器只在治理模型设计器出现。
4. 质量门禁有独立配置阶段。
5. 发布对象清晰：DWD 表、模型版本和流水线版本。
6. 技术 DAG 可见但不主导主流程。

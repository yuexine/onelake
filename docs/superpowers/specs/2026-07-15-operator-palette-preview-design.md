# 前端算子拖拽与 SQL 预览设计

日期：2026-07-15

范围：`onelake-app/web-console` 统一流水线编辑器

依据：`RTK.md` 第 9 节、`编排模块V2-M4详细任务拆解与提示词.md` 第 9.4 步

## 目标与边界

在当前 `UnifiedPipelineEditor` 三栏工作台内完成 G2/G1 前端闭环：左侧 Palette 展示可执行的已安装算子，拖入画布后通过 9.2 专用命令立即创建标准 `pipeline_task`；选中算子节点后，Inspector 按节点锁定版本的 Manifest 参数 schema 编辑配置，并可调用 9.1 编译预览查看当前节点生成的 Spark SQL。

本轮不改后端契约，不修改旧 `DagCanvas`，不重做编辑器布局，不扩展 G1 支持的模板种类，也不把算子市场浏览能力搬进编辑器。

## 已确认方案

采用 Palette 内嵌方案：在现有任务类型分组之后增加顶层“算子”分组，其内部按 `OperatorCategory` 二级分组。算子拖入画布后立即创建并自动选中新节点，不再打开通用“创建任务”弹窗。点击算子条目也使用同一命令，在画布可视区域放置节点。

没有采用双 Tab，是因为它会改变现有 Palette 信息架构并隐藏另一类节点；没有采用算子抽屉，是因为抽屉会遮挡拖放目标且超出第 9.4 步的最小范围。

## 前端契约

在 `src/types` 增加以下强类型：

- `OperatorCategory`：覆盖后端 Manifest 分类，同时保留未知字符串的兼容显示。
- `OperatorParamSchema`、`OperatorParamPropertySchema`：描述 `object/properties/required` 及常用字段约束。
- `OperatorTaskCreateRequest`：`operatorRef`、精确 `version`、必填 `position{x,y}`。
- Palette 加载与权限错误继续复用统一 API 错误模型，不另造响应包装。

在 `src/api` 增加 `OperatorPaletteAPI`：

- `listInstalled()` → `GET /orchestration/operators/installed`。
- `createTaskFromOperator(dagId, request)` → `POST /orchestration/pipelines/{dagId}/tasks/from-operator`。

编译预览继续复用已经存在且类型完整的 `PipelineAPI.compilePreview(dagId)`，避免为同一路径建立第二个门面。

## 组件与数据流

### Palette

`TaskPalette` 接收已安装算子集合、加载状态、错误与重试回调，并输出 `onAddOperator`。任务类型继续使用原有 MIME；算子拖拽使用独立的 `application/x-onelake-operator` 数据，内容只包含创建命令所需的 `operatorRef`、锁定版本及展示字段。

Palette 只展示接口返回的已安装/G1 兼容集合，不再在前端用普通市场列表猜测安装状态。分类顺序使用明确的中文元数据映射，未知分类稳定落在“其他算子”。

“算子”分组内提供本地关键词过滤，匹配展示名、`operatorRef` 与标签；过滤结果为空时显示独立的“未找到匹配算子”，不与“尚未安装算子”混淆。

### 画布与创建命令

`DagCanvasSimple` 增加算子 drop 分支并把画布坐标回传给 shell。`usePipelineEditor` 封装 `createTaskFromOperator`：调用专用 API，成功后把返回节点合入任务集合、选中新 `taskKey` 并失效当前校验结果；失败沿用现有消息提示和保存中状态。

点击算子条目与拖入共用同一函数，仅坐标来源不同。创建接口由后端负责默认参数、端口、`operator_ref/version` 和 `_operator_contract`，前端不复制 Manifest 到节点的映射规则。

### Inspector 参数表单

算子节点按 `operatorRef + operatorVersion` 解析精确 Manifest。优先复用已安装列表中版本完全匹配的 Manifest；若节点锁定的是其他历史版本，则通过已有算子详情与 `versions` 查找精确版本。找不到精确版本时只展示错误态，不回退 latest。

新增算子参数区，按 schema 渲染：

- `string`：`Input`；存在 `enum` 时使用 `Select`；多行/SQL 提示使用 `Input.TextArea`。
- `number/integer`：`InputNumber`。
- `boolean`：`Switch`。
- `array`：简单标量数组使用 `Select mode=tags`。
- `object` 或无法安全表达的复杂字段：JSON 文本区，解析成功后才写回。

必填、description、default、enum 和基础数值约束进入表单提示；`_operator_contract` 只读保留，参数变更只更新普通 config 字段。保存仍走现有 `updateTask`，版本绑定保持不变。

### SQL 预览

算子参数区提供“预览生成 SQL”。按钮先保存当前草稿；保存成功后调用 `PipelineAPI.compilePreview`。复用现有编译预览 Modal，但从 Inspector 进入时把当前 `taskKey` 对应节点置顶并明确标识；仍保留全图错误和其他节点结果，避免把图级编译失败误报成当前节点失败。

## 状态与权限

- loading：算子分组内使用现有 `StateView` 加载态；不阻断普通任务 Palette。
- empty：提示先到算子市场安装或发布算子，并保留普通任务拖拽。
- error：显示可读错误和“重试”，不静默退回市场全量列表。
- no permission：识别 401/403，显示无权限说明；创建和参数保存按钮禁用。
- disabled/saving：已有节点保存或算子创建期间禁用重复拖放，保持视觉反馈。
- Manifest error：精确版本缺失或 schema 不可解析时，Inspector 显示错误，不允许把 latest 参数写进锁定节点。

## 验证

静态与生产构建：

```bash
cd onelake-app/web-console
pnpm exec tsc --noEmit
pnpm build
```

浏览器自查统一编辑器主路由，至少覆盖：算子 loading/empty/error/无权限展示、分类与搜索、拖入立即创建、自动选中、schema 字段修改保存、当前节点 SQL 预览，以及普通任务创建/拖拽不回归。

若仓库已有可复用的前端测试框架，则补纯函数/组件测试；本轮验收门槛仍以任务卡指定的 TypeScript、build 和浏览器实证为准。

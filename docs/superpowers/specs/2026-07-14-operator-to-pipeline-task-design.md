# G2 算子拖入生成可执行节点设计

## 1. 目标与范围

G2 为统一流水线编辑器提供后端命令边界：Palette 查询当前租户可用的已安装算子，用户把算子拖入画布后，后端根据锁定版本的 Manifest 创建标准 `pipeline_task`。生成的节点继续使用 G1 的 `OperatorSqlGenerator` 和 `PipelineCompileService` 编译为 Spark SQL，不新增并行执行模型。

本阶段只实现后端服务、REST 契约和单元测试。前端 Palette 拖拽、动态参数表单和 SQL 预览交互属于 9.4，不在本阶段修改。

## 2. 现有能力与约束

- `OperatorService#getManifest(tenantId, operatorRef, version)` 已按租户可见性和精确版本返回不可变 Manifest。
- `PipelineTask` 已持久化 `category`、`operatorRef`、`operatorVersion`、`config` 和画布位置，无需数据库迁移。
- G1 只允许 `taskType=SPARK_SQL`、`compileTarget=SPARK`，并支持 `SELECT_EXPR`、`COLUMN_EXPR`、`FILTER`、`SPARK_SQL`、`SPARK_SINK`、`RAW_SQL` 六类模板。
- 节点创建后保持 `DRAFT`/不可执行；调用现有 validate 或 compile-preview 后，由编译流程生成 SQL 并更新编译状态和可执行标记。
- 已发布流水线继续编辑时必须通过现有 `markUnpublishedChanges` 标记未发布变更。

## 3. API 设计

### 3.1 查询 Palette 已安装算子

```http
GET /api/v1/orchestration/operators/installed
```

返回现有 `OperatorDTO` 列表，不引入重复投影。列表只包含 `ACTIVE` 算子，集合由以下来源合并并按算子 ID 去重：

1. 平台 `BUILTIN` 算子，视为默认安装；
2. 当前租户拥有的 `CUSTOM`、`TENANT_PRIVATE` 算子，视为租户内可直接使用；
3. 当前租户通过 `OperatorInstall` 显式安装的可见算子。

未安装的其他租户算子和 `DEPRECATED` 算子不进入 Palette。返回值保留 `latestVersion`、`pinnedVersion` 和当前 Manifest；调用方应优先选择 pinnedVersion，否则使用 latestVersion，并在创建命令中显式提交最终版本。

### 3.2 从算子创建流水线节点

```http
POST /api/v1/orchestration/pipelines/{dagId}/tasks/from-operator
Content-Type: application/json

{
  "operatorRef": "transform.select_columns",
  "version": "1.0.0",
  "position": {
    "x": 320,
    "y": 240
  }
}
```

接口需要 `DE` 角色。请求使用专用 DTO，不复用通用 `PipelineTaskRequest`，避免客户端覆盖服务端依据 Manifest 推导的任务类型、分类、引擎和默认配置。

## 4. 服务边界

### 4.1 OperatorService

新增以下只读能力：

- `listInstalledOperators()`：实现 Palette 集合语义；
- `isInstalled(operatorRef)` 或等价的内部查询：供创建命令在写入前再次校验安装状态。

精确版本解析继续复用 `getManifest`。列表接口和创建命令共享同一安装判定，避免“Palette 可见但拖入失败”或“未安装算子可绕过 Palette 创建”的漂移。

### 4.2 PipelineService

新增：

```java
createTaskFromOperator(dagId, operatorRef, version, position)
```

该方法在单一事务中完成：

1. 按当前租户锁定 DAG；
2. 校验算子已安装且状态为 `ACTIVE`；
3. 按精确版本读取 Manifest；
4. 校验 G1 Spark 模板支持范围；
5. 推导节点字段和默认配置；
6. 保存 `pipeline_task`；
7. 标记流水线存在未发布变更。

任何一步失败都不写入半成品节点。

## 5. Manifest 到 pipeline_task 的映射

| pipeline_task 字段 | 生成规则 |
| --- | --- |
| `taskKey` | 将 operatorRef 规范化为小写下划线；同一 DAG 冲突时追加 `_2`、`_3`，DAG 行锁保证并发下顺序分配 |
| `taskType` | G1 支持的 Spark Manifest 固定映射为 `SPARK_SQL` |
| `category` | 固定为 `TaskCategory.EXEC`；Manifest 的业务分类保存在端口契约快照中 |
| `engine` | 固定为 `SPARK_SQL` |
| `name` | 使用 Manifest `displayName` |
| `operatorRef` | 保存稳定算子引用 |
| `operatorVersion` | 保存请求中的精确版本，不回退 latest |
| `positionX/Y` | 来自请求 position |
| `targetFqn` | 优先使用默认 config 的 `targetFqn`；没有时生成 `onelake.tmp.<taskKey>` |
| `config` | 默认参数和 `_operator_contract` 快照 |
| `compileStatus` | 使用实体默认值 `DRAFT` |
| `executable` | 使用实体默认值 `false`，编译成功后由现有流程更新 |

## 6. 默认配置与端口快照

默认业务参数按字段合并：

1. 读取 `examples[0].params` 作为兜底；
2. 遍历 `paramsSchema.properties`，存在 `default` 的字段覆盖示例值；
3. 不存在示例值和 default 的字段不伪造值。

config 中增加保留对象：

```json
{
  "columns": ["order_id"],
  "_operator_contract": {
    "category": "TRANSFORM",
    "inputPorts": [
      {"name": "in", "cardinality": "ONE", "accept": "TABLE"}
    ],
    "outputSchema": {"mode": "DERIVE"}
  }
}
```

端口快照随节点锁定版本保存，供后续 9.4 画布渲染与连边使用。G1 SQL 生成器只消费业务参数并忽略 `_operator_contract`；正式编译仍按 `operatorRef/operatorVersion` 读取不可变 Manifest，不信任客户端修改过的快照。

## 7. 编译数据流

节点创建完成后，现有链路保持不变：

1. 用户为需要上游输入的节点建立 `pipeline_task_edge`；
2. `validate` 或 `compile-preview` 调用 `PipelineCompileService`；
3. 编译服务按锁定版本读取 Manifest；
4. `OperatorSqlGenerator` 渲染算子片段；
5. 编译服务结合入边资产和 targetFqn 生成完整 Spark SQL；
6. 正式 validate 更新 `compiled_sql`、编译状态与 `executable`；
7. 运行继续使用标准 pipeline_task 和现有 Dagster/Spark 路径。

输入类内置算子无需入边，依靠示例或 Schema default 生成 source 参数，因此可作为“创建后直接编译”的闭环测试样例。转换类算子在创建后允许保持 DRAFT，连边后再编译。

## 8. 错误处理

创建命令在写入前拒绝以下情况，并通过 `BizException` 返回稳定业务文案：

- DAG 不存在或不属于当前租户；
- operatorRef 为空、算子不可见、未安装或已废弃；
- version 为空、版本不存在或 Manifest 引用与锁定版本不一致；
- Manifest `compileTarget` 不是 `SPARK`；
- templateKind 不属于 G1 六类支持范围；
- position 缺失或 x/y 不完整。

自定义算子的必填参数如果既无 Schema default 也无示例值，不阻止节点创建。后续编译沿用 G1 缺参错误，节点保持 DRAFT，供 9.4 Inspector 补齐参数。

## 9. 测试与验收

### 9.1 OperatorService 单测

- 已安装列表包含 ACTIVE BUILTIN；
- 已安装列表包含当前租户 ACTIVE CUSTOM/TENANT_PRIVATE；
- 已安装列表包含显式安装的可见算子；
- 排除未安装的其他租户算子和 DEPRECATED 算子；
- 创建命令使用的安装判定与列表语义一致。

### 9.2 PipelineService 单测

- 从算子创建节点后字段映射正确；
- Schema default 覆盖首个示例参数，其他字段由示例参数兜底；
- `_operator_contract` 保存 Manifest category、inputPorts、outputSchema；
- taskKey 冲突时生成稳定后缀；
- targetFqn 使用显式默认值或临时表兜底；
- 未安装、错误版本、不支持模板时不调用 taskRepo.save；
- 创建后标记已发布流水线存在未发布变更。

### 9.3 编译闭环单测

- 使用 `input.ods_table` 创建标准节点；
- 调用现有编译链；
- 断言生成完整 Spark SQL、校验通过且节点可执行；
- 断言编译读取锁定版本，而不是 latest。

### 9.4 命令验证

```bash
cd onelake-app
mvn -q -pl module-orchestration -am test
git diff --check
```

## 10. 非目标

- 不实现前端 Palette、拖拽事件或动态 Inspector；
- 不新增数据库字段、表或迁移；
- 不改变 G1 模板渲染规则；
- 不实现算子版本升级和发布快照迁移，该能力属于 G3；
- 不把 Manifest 业务分类扩展成新的 TaskCategory，G2 生成节点仍属于 EXEC。

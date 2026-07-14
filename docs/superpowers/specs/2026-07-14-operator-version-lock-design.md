# 算子版本锁定（G3）设计

## 1. 目标

保证已发布流水线的算子 SQL 生成结果可复现：算子发布新版本后，不得改变已有
`pipeline_version` 的运行结果；只有用户显式把草稿节点切换到新版本并重新发布，
新的流水线快照和后续运行才采用新版本。

## 2. 当前基础

- `pipeline_task` 已持久化 `operator_ref` 与 `operator_version`。
- `PipelineSnapshotService` 已把任务的 `operatorRef/operatorVersion` 写入并从
  `pipeline_version.snapshot` 还原。
- PROD 运行已从 `published_version_id` 对应快照重建任务和边，再调用
  `PipelineCompileService` 的集合编译入口。
- `PipelineCompileService` 已通过
  `OperatorService#getManifest(tenantId, operatorRef, operatorVersion)` 读取精确版本，
  该入口不会回退 `latestVersion`。
- `task_run.operator_version` 已存在，创建运行任务时从本次执行任务复制版本。

G3 本轮不改变这些边界，主要补强发布快照的不变量和跨升级回归覆盖。

## 3. 版本选择语义

节点绑定是显式的 `operatorRef + operatorVersion`，二者必须成对存在。

算子发布 v2 只新增不可变 `operator_version` 并移动 `operator.latest_version` 指针，
不会自动修改任何 `pipeline_task`，也不会改写历史 `pipeline_version.snapshot`。

采用新版本的唯一流程是：

1. 用户把草稿节点的 `operatorVersion` 从 v1 显式更新为 v2；
2. 流水线重新校验并发布；
3. `PipelineSnapshotService` 生成包含 v2 的新快照；
4. 新运行读取新快照并按 v2 Manifest 生成 SQL。

单纯重新点击发布但节点仍锁定 v1，不得静默提升到 latest。若算子安装记录设置了
`pinnedVersion`，节点改绑仍遵循既有 pinnedVersion 约束。

## 4. 发布快照边界

`PipelineSnapshotService` 在规范化 JSON 前校验所有任务：

- 普通节点允许 `operatorRef/operatorVersion` 都为空；
- 算子节点必须同时提供非空 `operatorRef` 和非空 `operatorVersion`；
- 只有一项存在时拒绝生成或发布快照，并返回可定位到 `taskKey` 的业务错误。

通过校验后，快照继续以稳定字段名写入：

```json
{
  "taskKey": "select_customer_columns",
  "operatorRef": "transform.select_columns",
  "operatorVersion": "1.0.0"
}
```

完整 Manifest 不复制进流水线快照。`operator_version` 表保存不可变 Manifest，
流水线快照只保存精确引用，避免产生两个内容事实源。

## 5. 运行与 SQL 生成

PROD 运行继续使用以下数据流：

```text
dag.published_version_id
  -> PipelineSnapshotService.loadExecutionSnapshot
  -> 快照任务 operatorRef/operatorVersion
  -> PipelineCompileService.compile(tasks, edges)
  -> OperatorService.getManifest(ref, exactVersion)
  -> OperatorSqlGenerator
  -> task_run.operator_version
```

运行路径不得调用 latest 解析接口，也不得重新读取实时 `pipeline_task`。DEV 草稿试跑
仍可读取实时任务，但同样要求算子节点携带显式版本。

`task_run.operator_version` 记录本次实际编译任务的版本；普通节点保持为空。本轮不新增
`task_run.operator_ref`，因为 `job_run.pipeline_version_id + task_key` 已可回溯快照中的
算子引用，而需求只要求运行记录固化版本。

## 6. 失败处理

- 发布快照遇到 ref/version 不成对时立即失败，不生成不完整版本。
- 快照引用的精确 Manifest 缺失、内容与 ref/version 不一致或不再可见时，运行编译
  失败并拒绝启动，不允许回退 latest。
- 算子生命周期改为 `DEPRECATED` 不破坏既有锁定节点的读取和复现；新建或改绑仍按
  既有规则要求算子可安装且 ACTIVE。

## 7. 测试设计

### 7.1 快照锁定

在 `PipelineSnapshotServiceTest` 覆盖：

- 规范化快照同时包含 `operatorRef` 和 `operatorVersion`；
- 快照反序列化后仍保留精确版本；
- ref/version 缺失任一项时拒绝生成快照。

### 7.2 升级后旧版本复现

在编译或运行聚焦测试中构造 v1 与 v2 模板：

1. 用锁定 v1 的快照任务生成并记录 v1 SQL；
2. 模拟 `OperatorService` 已发布 v2；
3. 再次编译旧快照，确认仍只请求 `ref@v1` 且生成结果与首次一致；
4. 构造节点已显式切换到 v2 的新快照，确认生成 v2 SQL；
5. 确认创建的 `task_run.operatorVersion` 分别与所用快照版本一致。

测试必须对精确 `getManifest(ref, version)` 调用做断言，防止未来重构回退 latest。

## 8. 验证与文档同步

完成实现后运行：

```bash
cd onelake-app
mvn -q -pl module-orchestration -am test
cd ..
git diff --check
```

同步 `docs/编排模块V2-M4详细任务拆解与提示词.md` 的 9.3 落地状态，并在根目录
`task_plan.md`、`findings.md`、`progress.md` 记录实现文件和测试结果。

## 9. 非目标

- 不自动把草稿节点提升到算子 latest 版本；
- 不在流水线快照中复制完整 Manifest 或预编译 SQL；
- 不修改 OperatorInstall/pinnedVersion 语义；
- 不新增数据库迁移或前端升级交互；
- 不改动 DEV/PROD 之外的运行模式和 Dagster 执行协议。

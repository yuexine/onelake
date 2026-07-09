# ODS 到 DWD 标准闭环当前方案

> 修订日期：2026-07-09
>
> 当前 ODS -> DWD 运行主线已从早期 dbt-on-Trino 方案收敛为 Spark-only pipeline。旧文中 `onelake_dbt_model_run`、`/models/{id}/run`、`DwdModelDagsterClient`、`DwdRunArtifactReader` 等表述不再作为当前代码事实。

## 1. 职责拆分

| 模块 | 职责 |
| --- | --- |
| `module-integration` | ODS 数据进入、运行回写、`integration.table.loaded` |
| `module-modeling` | DWD 模型草稿、字段映射、校验、编译、发布 |
| `module-orchestration` | Spark pipeline、pipeline task/edge、job run、task run、回填、资源画像 |
| `dagster/definitions.py` | `onelake_pipeline_run`，执行 Spark SQL/PySpark/Quality Gate |
| `module-catalog` | DWD 资产、字段 schema、表级/字段级血缘 |
| `module-quality` | DWD 质量规则、运行结果、告警 |

## 2. 当前闭环

```text
ODS 表就绪
  -> Catalog 资产/字段可见
  -> 建模模块创建 DWD 草稿
  -> 校验字段映射、目标表、质量门禁
  -> 编译为 Spark pipeline task
  -> Orchestration 触发 onelake_pipeline_run
  -> Spark 写 DWD Iceberg 表
  -> job_run/task_run 更新
  -> Catalog 写资产/血缘
  -> Quality 记录检查结果/告警
```

## 3. 数据面

| 组件 | 当前用途 |
| --- | --- |
| Spark | 执行 Spark SQL/PySpark/Quality Gate |
| Dagster | 调度/执行 user-code job |
| Trino | 查询验证和 SQL Workbench |
| Iceberg + Hive Metastore + MinIO | 表存储和元数据 |
| dbt | 样例模型、宏、生成产物参考，不是当前运行主闭环 |

## 4. 验收建议

`make ods-dwd-baseline` 与 `make ods-dwd-verify` 只验证样例源表/ODS 基线。完整闭环还需要：

1. 触发控制面 pipeline。
2. 确认 Dagster `onelake_pipeline_run` 成功。
3. 通过 Trino 查询 DWD 结果。
4. 检查 `orchestration.job_run/task_run`。
5. 检查 `catalog.asset/lineage_edge`。
6. 检查 `quality.run_result/alert`。
7. 检查 Outbox 事件发布与消费幂等。

## 5. 剩余项

- DWD 工作台体验继续收敛到业务流程，不直接暴露技术 DAG。
- Spark 任务失败诊断、资源画像、重试和回填需要更多自动化验证。
- dbt 相关旧方案保留为 ADR/参考，不再写入主链路验收。

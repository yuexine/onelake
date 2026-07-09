# DWD 治理流水线工作台设计 ADR

> 修订日期：2026-07-09
>
> 本文保留为 ADR。当前代码主线是 Spark-only pipeline 与统一编辑器；DWD 工作台仍是体验收敛方向，不应被误读为已经完整落地的路由/页面。

## 1. 当前事实

| 项 | 当前状态 |
| --- | --- |
| 运行主线 | Spark-only pipeline |
| 前端主入口 | `/orchestration/pipelines/:id` -> `UnifiedPipelineEditor` |
| 历史 graph 路由 | `/orchestration/pipelines/:id/graph` 重定向 |
| DWD 定义 | `module-modeling` |
| DWD 运行 | `module-orchestration` + Dagster `onelake_pipeline_run` |

## 2. ADR 目标

DWD 治理工作台的目标是让用户按业务流程完成：

```text
选择 ODS 表
-> 创建 DWD 治理流水线
-> 配置目标 DWD 表
-> 字段映射和处理
-> 字典/关联/脱敏/表达式
-> 质量门禁
-> 编译
-> 试运行
-> 发布
-> 查看血缘、质量和版本
```

## 3. 当前未落地项

- `/orchestration/pipelines/:id/workbench` 不是当前真实路由。
- 专用 DWD Workbench 组件目录不是当前主线。
- 字段处理器、字典匹配、关联查询体验仍需结合 Modeling/Glossary/Quality/Security 现状设计。

## 4. 保留价值

该 ADR 继续作为体验设计方向：

1. DWD 主流程应面向业务治理，不直接暴露技术 DAG。
2. 技术图作为高级视图或编译结果视图。
3. 字段级治理、质量门禁、发布回滚应集中在同一工作台。

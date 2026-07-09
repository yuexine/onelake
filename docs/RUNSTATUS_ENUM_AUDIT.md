# RunStatus 枚举口径审计

> 修订日期：2026-07-09
>
> 本文由旧 C8 迁移清单收敛为当前口径。历史 `SUCCESS` 已不应作为新代码主状态；消费方需兼容旧事件并 normalize 为 `SUCCEEDED`。

## 1. 当前统一状态

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELLED
UPSTREAM_FAILED
SKIPPED
```

Integration、Orchestration、Analytics、Quality 等运行态文档统一使用以上词。中文展示可映射为：

| 状态 | 中文 |
| --- | --- |
| `QUEUED` | 排队中 |
| `RUNNING` | 运行中 |
| `SUCCEEDED` | 已成功 |
| `FAILED` | 已失败 |
| `CANCELLED` | 已取消 |
| `UPSTREAM_FAILED` | 上游失败 |
| `SKIPPED` | 已跳过 |

## 2. 历史兼容

旧事件或旧数据中可能存在：

```text
SUCCESS
```

处理规则：

```text
SUCCESS -> SUCCEEDED
```

不得在新文档、新 API 示例或新迁移中继续写 `SUCCESS` 作为主状态。

## 3. 审计建议

```bash
rg -n "'SUCCESS'|\"SUCCESS\"|SUCCESS" onelake-app docs
```

命中后判断：

- 如果是历史兼容或注释，保留并标明 normalize。
- 如果是新逻辑、示例数据、文档主状态，改为 `SUCCEEDED`。

## 4. 验收

1. 前端 `StatusBadge` 可展示统一状态。
2. 后端事件消费方兼容旧 `SUCCESS`。
3. 数据库迁移或 seed 不再新增 `SUCCESS`。
4. 文档统一写 `SUCCEEDED`。

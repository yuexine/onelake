# RunStatus 枚举迁移审计清单（C8）

> 来源：流水线模块重设计方案 §7 P0 / §7 P5
> 用途：列出 `SUCCESS / SUCCEEDED / FAILED / RUNNING / QUEUED / CANCELLED` 字符串在代码库的所有引用，为 P5 枚举迁移（`JobRun.SUCCESS → SUCCEEDED`、`modeling.model_run.SUCCESS → SUCCEEDED`）做准备，避免一刀切破坏前端或 Outbox payload 兼容性。
> 实施结果：流水线主链路已统一使用 `SUCCEEDED / FAILED / RUNNING / QUEUED / CANCELLED / UPSTREAM_FAILED / SKIPPED`；历史 `SUCCESS` 仅作为迁移清理和旧事件兼容输入处理。
> 审计命令：
> ```bash
> grep -rn "SUCCESS\|SUCCEEDED\|FAILED\|RUNNING\|QUEUED\|CANCELLED" \
>   onelake-app/web-console/src/ \
>   onelake-app/module-orchestration/src/ \
>   onelake-app/module-modeling/src/ \
>   onelake-app/bootstrap/src/main/resources/db/migration/ \
>   --include="*.ts" --include="*.tsx" --include="*.java" --include="*.sql"
> ```

## 1. 汇总（按文件）

| 引用数 | 文件 | 范围 | P5 动作 |
|--------|------|------|---------|
| 16 | `module-orchestration/.../OrchestrationService.java` | 状态映射、JDBC 写 model_run | **重写**：删除跨 schema 直写 + 把 `DagStatus.SUCCESS` → `DagStatus.SUCCEEDED` |
| 15 | `module-modeling/.../DwdModelService.java` | `model_run.status` 读写 | **重写**：枚举字段对齐；本模块改事件驱动后部分代码删除 |
| 14 | `module-orchestration/.../OrchestrationServiceTest.java` | 测试断言 | **同步更新** |
| 13 | `web-console/src/pages/lakehouse/SqlWorkbench.tsx` | SQL 工作台状态展示 | **同步更新**（仅展示，改枚举不影响功能） |
| 10 | `web-console/src/pages/integration/FailureDiagnose.tsx` | 失败诊断状态 | **同步更新** |
| 9 | `web-console/src/components/TaskProgressBar.tsx` | 全局任务条 | **同步更新** |
| 9 | `module-modeling/.../DwdModelServiceTest.java` | 测试断言 | **同步更新** |
| 6 | `web-console/src/types/index.ts` | TS 类型定义 | **关键**：枚举源；P0/P1 阶段加 `SUCCEEDED = 'SUCCEEDED'` 别名，P5 删 `SUCCESS` |
| 6 | `web-console/src/mock/l1-integration.ts` | mock 数据 | **同步更新**（迁移到真实 API 后此文件可删） |
| 6 | `bootstrap/.../integration/V4__integration_seed.sql` | 种子数据 `status='SUCCESS'` | **DB UPDATE**：P5 迁移脚本 `UPDATE integration.sync_run SET status='SUCCEEDED' WHERE status='SUCCESS'` |
| 5 | `web-console/src/pages/lakehouse/TableDetail.tsx` | 表详情状态 | **同步更新** |
| 5 | `web-console/src/components/tokens.ts` | 状态徽标色板 | **同步更新** |
| 2 | `web-console/src/pages/orchestration/RunInstances.tsx` | 运行实例页 | **同步更新** |
| 2 | `web-console/src/pages/integration/SyncTaskDetail.tsx` | 同步任务详情 | **同步更新** |
| 1+1+1+1 | 其余 4 个 web-console 文件 | UI 展示 | **同步更新** |
| 1 | `web-console/src/components/StatusBadge.tsx` | 状态徽标组件 | **关键**：所有页面共用 |
| 1 | `web-console/src/pages/orchestration/dwd-workbench/DwdPipelineWorkbench.tsx` | DWD 工作台 | P3 完成后此文件删除 |

**总计：132 处引用，分布在 20 个文件。**

## 2. 关键代码点（P5 迁移时必须改对）

### 2.1 后端枚举定义（源头）

```java
// module-orchestration/.../domain/enums/DagStatus.java（当前）
public enum DagStatus { QUEUED, RUNNING, SUCCESS, FAILED }

// P5 目标
public enum DagStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
```

```java
// module-modeling DataModelRun（需确认）
// 当前 status 字段用 String，实际值 "SUCCESS" / "RUNNING" / ...
// P5 目标：使用统一枚举，或 String 值统一为 "SUCCEEDED"
```

### 2.2 后端状态映射（OrchestrationService.java）

| 行号 | 当前 | P5 目标 |
|------|------|---------|
| L332 | `case SUCCESS -> "SUCCEEDED"` | 整段删除（Dagster 状态字符串映射统一） |
| L379 | `case "SUCCESS", "SUCCEEDED" -> DagStatus.SUCCESS` | `case "SUCCEEDED" -> DagStatus.SUCCEEDED` |
| L387 | `status == DagStatus.SUCCESS \|\| status == DagStatus.FAILED` | `status == DagStatus.SUCCEEDED \|\| status == DagStatus.FAILED` |

### 2.3 数据库迁移（P5 `V<n>__runstatus_unify.sql`）

```sql
-- orchestration.job_run
UPDATE orchestration.job_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
-- modeling.model_run
UPDATE modeling.model_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
-- integration.sync_run（seed 数据中有 SUCCESS 字面量）
UPDATE integration.sync_run SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
-- quality.run_result（若有）
UPDATE quality.run_result SET status = 'SUCCEEDED' WHERE status = 'SUCCESS';
```

### 2.4 前端类型（web-console/src/types/index.ts）

```typescript
// 当前（推测）
type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED';

// P0 阶段（向后兼容别名）
type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

// P5 阶段（删除别名）
type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
```

### 2.5 Outbox payload 版本化（关键）

**问题**：`integration.table.loaded` 当前 payload 含 `"status": "SUCCEEDED"`（见全局事件契约 §7.2），但 `orchestration.job_run` DB 中存的是 `"SUCCESS"`。P5 统一为 `"SUCCEEDED"` 后，历史 payload 与新 payload 字面量不同。

**应对**：
- 消费方（如 catalog、orchestration handler）按 **payload schema 版本** 兼容：
  - `version=1`：`status` ∈ `SUCCESS | FAILED`（历史）
  - `version=2`：`status` ∈ `QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED`（P5 起）
- 消费方对 `status` 字段做 normalize：`status === 'SUCCESS' ? 'SUCCEEDED' : status`。
- 已存在的 `DomainEvents.INTEGRATION_TABLE_LOADED` payload schema 加 `version` 字段。

## 3. 阶段化迁移步骤

| 阶段 | 动作 | 验证 |
|------|------|------|
| **P0（已完成）** | 产出本文档；前端 `types/index.ts` 加 `SUCCEEDED` 别名（向后兼容） | `pnpm build` 通过 |
| **P1** | 新代码路径用 `SUCCEEDED`；DB 迁移只新建表，不动 `JobRun.status` 列值 | 新流水线 task_run 状态值正确 |
| **P2-P4** | 新代码继续用 `SUCCEEDED`；老路径保留 `SUCCESS`；UI 同时识别两者 | `StatusBadge` 同时显示两种值正确 |
| **P5** | DB UPDATE（`SUCCESS → SUCCEEDED`）；删老代码路径；前端 `types/index.ts` 删 `SUCCESS`；Outbox payload 版本化消费方上线 | grep `JobRun.SUCCESS` / `model_run.SUCCESS` = 0 |

## 4. 风险点

- **跨模块枚举漂移**：orchestration 与 modeling 必须同时迁移，否则跨 schema 读写（即使是 Outbox payload）会报错。
- **历史 Outbox 事件重放**：DEAD 队列里可能有 `SUCCESS` payload 的旧事件，消费方 normalize 必须保留。
- **E2E 测试**：Playwright 测试用例可能硬编码 `'SUCCESS'`，需同步更新。
- **外部 API 契约**：若有第三方调用方读 `/api/v1/.../runs/{id}` 拿到 `status` 字段，需要 deprecation notice。

## 5. 验收口径（P5 完成时）

```bash
# 后端无 SUCCESS 枚举
grep -rn "DagStatus.SUCCESS\b\|JobRun.SUCCESS\b" onelake-app/ --include="*.java" | wc -l   # = 0

# 前端无 SUCCESS 字面量（除注释）
grep -rn "'SUCCESS'" onelake-app/web-console/src/ --include="*.ts" --include="*.tsx" | grep -v "// " | wc -l   # = 0

# DB 无 SUCCESS 状态值
psql -c "SELECT count(*) FROM orchestration.job_run WHERE status = 'SUCCESS'"   # = 0
psql -c "SELECT count(*) FROM modeling.model_run WHERE status = 'SUCCESS'"       # = 0

# Outbox payload normalize 单测通过
mvn -pl module-common test -Dtest=EventEnvelopeNormalizeTest
```

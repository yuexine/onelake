# 全局事件契约：Outbox 总线、事件清单与订阅

> 修订日期：2026-07-12
>
> 当前代码已落地 `Outbox -> Redis Stream -> consumer group -> consumed_event` 的可靠域事件通道。事件常量集中在 `module-common/.../DomainEvents.java`。

## 1. 机制

```text
业务事务
  -> 写业务表
  -> 写 common.outbox_event(status=PENDING)
  -> OutboxDispatcher 发布 Redis Stream
  -> status=PUBLISHED 或 DEAD
  -> RedisStreamDomainEventConsumer 消费
  -> common.consumed_event 幂等
  -> DomainEventHandler 处理业务副作用
```

核心表：

- `common.outbox_event`
- `common.consumed_event`

事件命名：`<module>.<entity>.<action>`，例如 `integration.table.loaded`。

## 2. 当前事件常量

| 模块 | 事件 |
| --- | --- |
| Integration | `integration.datasource.created`、`updated`、`deleted`、`health_changed`、`integration.sync_task.created`、`status_changed`、`integration.sync_run.started`、`integration.table.loaded`、`integration.sync.failed`、`integration.schema.drift`、`integration.cdc_task.created` |
| Catalog | `catalog.asset.registered`、`catalog.lineage.updated` |
| Modeling | `modeling.model.published`、`modeling.model.loaded`、`modeling.model.failed`、`modeling.transform.completed`、`modeling.term.*`、`modeling.codebook.*` |
| Quality | `quality.check.completed`、`quality.check.failed` |
| Security | `security.classification.assigned`、`security.pii.detected`、`security.masking_policy.updated`、`security.access.changed`、`security.approval.decided` |
| DataService | `dataservice.api.published`、`dataservice.api.offline`、`dataservice.subscription.approved`、`dataservice.subscription.revoked` |
| Orchestration/Pipeline | `orchestration.job.bound`、`pipeline.published`、`pipeline.rolled_back`、`pipeline.publish-approval.requested`、`pipeline.run.succeeded`、`pipeline.run.failed`、`pipeline.run.sla_missed`、`pipeline.run.timeout`、`pipeline.task.loaded` |
| Analytics | `analytics.dashboard.published`、`analytics.notebook.run-submitted`、`analytics.notebook.run-status-changed`、`analytics.notebook.timeout`、`analytics.notebook.artifact-published`、`analytics.query.slow` |

## 3. 主要发布订阅

| 事件 | 生产者 | 消费者 | 当前状态 |
| --- | --- | --- | --- |
| `integration.sync_task.created` | Integration | Security、DataService | 已实现，字段驱动 PII 和 API 草稿投影 |
| `integration.table.loaded` | Integration | Catalog、Orchestration | 已实现，Catalog 建档/字段/血缘，Orchestration 触发依赖 |
| `integration.sync.failed` | Integration | Catalog、Common alert | 已实现 |
| `integration.schema.drift` | Integration | Modeling/Quality/Catalog 后续治理 | 生产端已实现，消费增强中 |
| `security.pii.detected` | Security | Catalog | 已实现，合并字段级 PII 标签 |
| `quality.check.completed/failed` | Quality | Catalog、Orchestration 质量门 | 已实现；资产更新与对应质检支持乱序汇合，失败抑制下游自动触发 |
| `modeling.model.loaded/failed` | Modeling/Orchestration 相关链路 | Catalog/Quality | 需按 Spark pipeline 当前主线继续校准 |
| `pipeline.task.loaded` | Orchestration | Catalog | 已实现相关 handler |
| `pipeline.publish-approval.requested` | Orchestration | Security | 已实现；按 DAG + snapshotChecksum 复用待审批申请 |
| `security.approval.decided` | Security | Orchestration | 已实现；审批通过且 checksum 仍匹配时发布，拒绝或陈旧审批不发布 |
| `pipeline.published` | Orchestration | Catalog 及兼容消费者 | 已实现；payload 含不可变 `versionId` 与目标资产列表 |
| `pipeline.rolled_back` | Orchestration | 审计及后续消费者 | 生产端已实现；表示历史版本恢复为 DEV 草稿，不改变生产发布指针 |
| `dataservice.api.published/offline` | DataService | Catalog/监控后续扩展 | 事件常量已存在，消费侧按需扩展 |
| `analytics.*` | Analytics | Catalog/通知/监控后续扩展 | 事件常量已存在，需补事件矩阵验证 |

## 4. 载荷规则

所有跨模块事件应包含：

| 字段 | 规则 |
| --- | --- |
| `tenantId` | 必填，和 `TenantContext` 一致 |
| `aggregateId` | 必填，便于幂等和追踪 |
| `version` | 建议必填，便于兼容旧 payload |
| `occurredAt` | 事件发生时间 |
| `payload` | JSON 对象，不直接暴露 JPA Entity |

状态字段统一使用：

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELLED
UPSTREAM_FAILED
SKIPPED
```

消费方对历史 `SUCCESS` 做 normalize：`SUCCESS -> SUCCEEDED`。

### 4.1 M3 版本与审批事件载荷

| 事件 | 必要业务字段 |
| --- | --- |
| `pipeline.publish-approval.requested` | `tenantId`、`targetRef`、`applicantId`、`pipelineName`、`dagVersion`、`snapshotChecksum`、`snapshotBytes`、`taskCount` |
| `security.approval.decided` | `tenantId`、`requestType=PUBLISH`、`targetRef`、`status`、`snapshotChecksum`、`approverId`、`comment` |
| `pipeline.published` | `pipelineId`、`tenantId`、`version`、`versionId`、`pipelineKind`、`publishedBy`、`publishedAt`、`targetFqns`、`taskCount` |
| `pipeline.rolled_back` | `pipelineId`、`tenantId`、`targetVersion`、`targetVersionId`、`targetChecksum`、`publishedVersionId`、`hasUnpublishedChanges=true`、`rolledBackBy`、`rolledBackAt` |

`pipeline.rolled_back` 只描述草稿恢复动作。消费者不得据此切换生产版本；只有后续新的 `pipeline.published` 才代表生产指针变化。

## 5. 新增事件 checklist

1. 在 `DomainEvents` 中新增常量。
2. 生产端在业务事务内调用 `OutboxPublisher.publish`。
3. payload 不依赖外部模块 Entity。
4. 消费端实现 `DomainEventHandler`。
5. 消费逻辑幂等，重复事件不产生重复业务副作用。
6. 文档记录生产者、消费者、端到端验证状态。
7. 补最小单元测试或集成测试。

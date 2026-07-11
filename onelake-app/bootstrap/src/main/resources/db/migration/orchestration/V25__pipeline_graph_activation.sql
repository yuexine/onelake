-- F1：Dagster GRAPH 历史版本按需加载租约
-- 仅让当前发布版本和短期内明确触发的历史版本进入 code location，避免全量历史定义无限增长。

CREATE TABLE IF NOT EXISTS orchestration.pipeline_graph_activation (
  version_id UUID PRIMARY KEY
    REFERENCES orchestration.pipeline_version(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pipeline_graph_activation_expires
  ON orchestration.pipeline_graph_activation (expires_at);

-- V24 无法在纯 SQL 迁移中组装 Java 规范化快照。存量已发布 DAG 必须先明确标记为待重新发布，
-- 避免升级后被误认为已经绑定不可变版本。
UPDATE orchestration.dag
SET has_unpublished_changes = true
WHERE upper(status) = 'PUBLISHED'
  AND published_version_id IS NULL;

-- 调度等待点必须与创建它的不可变版本绑定。升级前的等待记录无法可靠还原来源版本，
-- 因此安全取消，避免用新版本重新解释旧 logical_date。
ALTER TABLE orchestration.pipeline_dependency_wait
  ADD COLUMN IF NOT EXISTS pipeline_version_id UUID
    REFERENCES orchestration.pipeline_version(id) ON DELETE SET NULL;

UPDATE orchestration.pipeline_dependency_wait
SET status = 'CANCELLED',
    last_blockers = '升级后需按发布版本重新生成等待计划点',
    resolved_at = now(),
    updated_at = now()
WHERE status = 'WAITING'
  AND pipeline_version_id IS NULL;

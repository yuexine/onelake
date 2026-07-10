-- M2-C2：跨流水线 / 跨周期依赖。

CREATE TABLE IF NOT EXISTS orchestration.pipeline_dependency (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL,
  downstream_dag_id UUID NOT NULL,
  upstream_dag_id   UUID NOT NULL,
  dependency_type   VARCHAR(16) NOT NULL DEFAULT 'SAME_CYCLE',
  offset_grain      VARCHAR(16),
  offset_n          INT NOT NULL DEFAULT 0,
  enabled           BOOLEAN NOT NULL DEFAULT true,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT fk_pipeline_dep_downstream
    FOREIGN KEY (downstream_dag_id)
    REFERENCES orchestration.dag(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_pipeline_dep_upstream
    FOREIGN KEY (upstream_dag_id)
    REFERENCES orchestration.dag(id)
    ON DELETE CASCADE,
  CONSTRAINT chk_pipeline_dep_not_self
    CHECK (downstream_dag_id <> upstream_dag_id),
  CONSTRAINT chk_pipeline_dep_type
    CHECK (dependency_type IN ('SAME_CYCLE', 'CROSS_CYCLE')),
  CONSTRAINT chk_pipeline_dep_offset
    CHECK (
      (dependency_type = 'SAME_CYCLE' AND offset_grain IS NULL AND offset_n = 0)
      OR
      (dependency_type = 'CROSS_CYCLE' AND offset_grain IN ('HOUR', 'DAY', 'MONTH'))
    )
);

-- PostgreSQL 的普通 UNIQUE 允许多个 NULL；用表达式索引确保同周期依赖也真正唯一。
CREATE UNIQUE INDEX IF NOT EXISTS uq_pipeline_dependency
  ON orchestration.pipeline_dependency (
    downstream_dag_id,
    upstream_dag_id,
    dependency_type,
    COALESCE(offset_grain, ''),
    offset_n
  );

CREATE INDEX IF NOT EXISTS idx_pipeline_dep_downstream
  ON orchestration.pipeline_dependency (downstream_dag_id)
  WHERE enabled;

CREATE INDEX IF NOT EXISTS idx_pipeline_dep_upstream
  ON orchestration.pipeline_dependency (upstream_dag_id);

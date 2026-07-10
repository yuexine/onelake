-- M2-H：三级参数 + 节点间传参。
-- 设计草案原编号为 V18；当前迁移目录的 V18-V21 已被占用，因此顺延到 V22。
CREATE TABLE IF NOT EXISTS orchestration.pipeline_param (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL,
  scope       VARCHAR(16)  NOT NULL,             -- GLOBAL|PIPELINE|TASK
  dag_id      UUID REFERENCES orchestration.dag(id) ON DELETE CASCADE, -- PIPELINE/TASK 用
  task_key    VARCHAR(128),                      -- TASK 用
  param_key   VARCHAR(128) NOT NULL,
  param_value TEXT,
  value_type  VARCHAR(16)  NOT NULL DEFAULT 'STRING', -- STRING|NUMBER|BOOL|EXPR
  description VARCHAR(512),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_param_global
  ON orchestration.pipeline_param (tenant_id, param_key)
  WHERE scope = 'GLOBAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_param_pipeline
  ON orchestration.pipeline_param (dag_id, param_key)
  WHERE scope = 'PIPELINE';

CREATE UNIQUE INDEX IF NOT EXISTS uq_param_task
  ON orchestration.pipeline_param (dag_id, task_key, param_key)
  WHERE scope = 'TASK';

-- 节点间传参：上游输出快照（供下游 config 注入）。
ALTER TABLE orchestration.task_run
  ADD COLUMN IF NOT EXISTS outputs jsonb;

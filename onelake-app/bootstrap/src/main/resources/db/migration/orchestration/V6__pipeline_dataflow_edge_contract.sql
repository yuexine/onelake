-- Stage 108: data-flow edge contract for pipeline_task_edge.
-- Edges now carry the data contract used by downstream task input inference.

ALTER TABLE orchestration.pipeline_task_edge
  ADD COLUMN IF NOT EXISTS source_output VARCHAR(64) DEFAULT 'out',
  ADD COLUMN IF NOT EXISTS target_input VARCHAR(64) DEFAULT 'in',
  ADD COLUMN IF NOT EXISTS asset_fqn VARCHAR(256),
  ADD COLUMN IF NOT EXISTS input_alias VARCHAR(64),
  ADD COLUMN IF NOT EXISTS join_role VARCHAR(32),
  ADD COLUMN IF NOT EXISTS trigger_policy VARCHAR(32) DEFAULT 'ALL_SUCCEEDED',
  ADD COLUMN IF NOT EXISTS freshness_policy VARCHAR(32) DEFAULT 'LATEST';

UPDATE orchestration.pipeline_task_edge
SET source_output = COALESCE(source_output, source_port, 'out'),
    target_input = COALESCE(target_input, target_port, 'in'),
    join_role = COALESCE(join_role, target_port, 'in'),
    trigger_policy = COALESCE(trigger_policy, 'ALL_SUCCEEDED'),
    freshness_policy = COALESCE(freshness_policy, 'LATEST');

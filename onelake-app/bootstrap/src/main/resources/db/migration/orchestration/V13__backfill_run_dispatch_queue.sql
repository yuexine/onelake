-- M1-D2：业务日期回填明细队列与幂等约束

CREATE TABLE IF NOT EXISTS orchestration.backfill_run (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL,
  backfill_id         UUID NOT NULL REFERENCES orchestration.backfill(id) ON DELETE CASCADE,
  dag_id              UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  logical_date        TIMESTAMPTZ NOT NULL,
  data_interval_start TIMESTAMPTZ NOT NULL,
  data_interval_end   TIMESTAMPTZ NOT NULL,
  status              VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  job_run_id          UUID REFERENCES orchestration.job_run(id) ON DELETE SET NULL,
  error_msg           VARCHAR(4000),
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_backfill_run_logical_date UNIQUE (backfill_id, logical_date)
);

CREATE INDEX IF NOT EXISTS idx_backfill_run_backfill_status
    ON orchestration.backfill_run (backfill_id, status, logical_date);
CREATE INDEX IF NOT EXISTS idx_backfill_run_job_run
    ON orchestration.backfill_run (job_run_id) WHERE job_run_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_job_run_backfill_logical_date
    ON orchestration.job_run (backfill_id, logical_date)
    WHERE backfill_id IS NOT NULL AND logical_date IS NOT NULL;

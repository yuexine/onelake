-- M2-C：生产级调度增强（时区/日历/catchup/并发/优先级/空跑冻结/SLA）
-- 关联 docs/数据开发与编排模块V2升级计划.md §4.3（C1/C3/C4/C5）、§5

ALTER TABLE orchestration.dag
  ADD COLUMN IF NOT EXISTS timezone         VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  ADD COLUMN IF NOT EXISTS catchup          BOOLEAN      NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS max_active_runs  INT          NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS priority         INT          NOT NULL DEFAULT 5,
  ADD COLUMN IF NOT EXISTS sla_minutes      INT,
  ADD COLUMN IF NOT EXISTS timeout_minutes  INT,
  ADD COLUMN IF NOT EXISTS schedule_mode    VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',   -- NORMAL|DRY_RUN|FROZEN
  ADD COLUMN IF NOT EXISTS misfire_policy   VARCHAR(16)  NOT NULL DEFAULT 'FIRE_ONCE',-- FIRE_ONCE|SKIP
  ADD COLUMN IF NOT EXISTS calendar_id      UUID,
  ADD COLUMN IF NOT EXISTS schedule_start   TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS schedule_end     TIMESTAMPTZ;

ALTER TABLE orchestration.job_run
  ADD COLUMN IF NOT EXISTS run_mode   VARCHAR(16) NOT NULL DEFAULT 'NORMAL',  -- NORMAL|DRY_RUN
  ADD COLUMN IF NOT EXISTS sla_missed BOOLEAN     NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS priority   INT         NOT NULL DEFAULT 5;

-- 调度日历（工作日/节假日）C3
CREATE TABLE IF NOT EXISTS orchestration.schedule_calendar (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id  UUID NOT NULL,
  name       VARCHAR(128) NOT NULL,
  timezone   VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS orchestration.schedule_calendar_day (
  calendar_id UUID NOT NULL REFERENCES orchestration.schedule_calendar(id) ON DELETE CASCADE,
  day         DATE NOT NULL,
  day_type    VARCHAR(16) NOT NULL,   -- HOLIDAY|WORKDAY
  PRIMARY KEY (calendar_id, day)
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'fk_dag_schedule_calendar'
      AND conrelid = 'orchestration.dag'::regclass
  ) THEN
    ALTER TABLE orchestration.dag
      ADD CONSTRAINT fk_dag_schedule_calendar
      FOREIGN KEY (calendar_id)
      REFERENCES orchestration.schedule_calendar(id)
      ON DELETE SET NULL;
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_calendar_tenant ON orchestration.schedule_calendar (tenant_id);
CREATE INDEX IF NOT EXISTS idx_job_run_active ON orchestration.job_run (dag_id, status);

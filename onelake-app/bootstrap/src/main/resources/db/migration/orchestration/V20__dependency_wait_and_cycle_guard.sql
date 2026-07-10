-- M2-C2 修正：依赖等待计划点持久化 + 传递性成环保护。

-- 阻止 A -> B -> ... -> A。使用数据库触发器覆盖 API、脚本和后台任务等所有写入路径。
CREATE OR REPLACE FUNCTION orchestration.reject_pipeline_dependency_cycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  cycle_found BOOLEAN;
BEGIN
  IF NOT NEW.enabled THEN
    RETURN NEW;
  END IF;

  WITH RECURSIVE upstream_chain(dag_id) AS (
    SELECT NEW.upstream_dag_id
    UNION
    SELECT dependency.upstream_dag_id
    FROM orchestration.pipeline_dependency dependency
    JOIN upstream_chain chain
      ON dependency.downstream_dag_id = chain.dag_id
    WHERE dependency.enabled
      AND dependency.id IS DISTINCT FROM NEW.id
  )
  SELECT EXISTS (
    SELECT 1
    FROM upstream_chain
    WHERE dag_id = NEW.downstream_dag_id
  ) INTO cycle_found;

  IF cycle_found THEN
    RAISE EXCEPTION 'pipeline dependency cycle detected: downstream %, upstream %',
      NEW.downstream_dag_id, NEW.upstream_dag_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_pipeline_dependency_no_cycle
  ON orchestration.pipeline_dependency;
CREATE TRIGGER trg_pipeline_dependency_no_cycle
  BEFORE INSERT OR UPDATE OF downstream_dag_id, upstream_dag_id, enabled
  ON orchestration.pipeline_dependency
  FOR EACH ROW
  EXECUTE FUNCTION orchestration.reject_pipeline_dependency_cycle();

-- 依赖未就绪时持久化原始计划点，后续 scheduler tick 持续重判而不依赖 cron 再次命中。
CREATE TABLE IF NOT EXISTS orchestration.pipeline_dependency_wait (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL,
  dag_id       UUID NOT NULL REFERENCES orchestration.dag(id) ON DELETE CASCADE,
  logical_date TIMESTAMPTZ NOT NULL,
  scheduled_at TIMESTAMPTZ NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pipeline_dependency_wait UNIQUE (dag_id, logical_date)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_dependency_wait_created
  ON orchestration.pipeline_dependency_wait (created_at, id);

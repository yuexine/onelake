-- M2-C2 修正：串行化依赖边写入，避免并发反向边绕过 V20 的成环检查。

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

  PERFORM pg_advisory_xact_lock(hashtextextended('orchestration.pipeline_dependency.cycle', 0));

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

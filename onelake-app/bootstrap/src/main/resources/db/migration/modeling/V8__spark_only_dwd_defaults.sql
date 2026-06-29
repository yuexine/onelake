ALTER TABLE modeling.data_model
  ALTER COLUMN resource_group SET DEFAULT 'spark-default',
  ALTER COLUMN compute_profile SET DEFAULT 'spark-small',
  ALTER COLUMN engine SET DEFAULT 'SPARK';

UPDATE modeling.data_model
SET resource_group = 'spark-default',
    compute_profile = 'spark-small',
    engine = 'SPARK',
    pipeline_mode = CASE
      WHEN pipeline_mode = 'FIELD_GOVERNANCE' THEN 'SPARK_GOVERNANCE'
      ELSE pipeline_mode
    END,
    updated_at = now()
WHERE engine = 'TRINO_DBT'
   OR resource_group IN ('default', 'rg-default')
   OR compute_profile LIKE 'trino-%'
   OR pipeline_mode = 'FIELD_GOVERNANCE';

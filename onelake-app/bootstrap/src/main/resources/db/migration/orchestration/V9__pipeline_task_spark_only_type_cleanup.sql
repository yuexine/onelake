UPDATE orchestration.pipeline_task
SET task_type = 'SPARK_SQL',
    engine = 'SPARK_SQL',
    updated_at = now()
WHERE task_type IN ('SQL_MODEL', 'FIELD_GOVERNANCE');

UPDATE orchestration.pipeline_task
SET engine = CASE
    WHEN task_type = 'PYSPARK' THEN 'PYSPARK'
    ELSE 'SPARK_SQL'
END,
updated_at = now()
WHERE engine IN ('TRINO_DBT', 'SQL_DBT');

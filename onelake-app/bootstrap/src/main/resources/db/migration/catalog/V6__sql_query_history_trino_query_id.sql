ALTER TABLE catalog.sql_query_history
  ADD COLUMN IF NOT EXISTS trino_query_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_sql_query_history_trino_query
  ON catalog.sql_query_history (trino_query_id)
  WHERE trino_query_id IS NOT NULL;

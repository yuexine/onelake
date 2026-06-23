ALTER TABLE modeling.data_model_column_mapping
  ADD COLUMN IF NOT EXISTS term_id UUID,
  ADD COLUMN IF NOT EXISTS term_code VARCHAR(64),
  ADD COLUMN IF NOT EXISTS term_name VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_data_model_mapping_term
  ON modeling.data_model_column_mapping (term_id)
  WHERE term_id IS NOT NULL;

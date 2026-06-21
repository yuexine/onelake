ALTER TABLE catalog.asset
  ADD COLUMN IF NOT EXISTS columns JSONB DEFAULT '[]'::jsonb;

UPDATE catalog.asset
SET columns = '[]'::jsonb
WHERE columns IS NULL;

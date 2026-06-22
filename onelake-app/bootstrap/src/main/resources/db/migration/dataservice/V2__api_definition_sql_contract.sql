ALTER TABLE dataservice.api_definition
    ADD COLUMN IF NOT EXISTS request_params jsonb,
    ADD COLUMN IF NOT EXISTS response_schema jsonb;

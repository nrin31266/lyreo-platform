ALTER TABLE content_ai_request ADD COLUMN accepted_revision BIGINT;
ALTER TABLE content_ai_request ADD COLUMN applied JSONB NOT NULL DEFAULT '{}'::jsonb;

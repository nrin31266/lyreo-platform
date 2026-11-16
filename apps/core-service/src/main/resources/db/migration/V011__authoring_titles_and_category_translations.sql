ALTER TABLE lesson_category ADD COLUMN translations JSONB NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(translations) = 'object');
ALTER TABLE lesson_metadata ADD COLUMN title_automatic BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lesson_audio_source ADD COLUMN original_filename VARCHAR(255);

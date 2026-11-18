ALTER TABLE grammar_subtopic_version ADD COLUMN theory JSONB
    CHECK (theory IS NULL OR jsonb_typeof(theory) = 'object');

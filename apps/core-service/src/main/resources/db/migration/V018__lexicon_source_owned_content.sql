ALTER TABLE lexicon_sense ADD COLUMN definitions jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE lexicon_sense ADD COLUMN source_context jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE lexicon_pronunciation ADD COLUMN pronunciation_context jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE lexicon_sense ADD CONSTRAINT lexicon_definitions_object CHECK (jsonb_typeof(definitions)='object');
ALTER TABLE lexicon_pronunciation ADD CONSTRAINT lexicon_pronunciation_context_object CHECK (jsonb_typeof(pronunciation_context)='object');

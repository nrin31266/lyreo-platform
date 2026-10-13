-- Preserve source POS independently of the inferred lexical target. Occurrence context
-- is internal provenance; neither column is backfilled on immutable legacy releases.
ALTER TABLE lexicon_translation
    ADD COLUMN source_pos TEXT,
    ADD COLUMN source_context JSONB,
    ADD CONSTRAINT lexicon_translation_source_context_object
        CHECK (source_context IS NULL OR jsonb_typeof(source_context) = 'object'),
    ADD CONSTRAINT lexicon_translation_item_candidate_scope
        CHECK (link_status <> 'ITEM_CANDIDATE' OR (item_id IS NOT NULL AND sense_id IS NULL)),
    ADD CONSTRAINT lexicon_translation_entry_candidate_scope
        CHECK (link_status <> 'ENTRY_CANDIDATE' OR (item_id IS NULL AND sense_id IS NULL));

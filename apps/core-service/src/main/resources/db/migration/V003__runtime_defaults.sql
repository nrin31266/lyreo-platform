-- Safe product defaults. Lesson creator can request a subset/choice only inside this policy.
-- The owning module defines typed schema/defaults in code; this table stores admin-tunable policy documents.
INSERT INTO module_runtime_config(owner_module, config_key, schema_version, config_json)
VALUES (
    'lesson',
    'processing-policy',
    1,
    '{
      "allowedActivities": ["DICTATION", "SHADOWING", "VOCABULARY_PRACTICE", "GRAMMAR_PRACTICE"],
      "allowedAnnotations": ["TRANSLATION", "LEXICAL", "GRAMMAR", "ENTITY_HINTS", "DICTATION_HINTS", "SENTENCE_IPA", "THOUGHT_GROUPS", "LEARNING_TIPS"],
      "allowedPronunciationStrategies": ["DISABLED", "ON_DEMAND", "PREGENERATE"],
      "allowedAccents": ["US", "UK"]
    }'::jsonb
)
ON CONFLICT(owner_module, config_key) DO NOTHING;

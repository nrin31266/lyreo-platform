-- Admin-tunable runtime policy documents.
-- The owning module still defines a typed schema/defaults in code; this table is not a free-form domain database.
CREATE TABLE module_runtime_config (
    owner_module VARCHAR(100) NOT NULL,
    config_key VARCHAR(100) NOT NULL,
    schema_version INT NOT NULL CHECK(schema_version >= 1),
    config_json JSONB NOT NULL,
    updated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_module, config_key)
);

-- Safe product defaults. Lesson creator can request a subset/choice only inside this policy.
INSERT INTO module_runtime_config(owner_module,config_key,schema_version,config_json)
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
ON CONFLICT(owner_module,config_key) DO NOTHING;

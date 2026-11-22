-- Small stable reference seed belongs in Flyway. Large datasets do not.
-- Initial reference catalog baseline.

-- Grammar difficulty levels (foundation taxonomy)
INSERT INTO grammar_difficulty_level(level, name_vi, description_vi) VALUES
    (1, 'Rất dễ', 'Mức nền tảng'),
    (2, 'Dễ', 'Câu hỏi cơ bản'),
    (3, 'Trung bình', 'Mức trung bình'),
    (4, 'Khó', 'Yêu cầu phân biệt cấu trúc tốt'),
    (5, 'Rất khó', 'Mức nâng cao')
ON CONFLICT (level) DO NOTHING;

-- Default mission catalog. Admin can disable/edit missions later; learner progress is never seeded here.
INSERT INTO mission_definition(id, code, title, metric, target, diamond_reward, recurrence, enabled) VALUES
    ('10000000-0000-0000-0000-000000000001', 'DAILY_LESSON', 'Hoàn thành 1 bài học', 'LESSON_COMPLETED', 1, 3, 'DAILY', true),
    ('10000000-0000-0000-0000-000000000002', 'DAILY_VOCAB_10', 'Ôn 10 từ vựng', 'VOCABULARY_REVIEW', 10, 2, 'DAILY', true),
    ('10000000-0000-0000-0000-000000000003', 'DAILY_TOEIC', 'Hoàn thành 1 TOEIC drill', 'TOEIC_DRILL_COMPLETED', 1, 4, 'DAILY', true),
    ('10000000-0000-0000-0000-000000000004', 'DAILY_GRAMMAR_5', 'Làm 5 câu ngữ pháp', 'GRAMMAR_ANSWER', 5, 2, 'DAILY', true)
ON CONFLICT (code) DO NOTHING;

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

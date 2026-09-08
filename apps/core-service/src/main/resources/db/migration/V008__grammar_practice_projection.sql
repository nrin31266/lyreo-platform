-- Grammar practice already existed as imported reference data + attempts. This migration adds
-- query indexes for the real practice API and a small mission default. Large question data remains
-- imported by tools/data-import, never by Flyway.
CREATE INDEX IF NOT EXISTS grammar_question_topic_practice_idx
    ON grammar_question(topic_id, subtopic_id, difficulty_level, source_question_number);

CREATE INDEX IF NOT EXISTS grammar_question_difficulty_idx
    ON grammar_question(difficulty_level, source_question_number);

INSERT INTO mission_definition(
    id, code, title, metric, target, diamond_reward, recurrence, enabled
) VALUES (
    '10000000-0000-0000-0000-000000000004',
    'DAILY_GRAMMAR_5',
    'Làm 5 câu ngữ pháp',
    'GRAMMAR_ANSWER',
    5,
    2,
    'DAILY',
    true
)
ON CONFLICT (code) DO NOTHING;

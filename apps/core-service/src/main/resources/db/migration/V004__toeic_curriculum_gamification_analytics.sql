CREATE TABLE toeic_mock_test (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    test_year INT,
    test_name TEXT NOT NULL,
    source_url TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE toeic_passage (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    test_id UUID NOT NULL REFERENCES toeic_mock_test(id) ON DELETE CASCADE,
    part INT,
    content TEXT,
    position INT,
    image_object_key TEXT,
    audio_object_key TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE toeic_question (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    test_id UUID NOT NULL REFERENCES toeic_mock_test(id) ON DELETE CASCADE,
    passage_id UUID REFERENCES toeic_passage(id) ON DELETE SET NULL,
    part INT,
    question_number INT,
    question_text TEXT,
    option_a TEXT,
    option_b TEXT,
    option_c TEXT,
    option_d TEXT,
    correct_answer VARCHAR(8),
    difficulty_level INT,
    audio_object_key TEXT,
    image_object_key TEXT,
    explanation_vi TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(test_id, question_number)
);

CREATE TABLE toeic_attempt (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    test_id UUID REFERENCES toeic_mock_test(id),
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    raw_listening_correct INT,
    raw_reading_correct INT,
    listening_score INT,
    reading_score INT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ
);

CREATE TABLE toeic_attempt_answer (
    attempt_id UUID NOT NULL REFERENCES toeic_attempt(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES toeic_question(id),
    answer VARCHAR(8),
    correct BOOLEAN,
    answered_at TIMESTAMPTZ,
    PRIMARY KEY(attempt_id, question_id)
);

CREATE TABLE speech_attempt (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    lesson_id UUID REFERENCES lesson(id) ON DELETE SET NULL,
    sentence_id UUID REFERENCES lesson_sentence(id) ON DELETE SET NULL,
    recording_object_key TEXT NOT NULL,
    asr_text TEXT,
    word_accuracy INT CHECK(word_accuracy BETWEEN 0 AND 100),
    timing_score INT CHECK(timing_score BETWEEN 0 AND 100),
    fluency_score INT CHECK(fluency_score BETWEEN 0 AND 100),
    deep_judge_score INT CHECK(deep_judge_score BETWEEN 0 AND 100),
    raw_result_object_key TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE curriculum_path (
    id UUID PRIMARY KEY,
    code VARCHAR(100) UNIQUE NOT NULL,
    name TEXT NOT NULL,
    level VARCHAR(32),
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE curriculum_section (
    id UUID PRIMARY KEY,
    path_id UUID NOT NULL REFERENCES curriculum_path(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    position INT NOT NULL,
    UNIQUE(path_id, position)
);

CREATE TABLE curriculum_item (
    id UUID PRIMARY KEY,
    section_id UUID NOT NULL REFERENCES curriculum_section(id) ON DELETE CASCADE,
    position INT NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    content_reference_id UUID NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    unlock_rule TEXT,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(section_id, position)
);
CREATE INDEX curriculum_item_ref_idx ON curriculum_item(content_type, content_reference_id);

CREATE TABLE curriculum_enrollment (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    path_id UUID NOT NULL REFERENCES curriculum_path(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY(learner_id, path_id)
);

CREATE TABLE curriculum_item_progress (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES curriculum_item(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'LOCKED',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY(learner_id, item_id)
);

CREATE TABLE learner_level (
    learner_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    xp BIGINT NOT NULL DEFAULT 0 CHECK(xp >= 0),
    level INT NOT NULL DEFAULT 1 CHECK(level >= 1),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE diamond_wallet (
    learner_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    cached_balance INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE diamond_transaction (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    transaction_type VARCHAR(64) NOT NULL,
    amount INT NOT NULL CHECK(amount <> 0),
    idempotency_key TEXT NOT NULL UNIQUE,
    reference_type VARCHAR(64),
    reference_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX diamond_tx_learner_idx ON diamond_transaction(learner_id, created_at DESC);

CREATE TABLE mission_definition (
    id UUID PRIMARY KEY,
    code VARCHAR(100) UNIQUE NOT NULL,
    title TEXT NOT NULL,
    metric VARCHAR(64) NOT NULL,
    target INT NOT NULL CHECK(target > 0),
    diamond_reward INT NOT NULL DEFAULT 0 CHECK(diamond_reward >= 0),
    recurrence VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE mission_progress (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    mission_id UUID NOT NULL REFERENCES mission_definition(id) ON DELETE CASCADE,
    period_key VARCHAR(64) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    PRIMARY KEY(learner_id, mission_id, period_key)
);

CREATE TABLE learner_daily_activity (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    activity_date DATE NOT NULL,
    study_seconds BIGINT NOT NULL DEFAULT 0,
    lesson_count INT NOT NULL DEFAULT 0,
    vocabulary_review_count INT NOT NULL DEFAULT 0,
    grammar_answer_count INT NOT NULL DEFAULT 0,
    toeic_question_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY(learner_id, activity_date)
);

CREATE TABLE learner_skill_summary (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    skill_code VARCHAR(64) NOT NULL,
    score NUMERIC(6,2),
    sample_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(learner_id, skill_code)
);

CREATE TABLE learner_weakness (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    weakness_type VARCHAR(64) NOT NULL,
    weakness_key TEXT NOT NULL,
    severity NUMERIC(6,2) NOT NULL,
    sample_count INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(learner_id, weakness_type, weakness_key)
);

CREATE TABLE chat_conversation (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES chat_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK(role IN ('USER','ASSISTANT','SYSTEM')),
    content TEXT NOT NULL,
    ai_invocation_id UUID REFERENCES ai_invocation(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

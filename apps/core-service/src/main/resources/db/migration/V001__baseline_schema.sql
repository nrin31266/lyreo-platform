-- Lyreo baseline schema. Flyway is the only schema owner.
-- Consolidated initial baseline (V001-V008 history consolidated).
-- Future evolution must append V004__..., never edit baseline migrations.

-- ============================================================
-- 1. PLATFORM & MODULITH INFRASTRUCTURE
-- ============================================================

-- Spring Modulith 2.1.x JDBC Event Publication Registry, PostgreSQL schema.
CREATE TABLE event_publication (
    id UUID NOT NULL PRIMARY KEY,
    listener_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date TIMESTAMPTZ,
    status TEXT,
    completion_attempts INT,
    last_resubmission_date TIMESTAMPTZ
);
CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication(completion_date);

-- Identity mapping
CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    keycloak_subject TEXT NOT NULL UNIQUE,
    email_snapshot TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

-- Learner profile
CREATE TABLE learner_profile (
    learner_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    display_name TEXT,
    current_level VARCHAR(32),
    goal VARCHAR(128),
    daily_minutes INT CHECK (daily_minutes IS NULL OR daily_minutes BETWEEN 5 AND 240),
    focus_area VARCHAR(128),
    preferences_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    onboarding_completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- PostgreSQL-backed background job queue
CREATE TABLE background_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(100) NOT NULL,
    owner_module VARCHAR(100) NOT NULL,
    owner_reference_id UUID,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED','CANCELLED','SUCCEEDED','FAILED')),
    priority INT NOT NULL DEFAULT 0,
    current_step VARCHAR(100),
    progress_percent INT NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    cancel_requested_at TIMESTAMPTZ,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    next_retry_at TIMESTAMPTZ,
    lease_owner TEXT,
    lease_until TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    error_message TEXT,
    config_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX background_job_claim_idx
    ON background_job(status, next_retry_at, priority DESC, created_at);
CREATE INDEX background_job_owner_idx
    ON background_job(owner_module, owner_reference_id);
CREATE INDEX background_job_lease_idx
    ON background_job(status, lease_until);

-- Data license & attribution
CREATE TABLE data_license (
    id UUID PRIMARY KEY,
    spdx_code VARCHAR(100),
    name TEXT NOT NULL,
    url TEXT
);

CREATE TABLE data_source (
    id UUID PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    name TEXT NOT NULL,
    url TEXT,
    license_id UUID REFERENCES data_license(id),
    attribution_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Dataset import tracking
CREATE TABLE dataset_import (
    id UUID PRIMARY KEY,
    dataset_code VARCHAR(100) NOT NULL,
    dataset_version VARCHAR(100),
    source_path TEXT,
    checksum VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK(status IN ('PLANNED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    records_imported BIGINT NOT NULL DEFAULT 0,
    media_imported BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(dataset_code, checksum)
);

-- Module runtime configuration
CREATE TABLE module_runtime_config (
    owner_module VARCHAR(100) NOT NULL,
    config_key VARCHAR(100) NOT NULL,
    schema_version INT NOT NULL CHECK(schema_version >= 1),
    config_json JSONB NOT NULL,
    updated_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(owner_module, config_key)
);

-- ============================================================
-- 2. AI MODULE
-- ============================================================

CREATE TABLE ai_provider (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    base_url TEXT,
    encrypted_api_key TEXT,
    key_last4 VARCHAR(8),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    connection_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    last_tested_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai_capability_route (
    id UUID PRIMARY KEY,
    capability VARCHAR(64) NOT NULL,
    provider_id UUID NOT NULL REFERENCES ai_provider(id),
    model TEXT NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    is_fallback BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(capability, provider_id, model)
);
CREATE INDEX ai_capability_route_lookup_idx
    ON ai_capability_route(capability, enabled, priority);

CREATE TABLE ai_invocation (
    id UUID PRIMARY KEY,
    capability VARCHAR(64) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    model TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    latency_ms BIGINT,
    input_tokens INT,
    output_tokens INT,
    estimated_cost NUMERIC(18,8),
    request_hash VARCHAR(128),
    raw_request_artifact_key TEXT,
    raw_response_artifact_key TEXT,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ai_invocation_time_idx ON ai_invocation(started_at DESC);
CREATE INDEX ai_invocation_capability_idx ON ai_invocation(capability, provider_code, model);

-- ============================================================
-- 3. LESSON MODULE
-- ============================================================

CREATE TABLE lesson (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL CHECK (source_type IN ('TEXT','AUDIO','YOUTUBE')),
    source_text TEXT,
    source_reference TEXT,
    canonical_audio_object_key TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE lesson_sentence (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    position INT NOT NULL,
    text TEXT NOT NULL,
    audio_start_ms BIGINT,
    audio_end_ms BIGINT,
    audio_clip_object_key TEXT,
    UNIQUE(lesson_id, position),
    CHECK (audio_start_ms IS NULL OR audio_start_ms >= 0),
    CHECK (audio_end_ms IS NULL OR audio_end_ms >= audio_start_ms)
);

CREATE TABLE lesson_word_timestamp (
    id UUID PRIMARY KEY,
    sentence_id UUID NOT NULL REFERENCES lesson_sentence(id) ON DELETE CASCADE,
    position INT NOT NULL,
    surface_text TEXT NOT NULL,
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    end_ms BIGINT NOT NULL CHECK (end_ms >= start_ms),
    UNIQUE(sentence_id, position)
);

CREATE TABLE lesson_annotation (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    sentence_id UUID REFERENCES lesson_sentence(id) ON DELETE CASCADE,
    annotation_type VARCHAR(64) NOT NULL,
    start_char INT,
    end_char INT,
    payload_json JSONB NOT NULL,
    generated_by VARCHAR(64),
    provider VARCHAR(64),
    model TEXT,
    text_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lesson_annotation_sentence_idx
    ON lesson_annotation(sentence_id, annotation_type);

CREATE TABLE lesson_activity (
    id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    activity_type VARCHAR(64) NOT NULL,
    position INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(lesson_id, activity_type)
);

CREATE TABLE lesson_build_job (
    job_id UUID PRIMARY KEY REFERENCES background_job(id) ON DELETE CASCADE,
    lesson_id UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    build_plan_json JSONB NOT NULL,
    provider_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE lesson_build_job_step (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES lesson_build_job(job_id) ON DELETE CASCADE,
    step VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    ai_invocation_id UUID REFERENCES ai_invocation(id),
    output_artifact_key TEXT,
    error_message TEXT,
    UNIQUE(job_id, step)
);

CREATE TABLE lesson_progress (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    lesson_id UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ,
    PRIMARY KEY(learner_id, lesson_id)
);

CREATE TABLE lesson_practice_attempt (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    lesson_id UUID NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    activity_id UUID NOT NULL REFERENCES lesson_activity(id) ON DELETE CASCADE,
    sentence_id UUID REFERENCES lesson_sentence(id) ON DELETE SET NULL,
    activity_type VARCHAR(64) NOT NULL,
    server_score INT NOT NULL CHECK(server_score BETWEEN 0 AND 100),
    answer_text TEXT,
    detail_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lesson_attempt_learner_idx
    ON lesson_practice_attempt(learner_id, created_at DESC);

CREATE TABLE sentence_pronunciation (
    id UUID PRIMARY KEY,
    sentence_id UUID NOT NULL REFERENCES lesson_sentence(id) ON DELETE CASCADE,
    accent VARCHAR(16) NOT NULL,
    ipa TEXT NOT NULL,
    provider VARCHAR(64),
    model TEXT,
    text_hash VARCHAR(128) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(sentence_id, accent, text_hash)
);

-- Per-activity learner progress projection (from V007)
CREATE TABLE lesson_activity_progress (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    activity_id UUID NOT NULL REFERENCES lesson_activity(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED')),
    completed_items INT NOT NULL DEFAULT 0 CHECK (completed_items >= 0),
    total_items INT NOT NULL DEFAULT 0 CHECK (total_items >= 0),
    best_score INT CHECK (best_score BETWEEN 0 AND 100),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    last_practiced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(learner_id, activity_id)
);
CREATE INDEX lesson_activity_progress_activity_idx
    ON lesson_activity_progress(activity_id, status);

-- ============================================================
-- 4. LEXICON & VOCABULARY MODULES
-- ============================================================

CREATE TABLE lexicon_entry (
    id UUID PRIMARY KEY,
    canonical_form TEXT NOT NULL,
    normalized_form TEXT NOT NULL,
    entry_type VARCHAR(32) NOT NULL
        CHECK(entry_type IN ('WORD','PHRASE','PHRASAL_VERB','IDIOM','COLLOCATION')),
    language VARCHAR(16) NOT NULL DEFAULT 'en',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    relevance_score NUMERIC(10,4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,
    UNIQUE(language, normalized_form, entry_type)
);
CREATE INDEX lexicon_entry_form_idx ON lexicon_entry(normalized_form);

CREATE TABLE lexicon_form (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL REFERENCES lexicon_entry(id) ON DELETE CASCADE,
    form TEXT NOT NULL,
    normalized_form TEXT NOT NULL,
    form_type VARCHAR(32),
    UNIQUE(entry_id, normalized_form)
);
CREATE INDEX lexicon_form_lookup_idx ON lexicon_form(normalized_form);

CREATE TABLE lexicon_sense (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL REFERENCES lexicon_entry(id) ON DELETE CASCADE,
    position INT NOT NULL,
    part_of_speech VARCHAR(32),
    definition_en TEXT,
    translation_vi TEXT,
    translation_status VARCHAR(32) NOT NULL DEFAULT 'MISSING'
        CHECK(translation_status IN ('AVAILABLE','MISSING','NEEDS_REVIEW','AI_GENERATED','VERIFIED')),
    source_id UUID REFERENCES data_source(id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE(entry_id, position)
);

CREATE TABLE lexicon_pronunciation (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL REFERENCES lexicon_entry(id) ON DELETE CASCADE,
    accent VARCHAR(32),
    ipa TEXT,
    external_audio_url TEXT,
    cached_audio_object_key TEXT,
    source_id UUID REFERENCES data_source(id),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE vocabulary_card (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    lexicon_entry_id UUID NOT NULL REFERENCES lexicon_entry(id),
    source_context_type VARCHAR(64),
    source_context_id UUID,
    next_review_at TIMESTAMPTZ NOT NULL,
    stability NUMERIC(12,4) NOT NULL DEFAULT 1,
    difficulty NUMERIC(12,4) NOT NULL DEFAULT 5,
    lapse_count INT NOT NULL DEFAULT 0,
    review_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    suspended_at TIMESTAMPTZ,
    UNIQUE(learner_id, lexicon_entry_id)
);
CREATE INDEX vocabulary_due_idx ON vocabulary_card(learner_id, next_review_at);

CREATE TABLE vocabulary_review (
    id UUID PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES vocabulary_card(id) ON DELETE CASCADE,
    rating VARCHAR(16) NOT NULL CHECK(rating IN ('AGAIN','HARD','GOOD','EASY')),
    previous_due_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ NOT NULL,
    stability NUMERIC(12,4),
    difficulty NUMERIC(12,4),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 5. GRAMMAR & TOEIC MODULES
-- ============================================================

CREATE TABLE grammar_topic (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    code VARCHAR(128),
    name_en TEXT,
    name_vi TEXT NOT NULL,
    description_vi TEXT,
    position INT
);

CREATE TABLE grammar_subtopic (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    topic_id UUID REFERENCES grammar_topic(id),
    code VARCHAR(128),
    name_en TEXT,
    name_vi TEXT NOT NULL,
    description_vi TEXT,
    position INT
);

CREATE TABLE grammar_difficulty_level (
    level INT PRIMARY KEY,
    name_vi TEXT NOT NULL,
    description_vi TEXT
);

CREATE TABLE grammar_bank_set (
    id UUID PRIMARY KEY,
    external_id TEXT UNIQUE,
    name TEXT NOT NULL,
    source_url TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE grammar_question (
    id UUID PRIMARY KEY,
    external_question_id TEXT UNIQUE,
    question_text TEXT NOT NULL,
    option_a TEXT,
    option_b TEXT,
    option_c TEXT,
    option_d TEXT,
    correct_answer VARCHAR(8) NOT NULL,
    explanation_en TEXT,
    explanation_vi TEXT,
    translation_vi TEXT,
    answer_translation_vi TEXT,
    vocabulary_note TEXT,
    difficulty_level INT REFERENCES grammar_difficulty_level(level),
    topic_id UUID REFERENCES grammar_topic(id),
    subtopic_id UUID REFERENCES grammar_subtopic(id),
    explanation_policy VARCHAR(32) NOT NULL DEFAULT 'SOURCE',
    source_test_id TEXT,
    source_test_name TEXT,
    source_question_number INT,
    source_url TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX grammar_question_topic_practice_idx
    ON grammar_question(topic_id, subtopic_id, difficulty_level, source_question_number);
CREATE INDEX grammar_question_difficulty_idx
    ON grammar_question(difficulty_level, source_question_number);

CREATE TABLE grammar_question_membership (
    question_id UUID NOT NULL REFERENCES grammar_question(id) ON DELETE CASCADE,
    bank_set_id UUID NOT NULL REFERENCES grammar_bank_set(id) ON DELETE CASCADE,
    position INT,
    PRIMARY KEY(question_id, bank_set_id)
);

CREATE TABLE grammar_practice_attempt (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES grammar_question(id),
    answer VARCHAR(8),
    correct BOOLEAN NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX grammar_attempt_learner_idx
    ON grammar_practice_attempt(learner_id, answered_at DESC);

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

-- ============================================================
-- 6. SPEECH ASSESSMENT MODULE
-- ============================================================

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

-- ============================================================
-- 7. CURRICULUM MODULE
-- ============================================================

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

-- ============================================================
-- 8. GAMIFICATION & ANALYTICS MODULES
-- ============================================================

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

-- ============================================================
-- 9. CHAT MODULE
-- ============================================================

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

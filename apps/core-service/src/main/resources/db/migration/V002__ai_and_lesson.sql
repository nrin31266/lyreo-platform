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

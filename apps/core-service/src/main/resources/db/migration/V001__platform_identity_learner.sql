-- Lyreo foundation. Flyway is the only schema owner.

-- Spring Modulith 2.1.x JDBC Event Publication Registry, PostgreSQL schema.
-- Keep this aligned with the official PostgreSQL schema instead of relying on
-- Hibernate or Modulith runtime DDL; Flyway remains Lyreo's only schema owner.
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

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    keycloak_subject TEXT NOT NULL UNIQUE,
    email_snapshot TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ
);

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

-- V011: Platform Experience Upgrade
-- Adds:
-- 1) Plus access gating for lessons and curriculum paths, plus new entitlement features & quotas
-- 2) Curriculum path cover artwork support
-- 3) Real-world AI Voice Roleplay scenario catalog & session/turn tables
-- 4) Tiered Achievement badges & enriched Mission metadata
-- 5) Human-readable Learner Notifications & Announcements table

INSERT INTO entitlement_feature (feature_key, display_name, description, status) VALUES
    ('lesson.plus-lessons', 'Plus Lessons', 'Access to Plus-exclusive listening, dictation, and shadowing lessons', 'ACTIVE'),
    ('speaking.roleplay', 'AI Voice Roleplay', 'Real-world interactive AI voice roleplay practice scenarios', 'ACTIVE')
ON CONFLICT (feature_key) DO NOTHING;

INSERT INTO entitlement_quota_policy (feature_key, tier, limit_type, period_unit, max_amount, policy_version) VALUES
    ('speaking.roleplay', 'FREE', 'PERIOD_USAGE', 'DAY', 5, 'dev-v1'),
    ('speaking.roleplay', 'PLUS', 'PERIOD_USAGE', 'DAY', 100, 'dev-v1')
ON CONFLICT (feature_key, tier) DO NOTHING;

ALTER TABLE lesson_metadata
    ADD COLUMN access_mode varchar(16) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN required_feature_key varchar(64) REFERENCES entitlement_feature(feature_key);

ALTER TABLE lesson_metadata
    ADD CONSTRAINT lesson_metadata_access_mode_check
        CHECK (access_mode IN ('PUBLIC', 'FEATURE')),
    ADD CONSTRAINT lesson_metadata_access_feature_check
        CHECK (
            (access_mode = 'PUBLIC' AND required_feature_key IS NULL)
            OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL)
        );

ALTER TABLE curriculum_path
    ADD COLUMN cover_asset_id uuid REFERENCES content_asset(id),
    ADD COLUMN access_mode varchar(16) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN required_feature_key varchar(64) REFERENCES entitlement_feature(feature_key);

ALTER TABLE curriculum_path
    ADD CONSTRAINT curriculum_path_access_mode_check
        CHECK (access_mode IN ('PUBLIC', 'FEATURE')),
    ADD CONSTRAINT curriculum_path_access_feature_check
        CHECK (
            (access_mode = 'PUBLIC' AND required_feature_key IS NULL)
            OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL)
        );

ALTER TABLE achievement_definition
    ADD COLUMN badge_asset_id uuid REFERENCES content_asset(id),
    ADD COLUMN badge_tier varchar(16) NOT NULL DEFAULT 'BRONZE',
    ADD COLUMN category varchar(32) NOT NULL DEFAULT 'MILESTONE';

ALTER TABLE achievement_definition
    ADD CONSTRAINT achievement_definition_badge_tier_check
        CHECK (badge_tier IN ('BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'LEGENDARY'));

ALTER TABLE mission_definition
    ADD COLUMN category varchar(32) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN difficulty varchar(16) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE mission_definition
    ADD CONSTRAINT mission_definition_difficulty_check
        CHECK (difficulty IN ('EASY', 'NORMAL', 'HARD'));

CREATE TABLE speaking_scenario (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL UNIQUE,
    title text NOT NULL,
    title_vi text NOT NULL,
    category varchar(64) NOT NULL,
    cefr_level varchar(8) NOT NULL CHECK (cefr_level IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
    cover_asset_id uuid REFERENCES content_asset(id),
    ai_role_name varchar(120) NOT NULL,
    ai_persona text NOT NULL,
    learner_role varchar(120) NOT NULL,
    setting_description text NOT NULL,
    opening_line text NOT NULL,
    system_prompt text NOT NULL,
    objectives_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    tts_voice varchar(64) NOT NULL DEFAULT 'af_heart',
    target_turns integer NOT NULL DEFAULT 6 CHECK (target_turns >= 2 AND target_turns <= 30),
    estimated_minutes integer NOT NULL DEFAULT 5 CHECK (estimated_minutes >= 1 AND estimated_minutes <= 60),
    access_mode varchar(16) NOT NULL DEFAULT 'PUBLIC' CHECK (access_mode IN ('PUBLIC', 'FEATURE')),
    required_feature_key varchar(64) REFERENCES entitlement_feature(feature_key),
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT speaking_scenario_access_feature_check CHECK (
        (access_mode = 'PUBLIC' AND required_feature_key IS NULL)
        OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL)
    )
);

CREATE INDEX speaking_scenario_status_idx ON speaking_scenario (status, category, cefr_level);

CREATE TABLE speaking_session (
    id uuid PRIMARY KEY,
    scenario_id uuid NOT NULL REFERENCES speaking_scenario(id) ON DELETE CASCADE,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED')),
    completed_objectives_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    feedback_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX speaking_session_learner_idx ON speaking_session (learner_id, started_at DESC);

CREATE TABLE speaking_turn (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES speaking_session(id) ON DELETE CASCADE,
    role varchar(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    text_content text NOT NULL,
    audio_asset_id uuid REFERENCES content_asset(id),
    ai_invocation_id uuid REFERENCES ai_invocation(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX speaking_turn_session_idx ON speaking_turn (session_id, created_at ASC);

CREATE TABLE learner_notification (
    id uuid PRIMARY KEY,
    title text NOT NULL,
    body text NOT NULL,
    category varchar(32) NOT NULL DEFAULT 'SYSTEM'
        CHECK (category IN ('SYSTEM', 'STUDY_REMINDER', 'REWARD', 'PROMO')),
    target_audience varchar(16) NOT NULL DEFAULT 'ALL'
        CHECK (target_audience IN ('ALL', 'FREE', 'PLUS')),
    status varchar(16) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    published_at timestamptz,
    expires_at timestamptz,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX learner_notification_feed_idx
    ON learner_notification (status, target_audience, published_at DESC);

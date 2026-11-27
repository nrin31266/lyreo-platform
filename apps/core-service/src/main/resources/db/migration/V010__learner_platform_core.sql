-- Consolidated schema additions for Learner and Admin platform modules.

-- Shared idempotency & consumer deduplication
CREATE TABLE operation_idempotency_record (
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    operation varchar(80) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(128) NOT NULL,
    status_code integer NOT NULL DEFAULT 200,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    PRIMARY KEY (learner_id, operation, idempotency_key)
);

CREATE INDEX operation_idempotency_expiry_idx
    ON operation_idempotency_record (expires_at);

CREATE TABLE consumer_event_receipt (
    consumer varchar(100) NOT NULL,
    fact_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, fact_id)
);

-- Package B: Entitlement features, quotas, reservations and audit
INSERT INTO entitlement_feature (feature_key, display_name, description, status) VALUES
    ('vocabulary.plus-packs', 'Plus Vocabulary Packs', 'Access to premium curated vocabulary packs', 'ACTIVE'),
    ('vocabulary.saved-cards', 'Saved Vocabulary Capacity', 'Personal saved vocabulary collection capacity', 'ACTIVE'),
    ('speech.shadowing', 'Shadowing Pronunciation Assessment', 'Daily shadowing pronunciation evaluation quota', 'ACTIVE'),
    ('toeic.full-access', 'Full TOEIC Access', 'Access to premium TOEIC tests', 'ACTIVE'),
    ('grammar.advanced', 'Advanced Grammar Access', 'Access to premium grammar banks and topics', 'ACTIVE'),
    ('chat.tutor', 'English Tutor Chat', 'Daily English tutor conversation turn quota', 'ACTIVE')
ON CONFLICT (feature_key) DO NOTHING;

CREATE TABLE entitlement_quota_policy (
    feature_key varchar(64) NOT NULL REFERENCES entitlement_feature(feature_key),
    tier varchar(16) NOT NULL CHECK (tier IN ('FREE', 'PLUS')),
    limit_type varchar(16) NOT NULL CHECK (limit_type IN ('PERIOD_USAGE', 'CAPACITY')),
    period_unit varchar(16) NOT NULL DEFAULT 'DAY' CHECK (period_unit IN ('DAY', 'NONE')),
    max_amount integer NOT NULL CHECK (max_amount >= 0),
    policy_version varchar(32) NOT NULL DEFAULT 'dev-v1',
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (feature_key, tier)
);

INSERT INTO entitlement_quota_policy (feature_key, tier, limit_type, period_unit, max_amount, policy_version) VALUES
    ('vocabulary.saved-cards', 'FREE', 'CAPACITY', 'NONE', 100, 'dev-v1'),
    ('vocabulary.saved-cards', 'PLUS', 'CAPACITY', 'NONE', 5000, 'dev-v1'),
    ('speech.shadowing', 'FREE', 'PERIOD_USAGE', 'DAY', 5, 'dev-v1'),
    ('speech.shadowing', 'PLUS', 'PERIOD_USAGE', 'DAY', 100, 'dev-v1'),
    ('chat.tutor', 'FREE', 'PERIOD_USAGE', 'DAY', 10, 'dev-v1'),
    ('chat.tutor', 'PLUS', 'PERIOD_USAGE', 'DAY', 200, 'dev-v1')
ON CONFLICT (feature_key, tier) DO NOTHING;

CREATE TABLE entitlement_usage_counter (
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    feature_key varchar(64) NOT NULL REFERENCES entitlement_feature(feature_key),
    period_key varchar(64) NOT NULL,
    used_count integer NOT NULL DEFAULT 0 CHECK (used_count >= 0),
    reserved_count integer NOT NULL DEFAULT 0 CHECK (reserved_count >= 0),
    reset_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, feature_key, period_key)
);

CREATE TABLE entitlement_usage_reservation (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    feature_key varchar(64) NOT NULL REFERENCES entitlement_feature(feature_key),
    operation_id varchar(128) NOT NULL,
    period_key varchar(64) NOT NULL,
    amount integer NOT NULL DEFAULT 1 CHECK (amount > 0),
    status varchar(16) NOT NULL CHECK (status IN ('RESERVED', 'SETTLED', 'RELEASED')),
    policy_version varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    UNIQUE (user_id, feature_key, operation_id)
);

CREATE TABLE entitlement_audit_log (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    feature_key varchar(64) NOT NULL,
    operation_id varchar(128),
    decision varchar(32) NOT NULL,
    policy_version varchar(32) NOT NULL,
    detail_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX entitlement_audit_user_idx
    ON entitlement_audit_log (user_id, occurred_at DESC);

-- Package A & C: Vocabulary pack access, release lineage, study sessions & personal collections
ALTER TABLE vocabulary_pack
    ADD COLUMN access_mode varchar(16) NOT NULL DEFAULT 'PUBLIC',
    ADD COLUMN required_feature_key varchar(64) REFERENCES entitlement_feature(feature_key);

ALTER TABLE vocabulary_pack
    ADD CONSTRAINT vocabulary_pack_access_mode_check CHECK (access_mode IN ('PUBLIC', 'FEATURE')),
    ADD CONSTRAINT vocabulary_pack_access_feature_check CHECK (
        (access_mode = 'PUBLIC' AND required_feature_key IS NULL)
        OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL)
    );

CREATE TABLE vocabulary_release_lineage (
    id uuid PRIMARY KEY,
    entity_type varchar(16) NOT NULL CHECK (entity_type IN ('PACK', 'UNIT', 'ENTRY')),
    entity_id uuid NOT NULL,
    release_id uuid NOT NULL REFERENCES dataset_release(id),
    stable_key text NOT NULL,
    base_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    admin_modified boolean NOT NULL DEFAULT false,
    conflict_json jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (entity_type, entity_id)
);

CREATE INDEX vocabulary_release_lineage_key_idx
    ON vocabulary_release_lineage (entity_type, stable_key);

CREATE TABLE vocabulary_entry_progress (
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    entry_id uuid NOT NULL REFERENCES vocabulary_unit_entry(id) ON DELETE CASCADE,
    unit_id uuid NOT NULL REFERENCES vocabulary_unit(id) ON DELETE CASCADE,
    pack_id uuid NOT NULL REFERENCES vocabulary_pack(id) ON DELETE CASCADE,
    status varchar(32) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    correct_count integer NOT NULL DEFAULT 0 CHECK (correct_count >= 0),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_practiced_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (learner_id, entry_id)
);

CREATE INDEX vocabulary_entry_progress_unit_idx
    ON vocabulary_entry_progress (learner_id, unit_id);

CREATE TABLE vocabulary_study_session (
    id uuid PRIMARY KEY,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    pack_id uuid NOT NULL REFERENCES vocabulary_pack(id),
    unit_id uuid NOT NULL REFERENCES vocabulary_unit(id),
    mode varchar(32) NOT NULL CHECK (mode IN (
        'CHOOSE_MEANING', 'CHOOSE_WORD', 'LETTER_SCRAMBLE',
        'MISSING_LETTERS', 'LISTEN_WRITE', 'CONTEXT_CLOZE'
    )),
    snapshot jsonb NOT NULL,
    answers jsonb NOT NULL DEFAULT '{}'::jsonb,
    result jsonb,
    revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    submitted_at timestamptz
);

CREATE INDEX vocabulary_study_session_learner_idx
    ON vocabulary_study_session (learner_id, unit_id, created_at DESC);

ALTER TABLE vocabulary_card ALTER COLUMN headword_id DROP NOT NULL;

ALTER TABLE vocabulary_card
    ADD COLUMN dedup_key text,
    ADD COLUMN term text,
    ADD COLUMN part_of_speech varchar(64),
    ADD COLUMN meaning_vi text,
    ADD COLUMN definition_en text,
    ADD COLUMN ipa text,
    ADD COLUMN context_sentence text,
    ADD COLUMN context_translation_vi text,
    ADD COLUMN source_entry_id uuid,
    ADD COLUMN sense_index integer,
    ADD COLUMN source_revision bigint,
    ADD COLUMN audio_asset_id uuid REFERENCES content_asset(id),
    ADD COLUMN image_asset_id uuid REFERENCES content_asset(id),
    ADD COLUMN snapshot_json jsonb NOT NULL DEFAULT '{}'::jsonb;

UPDATE vocabulary_card c
SET
    dedup_key = 'LEXICON_HEADWORD:' || c.headword_id::text,
    term = COALESCE(
        (SELECT h.identity_form FROM lexicon_headword h WHERE h.id = c.headword_id),
        'unknown'
    )
WHERE c.dedup_key IS NULL;

ALTER TABLE vocabulary_card ALTER COLUMN dedup_key SET NOT NULL;
ALTER TABLE vocabulary_card ALTER COLUMN term SET NOT NULL;
ALTER TABLE vocabulary_card DROP CONSTRAINT vocabulary_card_learner_id_headword_id_key;
ALTER TABLE vocabulary_card ADD CONSTRAINT vocabulary_card_learner_dedup_key UNIQUE (learner_id, dedup_key);

CREATE TABLE vocabulary_collection (
    id uuid PRIMARY KEY,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL CHECK (btrim(name) <> ''),
    is_default boolean NOT NULL DEFAULT false,
    cover_preset varchar(32) NOT NULL DEFAULT 'BLUE_BOOK',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX vocabulary_collection_default_idx
    ON vocabulary_collection (learner_id)
    WHERE is_default;

CREATE UNIQUE INDEX vocabulary_collection_owner_name_idx
    ON vocabulary_collection (learner_id, lower(btrim(name)));

CREATE TABLE vocabulary_collection_item (
    collection_id uuid NOT NULL REFERENCES vocabulary_collection(id) ON DELETE CASCADE,
    card_id uuid NOT NULL REFERENCES vocabulary_card(id) ON DELETE CASCADE,
    added_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, card_id)
);

CREATE INDEX vocabulary_collection_item_card_idx
    ON vocabulary_collection_item (card_id);

INSERT INTO vocabulary_collection (id, learner_id, name, is_default, cover_preset)
SELECT gen_random_uuid(), owners.learner_id, 'Đã lưu', true, 'BLUE_BOOK'
FROM (SELECT DISTINCT learner_id FROM vocabulary_card) owners
ON CONFLICT DO NOTHING;

INSERT INTO vocabulary_collection_item (collection_id, card_id)
SELECT col.id, c.id
FROM vocabulary_card c
JOIN vocabulary_collection col ON col.learner_id = c.learner_id AND col.is_default = true
ON CONFLICT DO NOTHING;

-- Package D: Learner Shadowing pronunciation attempts
CREATE TABLE learner_pronunciation_attempt (
    id uuid PRIMARY KEY,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    idempotency_key uuid NOT NULL,
    fingerprint text NOT NULL,
    lesson_id uuid NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    activity_id uuid NOT NULL REFERENCES lesson_activity(id) ON DELETE CASCADE,
    sentence_id uuid NOT NULL REFERENCES lesson_sentence(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    job_id uuid UNIQUE REFERENCES background_job(id),
    reservation_id uuid,
    recording_object_key text,
    recording_purged_at timestamptz,
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    reference_text text,
    reference_start_ms bigint,
    reference_end_ms bigint,
    invocation_id uuid REFERENCES ai_invocation(id),
    outcome varchar(32),
    overall_score integer,
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (learner_id, idempotency_key)
);

CREATE INDEX learner_pronunciation_attempt_expiry_idx
    ON learner_pronunciation_attempt (expires_at);

CREATE INDEX learner_pronunciation_attempt_learner_idx
    ON learner_pronunciation_attempt (learner_id, lesson_id, created_at DESC);

-- Package E: Curriculum, Gamification (XP, Streaks, Achievements, Reward Cutover)
ALTER TABLE curriculum_path
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE mission_definition
    ADD COLUMN xp_reward integer NOT NULL DEFAULT 10 CHECK (xp_reward >= 0),
    ADD COLUMN rule_version varchar(32) NOT NULL DEFAULT 'dev-v1';

ALTER TABLE mission_progress
    ADD COLUMN reward_snapshot_json jsonb;

ALTER TABLE diamond_transaction
    ADD COLUMN reason text,
    ADD COLUMN rule_version varchar(32);

CREATE TABLE xp_transaction (
    id uuid PRIMARY KEY,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    source_type varchar(64) NOT NULL,
    amount integer NOT NULL CHECK (amount <> 0),
    idempotency_key text NOT NULL UNIQUE,
    rule_version varchar(32) NOT NULL DEFAULT 'dev-v1',
    reference_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX xp_transaction_learner_idx
    ON xp_transaction (learner_id, created_at DESC);

CREATE TABLE achievement_definition (
    id uuid PRIMARY KEY,
    code varchar(100) NOT NULL UNIQUE,
    title text NOT NULL,
    description text NOT NULL,
    metric varchar(64) NOT NULL,
    target integer NOT NULL CHECK (target > 0),
    xp_reward integer NOT NULL DEFAULT 0 CHECK (xp_reward >= 0),
    diamond_reward integer NOT NULL DEFAULT 0 CHECK (diamond_reward >= 0),
    rule_version varchar(32) NOT NULL DEFAULT 'dev-v1',
    enabled boolean NOT NULL DEFAULT true
);

INSERT INTO achievement_definition (
    id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled
) VALUES
    ('40000000-0000-0000-0000-000000000001', 'FIRST_LESSON', 'Bài học đầu tiên',
     'Hoàn thành bài học đầu tiên trên Lyreo', 'DISTINCT_LESSON_COMPLETED', 1, 20, 0, 'dev-v1', true),
    ('40000000-0000-0000-0000-000000000002', 'FIVE_LESSONS', 'Chinh phục 5 bài học',
     'Hoàn thành 5 bài học khác nhau', 'DISTINCT_LESSON_COMPLETED', 5, 50, 5, 'dev-v1', true),
    ('40000000-0000-0000-0000-000000000003', 'STREAK_3_DAYS', 'Chuỗi 3 ngày liên tiếp',
     'Duy trì học tập 3 ngày liên tiếp', 'STREAK_DAYS', 3, 30, 2, 'dev-v1', true)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE learner_achievement (
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    achievement_id uuid NOT NULL REFERENCES achievement_definition(id) ON DELETE CASCADE,
    progress integer NOT NULL DEFAULT 0 CHECK (progress >= 0),
    unlocked_at timestamptz,
    reward_snapshot_json jsonb,
    PRIMARY KEY (learner_id, achievement_id)
);

CREATE TABLE learner_achievement_item (
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    metric varchar(64) NOT NULL,
    item_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (learner_id, metric, item_id)
);

CREATE TABLE learner_streak_day (
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    study_date date NOT NULL,
    first_fact_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (learner_id, study_date)
);

CREATE TABLE learner_streak (
    learner_id uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    current_streak integer NOT NULL DEFAULT 0 CHECK (current_streak >= 0),
    longest_streak integer NOT NULL DEFAULT 0 CHECK (longest_streak >= 0),
    last_study_date date,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO module_runtime_config (owner_module, config_key, schema_version, config_json)
VALUES (
    'gamification',
    'reward-policy',
    1,
    '{
      "ruleVersion": "dev-v1",
      "directLessonActivityDiamondsEnabled": false,
      "cutoverEffectiveAt": "2026-11-25T00:00:00Z",
      "xpPerLevel": 100
    }'::jsonb
)
ON CONFLICT (owner_module, config_key) DO NOTHING;

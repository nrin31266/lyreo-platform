-- Destructive cutover of V001 scaffold content. Normal DROP intentionally fails on
-- any unexpected external foreign key or view dependency.
DROP TABLE toeic_attempt_answer;
DROP TABLE toeic_attempt;
DROP TABLE toeic_question;
DROP TABLE toeic_passage;
DROP TABLE toeic_mock_test;
DROP TABLE grammar_practice_attempt;
DROP TABLE grammar_question_membership;
DROP TABLE grammar_question;
DROP TABLE grammar_bank_set;
DROP TABLE grammar_subtopic;
DROP TABLE grammar_topic;
DROP TABLE vocabulary_review;
DROP TABLE vocabulary_card;
DROP TABLE lexicon_pronunciation;
DROP TABLE lexicon_sense;
DROP TABLE lexicon_form;
DROP TABLE lexicon_entry;
DROP TABLE dataset_import;

CREATE TABLE dataset_release (
    id UUID PRIMARY KEY,
    domain VARCHAR(32) NOT NULL CHECK (domain IN ('lexicon','grammar-toeic')),
    package_version VARCHAR(32) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    archive_url TEXT,
    manifest_json JSONB NOT NULL,
    license_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (domain, package_version),
    UNIQUE (domain, checksum_sha256),
    UNIQUE (id, domain)
);
CREATE TABLE dataset_import_run (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    run_type VARCHAR(16) NOT NULL CHECK (run_type IN ('DRY_RUN','APPLY')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    records_read BIGINT NOT NULL DEFAULT 0 CHECK (records_read >= 0),
    records_inserted BIGINT NOT NULL DEFAULT 0 CHECK (records_inserted >= 0),
    details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK ((status = 'RUNNING' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED','FAILED') AND completed_at IS NOT NULL))
);
CREATE INDEX dataset_import_run_success_idx
    ON dataset_import_run(release_id) WHERE run_type = 'APPLY' AND status = 'SUCCEEDED';
CREATE TABLE dataset_active_release (
    domain VARCHAR(32) PRIMARY KEY,
    release_id UUID NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_by TEXT NOT NULL,
    FOREIGN KEY (release_id, domain) REFERENCES dataset_release(id, domain)
);
CREATE TABLE dataset_activation_history (
    id UUID PRIMARY KEY,
    domain VARCHAR(32) NOT NULL,
    release_id UUID NOT NULL,
    previous_release_id UUID,
    action VARCHAR(16) NOT NULL CHECK (action IN ('ACTIVATE','ROLLBACK')),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_by TEXT NOT NULL,
    FOREIGN KEY (release_id, domain) REFERENCES dataset_release(id, domain),
    FOREIGN KEY (previous_release_id, domain) REFERENCES dataset_release(id, domain)
);

CREATE TABLE entitlement_feature (
    feature_key VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    description TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DEPRECATED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE user_entitlement_grant (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    feature_key VARCHAR(64) NOT NULL REFERENCES entitlement_feature(feature_key),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    source_type VARCHAR(64) NOT NULL,
    source_reference TEXT,
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_by TEXT,
    revocation_reason TEXT,
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    CHECK ((status = 'ACTIVE' AND revoked_at IS NULL AND revoked_by IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
);
CREATE INDEX user_entitlement_grant_lookup_idx
    ON user_entitlement_grant(user_id, feature_key, status, valid_from, valid_until);

CREATE TABLE lexicon_headword (
    id UUID PRIMARY KEY,
    language VARCHAR(16) NOT NULL,
    identity_form TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (language, identity_form)
);
CREATE TABLE lexicon_entry (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    headword_id UUID NOT NULL REFERENCES lexicon_headword(id),
    package_entry_id UUID NOT NULL,
    display_form TEXT NOT NULL,
    lookup_form TEXT NOT NULL,
    entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN ('WORD','PHRASE','PHRASAL_VERB','IDIOM','COLLOCATION')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (release_id, package_entry_id),
    UNIQUE (release_id, headword_id),
    UNIQUE (id, release_id)
);
CREATE INDEX lexicon_entry_lookup_idx ON lexicon_entry(release_id, lookup_form text_pattern_ops);
CREATE INDEX lexicon_entry_headword_idx ON lexicon_entry(headword_id, release_id);
CREATE TABLE lexicon_item (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    package_item_id UUID NOT NULL,
    part_of_speech TEXT NOT NULL,
    pos_title TEXT NOT NULL,
    etymology_number INT,
    etymology_text TEXT,
    order_index INT NOT NULL,
    FOREIGN KEY (entry_id, release_id) REFERENCES lexicon_entry(id, release_id),
    UNIQUE (release_id, package_item_id),
    UNIQUE (id, release_id),
    UNIQUE (id, entry_id, release_id)
);
CREATE INDEX lexicon_item_entry_idx ON lexicon_item(entry_id, order_index);
CREATE TABLE lexicon_sense (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    item_id UUID NOT NULL,
    package_sense_id UUID NOT NULL,
    position INT NOT NULL,
    definition_en TEXT,
    raw_glosses JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    examples JSONB NOT NULL DEFAULT '[]'::jsonb,
    translation_vi TEXT,
    matched_qualifier TEXT,
    translation_status VARCHAR(32) NOT NULL,
    FOREIGN KEY (item_id, entry_id, release_id) REFERENCES lexicon_item(id, entry_id, release_id),
    UNIQUE (release_id, package_sense_id),
    UNIQUE (id, release_id),
    UNIQUE (id, item_id, entry_id, release_id)
);
CREATE INDEX lexicon_sense_item_idx ON lexicon_sense(item_id, position);
CREATE TABLE lexicon_form (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    item_id UUID NOT NULL,
    package_form_id UUID NOT NULL,
    form TEXT NOT NULL,
    normalized_form TEXT NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    FOREIGN KEY (item_id, entry_id, release_id) REFERENCES lexicon_item(id, entry_id, release_id),
    UNIQUE (release_id, package_form_id)
);
CREATE INDEX lexicon_form_lookup_idx ON lexicon_form(release_id, normalized_form text_pattern_ops);
CREATE TABLE lexicon_pronunciation (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    item_id UUID NOT NULL,
    package_pronunciation_id UUID NOT NULL,
    accent TEXT,
    ipa TEXT,
    audio_file TEXT,
    audio_url TEXT,
    source_url TEXT,
    cached_audio_object_key TEXT,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    FOREIGN KEY (item_id, entry_id, release_id) REFERENCES lexicon_item(id, entry_id, release_id),
    UNIQUE (release_id, package_pronunciation_id)
);
CREATE TABLE lexicon_translation (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    item_id UUID,
    sense_id UUID,
    package_translation_id UUID NOT NULL,
    word_vi TEXT NOT NULL,
    source TEXT NOT NULL,
    source_scope TEXT NOT NULL,
    source_sense_qualifier TEXT,
    link_status VARCHAR(32) NOT NULL,
    unlinked_reason TEXT,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    FOREIGN KEY (entry_id, release_id) REFERENCES lexicon_entry(id, release_id),
    FOREIGN KEY (item_id, entry_id, release_id) REFERENCES lexicon_item(id, entry_id, release_id),
    FOREIGN KEY (sense_id, item_id, entry_id, release_id) REFERENCES lexicon_sense(id, item_id, entry_id, release_id),
    CHECK (link_status IN ('DIRECT_SENSE','QUALIFIER_MATCH','ITEM_CANDIDATE','ENTRY_CANDIDATE','UNLINKED')),
    CHECK (link_status NOT IN ('DIRECT_SENSE','QUALIFIER_MATCH') OR sense_id IS NOT NULL),
    CHECK (sense_id IS NULL OR item_id IS NOT NULL),
    UNIQUE (release_id, package_translation_id)
);
CREATE INDEX lexicon_translation_entry_idx ON lexicon_translation(entry_id);

CREATE TABLE vocabulary_card (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    headword_id UUID NOT NULL REFERENCES lexicon_headword(id),
    source_context_type VARCHAR(64),
    source_context_id UUID,
    next_review_at TIMESTAMPTZ NOT NULL,
    stability NUMERIC(12,4) NOT NULL DEFAULT 1,
    difficulty NUMERIC(12,4) NOT NULL DEFAULT 5,
    lapse_count INT NOT NULL DEFAULT 0,
    review_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    suspended_at TIMESTAMPTZ,
    UNIQUE (learner_id, headword_id)
);
CREATE INDEX vocabulary_due_idx ON vocabulary_card(learner_id, next_review_at);
CREATE TABLE vocabulary_review (
    id UUID PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES vocabulary_card(id) ON DELETE CASCADE,
    rating VARCHAR(16) NOT NULL CHECK (rating IN ('AGAIN','HARD','GOOD','EASY')),
    previous_due_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ NOT NULL,
    stability NUMERIC(12,4),
    difficulty NUMERIC(12,4),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE media_blob (
    sha256 VARCHAR(64) PRIMARY KEY CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    mime_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    storage_object_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (storage_object_key <> '' AND storage_object_key !~ '^[A-Za-z][A-Za-z0-9+.-]*:'
        AND storage_object_key !~ '[?#]' AND storage_object_key !~ '^/')
);
CREATE TABLE release_media_asset (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    package_asset_id VARCHAR(64) NOT NULL,
    blob_sha256 VARCHAR(64) NOT NULL REFERENCES media_blob(sha256),
    package_path TEXT NOT NULL,
    source_paths JSONB NOT NULL,
    UNIQUE (release_id, package_asset_id),
    UNIQUE (id, release_id)
);
CREATE TABLE assessment_item (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    package_item_id UUID NOT NULL,
    kind VARCHAR(32) NOT NULL,
    stem_en TEXT,
    transcript_en TEXT,
    options JSONB NOT NULL,
    correct_option VARCHAR(8) NOT NULL,
    difficulty_level INT,
    explanation_preference VARCHAR(32),
    annotations JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_domains JSONB NOT NULL DEFAULT '[]'::jsonb,
    provenance JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_only_fields JSONB,
    UNIQUE (release_id, package_item_id),
    UNIQUE (id, release_id)
);

CREATE TABLE toeic_test_catalog (
    id UUID PRIMARY KEY,
    year INT NOT NULL,
    test_number INT NOT NULL,
    publication_status VARCHAR(16) NOT NULL CHECK (publication_status IN ('DRAFT','PUBLISHED','HIDDEN','ARCHIVED')),
    access_mode VARCHAR(16) NOT NULL CHECK (access_mode IN ('PUBLIC','FEATURE')),
    required_feature_key VARCHAR(64) REFERENCES entitlement_feature(feature_key),
    reconciliation_status VARCHAR(16) NOT NULL CHECK (reconciliation_status IN ('CONFIRMED','PROVISIONAL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (year, test_number),
    UNIQUE (id, year, test_number),
    CHECK ((access_mode = 'PUBLIC' AND required_feature_key IS NULL)
        OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL))
);
CREATE TABLE toeic_test_version (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    catalog_id UUID NOT NULL,
    package_test_id UUID NOT NULL,
    name TEXT NOT NULL,
    year INT NOT NULL,
    test_number INT NOT NULL,
    set_id UUID NOT NULL,
    order_index INT,
    source_label TEXT,
    listening_duration_seconds INT,
    reading_duration_seconds INT,
    total_questions INT NOT NULL CHECK (total_questions = 200),
    difficulty_level INT,
    source_is_free BOOLEAN NOT NULL,
    source_is_hidden BOOLEAN NOT NULL,
    media_version INT,
    FOREIGN KEY (catalog_id, year, test_number)
        REFERENCES toeic_test_catalog(id, year, test_number),
    UNIQUE (release_id, package_test_id),
    UNIQUE (release_id, catalog_id),
    UNIQUE (id, release_id)
);
CREATE TABLE toeic_stimulus_group (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    test_version_id UUID NOT NULL,
    package_group_id UUID NOT NULL,
    part INT NOT NULL CHECK (part IN (3,4,6,7)),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('AUDIO','TEXT')),
    title TEXT,
    order_index INT,
    difficulty_level INT,
    question_numbers INT[] NOT NULL,
    transcript_en TEXT,
    content_translation_vi TEXT,
    vocabulary_note_vi TEXT,
    render_html TEXT,
    source_html TEXT,
    source_html_sha256 VARCHAR(64),
    document_parse_status VARCHAR(32),
    document_count INT NOT NULL,
    FOREIGN KEY (test_version_id, release_id) REFERENCES toeic_test_version(id, release_id),
    UNIQUE (release_id, package_group_id),
    UNIQUE (id, release_id),
    UNIQUE (id, test_version_id, release_id)
);
CREATE TABLE toeic_document (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    group_id UUID NOT NULL,
    package_doc_id TEXT NOT NULL,
    ordinal INT NOT NULL CHECK (ordinal BETWEEN 1 AND 3),
    document_type VARCHAR(64),
    html TEXT NOT NULL,
    parse_status VARCHAR(32),
    FOREIGN KEY (group_id, release_id) REFERENCES toeic_stimulus_group(id, release_id),
    UNIQUE (release_id, package_doc_id)
);
CREATE TABLE toeic_placement (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    package_placement_id UUID NOT NULL,
    test_version_id UUID NOT NULL,
    item_id UUID NOT NULL,
    group_id UUID,
    section VARCHAR(16) NOT NULL CHECK (section IN ('listening','reading')),
    part INT NOT NULL CHECK (part BETWEEN 1 AND 7),
    question_number INT NOT NULL CHECK (question_number BETWEEN 1 AND 200),
    gap_number INT,
    order_index INT NOT NULL,
    FOREIGN KEY (test_version_id, release_id) REFERENCES toeic_test_version(id, release_id),
    FOREIGN KEY (item_id, release_id) REFERENCES assessment_item(id, release_id),
    FOREIGN KEY (group_id, test_version_id, release_id) REFERENCES toeic_stimulus_group(id, test_version_id, release_id),
    UNIQUE (release_id, package_placement_id),
    UNIQUE (test_version_id, question_number),
    UNIQUE (id, release_id),
    UNIQUE (id, test_version_id)
);
CREATE INDEX toeic_placement_test_idx ON toeic_placement(test_version_id, question_number);
CREATE TABLE media_asset_use (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL,
    package_use_id TEXT NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('audio','image')),
    group_id UUID,
    item_id UUID,
    source_reference TEXT,
    media_version INT,
    FOREIGN KEY (release_id, asset_id) REFERENCES release_media_asset(release_id, package_asset_id),
    FOREIGN KEY (group_id, release_id) REFERENCES toeic_stimulus_group(id, release_id),
    FOREIGN KEY (item_id, release_id) REFERENCES assessment_item(id, release_id),
    CHECK ((group_id IS NULL) <> (item_id IS NULL)),
    UNIQUE (release_id, package_use_id)
);
CREATE TABLE toeic_attempt (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    test_version_id UUID NOT NULL REFERENCES toeic_test_version(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('FULL_TEST','DRILL')),
    status VARCHAR(16) NOT NULL,
    raw_listening_correct INT,
    raw_reading_correct INT,
    listening_score INT,
    reading_score INT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    UNIQUE (id, test_version_id)
);
CREATE TABLE toeic_attempt_answer (
    attempt_id UUID NOT NULL,
    placement_id UUID NOT NULL,
    test_version_id UUID NOT NULL,
    answer VARCHAR(8),
    correct BOOLEAN,
    answered_at TIMESTAMPTZ,
    PRIMARY KEY (attempt_id, placement_id),
    FOREIGN KEY (attempt_id, test_version_id) REFERENCES toeic_attempt(id, test_version_id) ON DELETE CASCADE,
    FOREIGN KEY (placement_id, test_version_id) REFERENCES toeic_placement(id, test_version_id)
);

CREATE TABLE grammar_topic_catalog (
    id UUID PRIMARY KEY,
    publication_status VARCHAR(16) NOT NULL CHECK (publication_status IN ('DRAFT','PUBLISHED','HIDDEN','ARCHIVED')),
    access_mode VARCHAR(16) NOT NULL CHECK (access_mode IN ('PUBLIC','FEATURE')),
    required_feature_key VARCHAR(64) REFERENCES entitlement_feature(feature_key),
    reconciliation_status VARCHAR(16) NOT NULL CHECK (reconciliation_status IN ('CONFIRMED','PROVISIONAL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((access_mode = 'PUBLIC' AND required_feature_key IS NULL)
        OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL))
);
CREATE TABLE grammar_topic_version (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    catalog_id UUID NOT NULL REFERENCES grammar_topic_catalog(id),
    package_topic_id UUID NOT NULL,
    title_en TEXT NOT NULL,
    title_vi TEXT NOT NULL,
    description_en TEXT,
    description_vi TEXT,
    icon TEXT,
    order_index INT NOT NULL,
    source_access_level VARCHAR(32) NOT NULL,
    source_is_hidden BOOLEAN NOT NULL,
    UNIQUE (release_id, package_topic_id),
    UNIQUE (release_id, catalog_id),
    UNIQUE (id, release_id),
    UNIQUE (id, catalog_id, release_id),
    UNIQUE (id, package_topic_id, release_id)
);
CREATE TABLE grammar_subtopic_catalog (
    id UUID PRIMARY KEY,
    topic_catalog_id UUID NOT NULL REFERENCES grammar_topic_catalog(id),
    publication_status VARCHAR(16) NOT NULL CHECK (publication_status IN ('DRAFT','PUBLISHED','HIDDEN','ARCHIVED')),
    access_mode VARCHAR(16) NOT NULL CHECK (access_mode IN ('PUBLIC','FEATURE')),
    required_feature_key VARCHAR(64) REFERENCES entitlement_feature(feature_key),
    reconciliation_status VARCHAR(16) NOT NULL CHECK (reconciliation_status IN ('CONFIRMED','PROVISIONAL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((access_mode = 'PUBLIC' AND required_feature_key IS NULL)
        OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL)),
    UNIQUE (id, topic_catalog_id)
);
CREATE TABLE grammar_subtopic_version (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    catalog_id UUID NOT NULL REFERENCES grammar_subtopic_catalog(id),
    topic_catalog_id UUID NOT NULL,
    package_subtopic_id UUID NOT NULL,
    package_topic_id UUID NOT NULL,
    topic_version_id UUID NOT NULL,
    title_en TEXT NOT NULL,
    title_vi TEXT NOT NULL,
    description_en TEXT,
    description_vi TEXT,
    difficulty_level INT,
    order_index INT NOT NULL,
    source_access_level VARCHAR(32) NOT NULL,
    source_is_hidden BOOLEAN NOT NULL,
    FOREIGN KEY (catalog_id, topic_catalog_id) REFERENCES grammar_subtopic_catalog(id, topic_catalog_id),
    FOREIGN KEY (topic_version_id, topic_catalog_id, release_id)
        REFERENCES grammar_topic_version(id, catalog_id, release_id),
    FOREIGN KEY (topic_version_id, package_topic_id, release_id)
        REFERENCES grammar_topic_version(id, package_topic_id, release_id),
    UNIQUE (release_id, package_subtopic_id),
    UNIQUE (release_id, catalog_id),
    UNIQUE (id, release_id),
    UNIQUE (id, topic_version_id, release_id)
);
CREATE TABLE grammar_bank_catalog (
    id UUID PRIMARY KEY,
    year INT NOT NULL,
    publication_status VARCHAR(16) NOT NULL CHECK (publication_status IN ('DRAFT','PUBLISHED','HIDDEN','ARCHIVED')),
    access_mode VARCHAR(16) NOT NULL CHECK (access_mode IN ('PUBLIC','FEATURE')),
    required_feature_key VARCHAR(64) REFERENCES entitlement_feature(feature_key),
    reconciliation_status VARCHAR(16) NOT NULL CHECK (reconciliation_status IN ('CONFIRMED','PROVISIONAL')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, year),
    CHECK ((access_mode = 'PUBLIC' AND required_feature_key IS NULL)
        OR (access_mode = 'FEATURE' AND required_feature_key IS NOT NULL))
);
CREATE TABLE grammar_bank_version (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    catalog_id UUID NOT NULL,
    package_bank_id UUID NOT NULL,
    name TEXT NOT NULL,
    year INT NOT NULL,
    order_index INT NOT NULL,
    source_access_level VARCHAR(32) NOT NULL,
    source_counts JSONB NOT NULL,
    FOREIGN KEY (catalog_id, year) REFERENCES grammar_bank_catalog(id, year),
    UNIQUE (release_id, package_bank_id),
    UNIQUE (release_id, catalog_id),
    UNIQUE (id, release_id)
);
CREATE TABLE grammar_difficulty_level_version (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    level INT NOT NULL CHECK (level BETWEEN 1 AND 5),
    package_level_id TEXT NOT NULL,
    name_vi TEXT NOT NULL,
    description_vi TEXT,
    UNIQUE (release_id, level),
    UNIQUE (release_id, package_level_id)
);
CREATE TABLE grammar_membership (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES dataset_release(id),
    package_membership_id TEXT NOT NULL,
    item_id UUID NOT NULL,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('topic','bank','difficulty')),
    topic_version_id UUID,
    subtopic_version_id UUID,
    bank_version_id UUID,
    difficulty_level INT,
    order_index INT NOT NULL,
    source_url TEXT NOT NULL,
    source_test_id TEXT,
    source_question_number INT,
    FOREIGN KEY (item_id, release_id) REFERENCES assessment_item(id, release_id),
    FOREIGN KEY (topic_version_id, release_id) REFERENCES grammar_topic_version(id, release_id),
    FOREIGN KEY (subtopic_version_id, topic_version_id, release_id) REFERENCES grammar_subtopic_version(id, topic_version_id, release_id),
    FOREIGN KEY (bank_version_id, release_id) REFERENCES grammar_bank_version(id, release_id),
    FOREIGN KEY (release_id, difficulty_level) REFERENCES grammar_difficulty_level_version(release_id, level),
    CHECK ((mode = 'topic' AND topic_version_id IS NOT NULL AND bank_version_id IS NULL)
        OR (mode = 'bank' AND topic_version_id IS NULL AND subtopic_version_id IS NULL AND bank_version_id IS NOT NULL)
        OR (mode = 'difficulty' AND topic_version_id IS NULL AND subtopic_version_id IS NULL AND bank_version_id IS NULL AND difficulty_level IS NOT NULL)),
    UNIQUE (release_id, package_membership_id)
);
CREATE INDEX grammar_membership_topic_idx ON grammar_membership(topic_version_id, subtopic_version_id, order_index);
CREATE INDEX grammar_membership_bank_idx ON grammar_membership(bank_version_id, order_index);
CREATE INDEX grammar_membership_difficulty_idx ON grammar_membership(release_id, difficulty_level, order_index);
CREATE TABLE grammar_practice_attempt (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES assessment_item(id),
    answer VARCHAR(8),
    correct BOOLEAN NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX grammar_attempt_learner_idx ON grammar_practice_attempt(learner_id, answered_at DESC);

-- A release can only become active after a complete, audited APPLY run.
CREATE FUNCTION verify_release_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM dataset_import_run r
         WHERE r.release_id = NEW.release_id AND r.run_type = 'APPLY'
           AND r.status = 'SUCCEEDED' AND r.completed_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Release % has no successful completed APPLY run', NEW.release_id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER dataset_active_release_guard
    BEFORE INSERT OR UPDATE OF release_id ON dataset_active_release
    FOR EACH ROW EXECUTE FUNCTION verify_release_activation();

-- Content snapshots are append-only. Catalog policy, grants, attempts, import runs,
-- and active pointers are deliberately mutable runtime state.
CREATE FUNCTION reject_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Immutable release snapshot % cannot be changed', TG_TABLE_NAME;
END;
$$;
CREATE FUNCTION reject_finalized_release_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM dataset_import_run r
         WHERE r.release_id = NEW.release_id AND r.run_type = 'APPLY' AND r.status = 'SUCCEEDED'
    ) THEN
        RAISE EXCEPTION 'Finalized release % cannot accept new snapshot rows', NEW.release_id;
    END IF;
    RETURN NEW;
END;
$$;
DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'dataset_release', 'lexicon_headword', 'lexicon_entry', 'lexicon_item',
        'lexicon_sense', 'lexicon_form', 'lexicon_pronunciation', 'lexicon_translation',
        'media_blob', 'release_media_asset', 'media_asset_use', 'assessment_item',
        'toeic_test_version', 'toeic_stimulus_group', 'toeic_document', 'toeic_placement',
        'grammar_topic_version', 'grammar_subtopic_version', 'grammar_bank_version',
        'grammar_difficulty_level_version', 'grammar_membership'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I_snapshot_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_snapshot_mutation()',
            table_name, table_name
        );
        EXECUTE format(
            'CREATE TRIGGER %I_snapshot_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_snapshot_mutation()',
            table_name, table_name
        );
    END LOOP;
END;
$$;
DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'lexicon_entry', 'lexicon_item', 'lexicon_sense', 'lexicon_form',
        'lexicon_pronunciation', 'lexicon_translation', 'release_media_asset',
        'media_asset_use', 'assessment_item', 'toeic_test_version',
        'toeic_stimulus_group', 'toeic_document', 'toeic_placement',
        'grammar_topic_version', 'grammar_subtopic_version', 'grammar_bank_version',
        'grammar_difficulty_level_version', 'grammar_membership'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER %I_finalized_insert AFTER INSERT ON %I FOR EACH ROW EXECUTE FUNCTION reject_finalized_release_insert()',
            table_name, table_name
        );
    END LOOP;
END;
$$;

-- Consolidated foundation schema from the pre-release development migrations.
-- PostgreSQL objects are grouped by dependency: functions, tables, indexes, triggers and constraints.

SET LOCAL check_function_bodies = false;

CREATE FUNCTION public.guard_lesson_annotation_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.sentence_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM lesson_sentence WHERE id=NEW.sentence_id AND lesson_id=NEW.lesson_id
    ) THEN
        RAISE EXCEPTION 'Annotation sentence must belong to the same lesson' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION public.guard_lesson_candidate_membership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM lesson_activity a JOIN lesson_sentence s ON s.lesson_id=a.lesson_id
        WHERE a.id=NEW.activity_id AND s.id=NEW.sentence_id) THEN
        RAISE EXCEPTION 'Candidate must belong to the same lesson as its activity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION public.guard_published_lesson_content() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE target UUID;
BEGIN
    IF TG_TABLE_NAME = 'lesson' THEN
        target := OLD.id;
    ELSIF TG_TABLE_NAME = 'lesson_authoring' THEN
        target := OLD.lesson_id;
    ELSIF TG_TABLE_NAME IN ('lesson_word_timestamp','sentence_pronunciation') THEN
        SELECT lesson_id INTO target FROM lesson_sentence
         WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.sentence_id ELSE OLD.sentence_id END;
    ELSIF TG_TABLE_NAME = 'lesson_activity_sentence' THEN
        SELECT lesson_id INTO target FROM lesson_activity
         WHERE id = CASE WHEN TG_OP = 'INSERT' THEN NEW.activity_id ELSE OLD.activity_id END;
    ELSE
        target := CASE WHEN TG_OP = 'INSERT' THEN NEW.lesson_id ELSE OLD.lesson_id END;
    END IF;
    -- Publication holds this row too; recheck after any concurrent publisher commits.
    PERFORM id FROM lesson WHERE id=target FOR UPDATE;
    IF EXISTS(SELECT 1 FROM lesson_authoring WHERE lesson_id=target AND published_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Published lesson content is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME NOT IN ('lesson','lesson_authoring') THEN
        IF TG_TABLE_NAME IN ('lesson_word_timestamp','sentence_pronunciation','lesson_activity_sentence') THEN
            IF NEW.sentence_id IS DISTINCT FROM OLD.sentence_id THEN
                RAISE EXCEPTION 'Content identity cannot be reassigned' USING ERRCODE='23514';
            END IF;
        ELSIF NEW.lesson_id IS DISTINCT FROM OLD.lesson_id THEN
            RAISE EXCEPTION 'Content identity cannot be reassigned' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE FUNCTION public.initialize_lesson_metadata() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO lesson_metadata(lesson_id,title) VALUES(NEW.id,NEW.title);
    RETURN NEW;
END $$;

CREATE FUNCTION public.protect_assessment_session() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.submitted_at IS NOT NULL OR
       ROW(NEW.id,NEW.learner_id,NEW.domain,NEW.catalog_id,NEW.mode,NEW.snapshot,NEW.created_at,NEW.expires_at)
       IS DISTINCT FROM
       ROW(OLD.id,OLD.learner_id,OLD.domain,OLD.catalog_id,OLD.mode,OLD.snapshot,OLD.created_at,OLD.expires_at) THEN
        RAISE EXCEPTION 'Immutable assessment snapshot/result';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.reject_finalized_release_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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

CREATE FUNCTION public.reject_snapshot_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Immutable release snapshot % cannot be changed', TG_TABLE_NAME;
END;
$$;

CREATE FUNCTION public.verify_release_activation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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

CREATE TABLE public.ai_capability_route (
    id uuid NOT NULL,
    capability character varying(64) NOT NULL,
    provider_id uuid NOT NULL,
    model text NOT NULL,
    priority integer DEFAULT 100 NOT NULL,
    is_fallback boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL
);

CREATE TABLE public.ai_invocation (
    id uuid NOT NULL,
    capability character varying(64) NOT NULL,
    provider_code character varying(64) NOT NULL,
    model text NOT NULL,
    status character varying(32) NOT NULL,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    latency_ms bigint,
    input_tokens integer,
    output_tokens integer,
    estimated_cost numeric(18,8),
    request_hash character varying(128),
    raw_request_artifact_key text,
    raw_response_artifact_key text,
    error_code character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.ai_provider (
    id uuid NOT NULL,
    code character varying(64) NOT NULL,
    display_name text NOT NULL,
    base_url text,
    encrypted_api_key text,
    key_last4 character varying(8),
    enabled boolean DEFAULT true NOT NULL,
    connection_status character varying(32) DEFAULT 'UNKNOWN'::character varying NOT NULL,
    last_tested_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.app_user (
    id uuid NOT NULL,
    keycloak_subject text NOT NULL,
    email_snapshot text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone
);

CREATE TABLE public.assessment_item (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    package_item_id uuid NOT NULL,
    kind character varying(32) NOT NULL,
    stem_en text,
    transcript_en text,
    options jsonb NOT NULL,
    correct_option character varying(8) NOT NULL,
    difficulty_level integer,
    explanation_preference character varying(32),
    annotations jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_domains jsonb DEFAULT '[]'::jsonb NOT NULL,
    provenance jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_only_fields jsonb
);

CREATE TABLE public.assessment_session (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    domain character varying(16) NOT NULL,
    catalog_id uuid,
    mode character varying(16) NOT NULL,
    snapshot jsonb NOT NULL,
    answers jsonb DEFAULT '{}'::jsonb NOT NULL,
    result jsonb,
    revision bigint DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone DEFAULT (now() + '24:00:00'::interval) NOT NULL,
    submitted_at timestamp with time zone,
    CONSTRAINT assessment_session_domain_check CHECK (((domain)::text = ANY ((ARRAY['grammar'::character varying, 'toeic'::character varying])::text[])))
);

CREATE TABLE public.background_job (
    id uuid NOT NULL,
    job_type character varying(100) NOT NULL,
    owner_module character varying(100) NOT NULL,
    owner_reference_id uuid,
    status character varying(32) NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    current_step character varying(100),
    progress_percent integer DEFAULT 0 NOT NULL,
    cancel_requested_at timestamp with time zone,
    attempt_count integer DEFAULT 0 NOT NULL,
    max_attempts integer DEFAULT 3 NOT NULL,
    next_retry_at timestamp with time zone,
    lease_owner text,
    lease_until timestamp with time zone,
    heartbeat_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    error_code character varying(100),
    error_message text,
    config_snapshot_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT background_job_max_attempts_check CHECK ((max_attempts >= 1)),
    CONSTRAINT background_job_progress_percent_check CHECK (((progress_percent >= 0) AND (progress_percent <= 100))),
    CONSTRAINT background_job_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'RETRY_WAIT'::character varying, 'CANCEL_REQUESTED'::character varying, 'CANCELLED'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE public.chat_conversation (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    title text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone
);

CREATE TABLE public.chat_message (
    id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    role character varying(16) NOT NULL,
    content text NOT NULL,
    ai_invocation_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chat_message_role_check CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying, 'SYSTEM'::character varying])::text[])))
);

CREATE TABLE public.content_ai_request (
    id uuid NOT NULL,
    namespace character varying(40) NOT NULL,
    document_id uuid NOT NULL,
    base_revision bigint NOT NULL,
    operation character varying(32) NOT NULL,
    input jsonb NOT NULL,
    output jsonb,
    job_id uuid NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    accepted_revision bigint,
    applied jsonb DEFAULT '{}'::jsonb NOT NULL
);

CREATE TABLE public.content_asset (
    id uuid NOT NULL,
    sha256 character varying(64) NOT NULL,
    storage_key text NOT NULL,
    mime_type character varying(64) NOT NULL,
    size_bytes bigint NOT NULL,
    filename text NOT NULL,
    synthetic boolean DEFAULT false NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT content_asset_size_bytes_check CHECK ((size_bytes > 0))
);

CREATE TABLE public.content_audit (
    id bigint NOT NULL,
    namespace character varying(40) NOT NULL,
    document_id uuid NOT NULL,
    action character varying(16) NOT NULL,
    revision bigint NOT NULL,
    actor text NOT NULL,
    reason text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT content_audit_action_check CHECK (((action)::text = ANY ((ARRAY['SAVE'::character varying, 'PUBLISH'::character varying, 'HIDDEN'::character varying, 'ARCHIVED'::character varying, 'PUBLISHED'::character varying, 'KEEP_EDIT'::character varying, 'TAKE_SOURCE'::character varying, 'EDITED'::character varying, 'DELETE'::character varying])::text[])))
);

ALTER TABLE public.content_audit ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.content_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.content_document (
    namespace character varying(40) NOT NULL,
    id uuid NOT NULL,
    revision bigint NOT NULL,
    draft jsonb NOT NULL,
    publication_id uuid,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT content_document_draft_check CHECK ((jsonb_typeof(draft) = 'object'::text)),
    CONSTRAINT content_document_revision_check CHECK ((revision > 0)),
    CONSTRAINT content_document_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'HIDDEN'::character varying, 'ARCHIVED'::character varying])::text[])))
);

CREATE TABLE public.content_publication (
    id uuid NOT NULL,
    namespace character varying(40) NOT NULL,
    document_id uuid NOT NULL,
    revision bigint NOT NULL,
    content jsonb NOT NULL,
    reason text NOT NULL,
    published_by text NOT NULL,
    published_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT content_publication_content_check CHECK ((jsonb_typeof(content) = 'object'::text))
);

CREATE TABLE public.content_quality_report (
    id uuid NOT NULL,
    namespace text NOT NULL,
    target_id uuid NOT NULL,
    revision bigint NOT NULL,
    fingerprint text NOT NULL,
    rules_version text NOT NULL,
    job_id uuid NOT NULL,
    status text DEFAULT 'RUNNING'::text NOT NULL,
    issues jsonb DEFAULT '[]'::jsonb NOT NULL,
    context jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    checked_at timestamp with time zone,
    CONSTRAINT content_quality_report_status_check CHECK ((status = ANY (ARRAY['RUNNING'::text, 'PASSED'::text, 'WARNING'::text, 'BLOCKED'::text])))
);

CREATE TABLE public.dataset_active_release (
    domain character varying(32) NOT NULL,
    release_id uuid NOT NULL,
    activated_at timestamp with time zone DEFAULT now() NOT NULL,
    activated_by text NOT NULL
);

CREATE VIEW public.content_quality_current AS
 SELECT DISTINCT ON (r.namespace, r.target_id) r.namespace,
    r.target_id,
        CASE
            WHEN ((r.rules_version <> '2.1.2'::text) OR (EXISTS ( SELECT 1
               FROM public.content_document d
              WHERE (d.updated_at > r.created_at))) OR (EXISTS ( SELECT 1
               FROM public.dataset_active_release a
              WHERE (a.activated_at > r.created_at)))) THEN 'STALE'::text
            WHEN ((r.status = 'RUNNING'::text) AND ((j.status)::text = ANY ((ARRAY['FAILED'::character varying, 'CANCELLED'::character varying])::text[]))) THEN 'FAILED'::text
            ELSE r.status
        END AS status
   FROM (public.content_quality_report r
     JOIN public.background_job j ON ((j.id = r.job_id)))
  ORDER BY r.namespace, r.target_id, r.created_at DESC, r.id DESC;

CREATE TABLE public.content_search_term (
    namespace character varying(40) NOT NULL,
    document_id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    term text NOT NULL,
    display_form text NOT NULL,
    CONSTRAINT content_search_term_kind_check CHECK (((kind)::text = ANY ((ARRAY['HEADWORD'::character varying, 'FORM'::character varying])::text[])))
);

CREATE TABLE public.content_source_alias (
    namespace character varying(40) NOT NULL,
    source_id uuid NOT NULL,
    document_id uuid NOT NULL
);

CREATE TABLE public.content_source_binding (
    namespace character varying(40) NOT NULL,
    document_id uuid NOT NULL,
    domain character varying(32) NOT NULL,
    base_release_id uuid NOT NULL,
    identity jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL
);

CREATE TABLE public.curriculum_enrollment (
    learner_id uuid NOT NULL,
    path_id uuid NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone
);

CREATE TABLE public.curriculum_item (
    id uuid NOT NULL,
    section_id uuid NOT NULL,
    "position" integer NOT NULL,
    content_type character varying(64) NOT NULL,
    content_reference_id uuid NOT NULL,
    required boolean DEFAULT true NOT NULL,
    unlock_rule text,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL
);

CREATE TABLE public.curriculum_item_progress (
    learner_id uuid NOT NULL,
    item_id uuid NOT NULL,
    status character varying(32) DEFAULT 'LOCKED'::character varying NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone
);

CREATE TABLE public.curriculum_path (
    id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name text NOT NULL,
    level character varying(32),
    description text,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.curriculum_section (
    id uuid NOT NULL,
    path_id uuid NOT NULL,
    title text NOT NULL,
    "position" integer NOT NULL
);

CREATE TABLE public.data_license (
    id uuid NOT NULL,
    spdx_code character varying(100),
    name text NOT NULL,
    url text
);

CREATE TABLE public.data_maintenance_run (
    id text NOT NULL,
    model text NOT NULL,
    prompt_version text NOT NULL,
    pricing_date date NOT NULL,
    budget_usd numeric(12,8) NOT NULL,
    reserved_usd numeric(12,8) DEFAULT 0 NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT data_maintenance_run_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT data_maintenance_run_budget_usd_check CHECK ((budget_usd > (0)::numeric)),
    CONSTRAINT data_maintenance_run_check CHECK (((reserved_usd >= (0)::numeric) AND (reserved_usd <= budget_usd)))
);

CREATE TABLE public.data_maintenance_work (
    fingerprint text NOT NULL,
    run_id text NOT NULL,
    domain text NOT NULL,
    target_id text NOT NULL,
    input jsonb NOT NULL,
    output jsonb,
    status text NOT NULL,
    reserved_usd numeric(12,8) NOT NULL,
    actual_usd numeric(12,8),
    invocation_id uuid,
    error_code text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    input_tokens integer,
    output_tokens integer,
    reasoning_tokens integer,
    CONSTRAINT data_maintenance_work_reserved_usd_check CHECK ((reserved_usd > (0)::numeric)),
    CONSTRAINT data_maintenance_work_status_check CHECK ((status = ANY (ARRAY['RESERVED'::text, 'SUCCEEDED'::text, 'FAILED'::text])))
);

CREATE TABLE public.data_source (
    id uuid NOT NULL,
    code character varying(100) NOT NULL,
    name text NOT NULL,
    url text,
    license_id uuid,
    attribution_text text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.dataset_import_run (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    run_type character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    records_read bigint DEFAULT 0 NOT NULL,
    records_inserted bigint DEFAULT 0 NOT NULL,
    details_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    error_message text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT dataset_import_run_check CHECK (((((status)::text = 'RUNNING'::text) AND (completed_at IS NULL)) OR (((status)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])) AND (completed_at IS NOT NULL)))),
    CONSTRAINT dataset_import_run_records_inserted_check CHECK ((records_inserted >= 0)),
    CONSTRAINT dataset_import_run_records_read_check CHECK ((records_read >= 0)),
    CONSTRAINT dataset_import_run_run_type_check CHECK (((run_type)::text = ANY ((ARRAY['DRY_RUN'::character varying, 'APPLY'::character varying])::text[]))),
    CONSTRAINT dataset_import_run_status_check CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);

CREATE TABLE public.dataset_release (
    id uuid NOT NULL,
    domain character varying(32) NOT NULL,
    package_version character varying(32) NOT NULL,
    schema_version character varying(32) NOT NULL,
    checksum_sha256 character varying(64) NOT NULL,
    archive_url text,
    manifest_json jsonb NOT NULL,
    license_text text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dataset_release_checksum_sha256_check CHECK (((checksum_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT dataset_release_domain_check CHECK (((domain)::text = ANY ((ARRAY['lexicon'::character varying, 'grammar-toeic'::character varying])::text[])))
);

CREATE TABLE public.diamond_transaction (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    transaction_type character varying(64) NOT NULL,
    amount integer NOT NULL,
    idempotency_key text NOT NULL,
    reference_type character varying(64),
    reference_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT diamond_transaction_amount_check CHECK ((amount <> 0))
);

CREATE TABLE public.diamond_wallet (
    learner_id uuid NOT NULL,
    cached_balance integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.entitlement_feature (
    feature_key character varying(64) NOT NULL,
    display_name character varying(128) NOT NULL,
    description text,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT entitlement_feature_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DEPRECATED'::character varying])::text[])))
);

CREATE TABLE public.event_publication (
    id uuid NOT NULL,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamp with time zone NOT NULL,
    completion_date timestamp with time zone,
    status text,
    completion_attempts integer,
    last_resubmission_date timestamp with time zone
);

CREATE TABLE public.grammar_bank_catalog (
    id uuid NOT NULL,
    year integer NOT NULL,
    publication_status character varying(16) NOT NULL,
    access_mode character varying(16) NOT NULL,
    required_feature_key character varying(64),
    reconciliation_status character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT grammar_bank_catalog_access_mode_check CHECK (((access_mode)::text = ANY ((ARRAY['PUBLIC'::character varying, 'FEATURE'::character varying])::text[]))),
    CONSTRAINT grammar_bank_catalog_check CHECK (((((access_mode)::text = 'PUBLIC'::text) AND (required_feature_key IS NULL)) OR (((access_mode)::text = 'FEATURE'::text) AND (required_feature_key IS NOT NULL)))),
    CONSTRAINT grammar_bank_catalog_publication_status_check CHECK (((publication_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'HIDDEN'::character varying, 'ARCHIVED'::character varying])::text[]))),
    CONSTRAINT grammar_bank_catalog_reconciliation_status_check CHECK (((reconciliation_status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'PROVISIONAL'::character varying])::text[])))
);

CREATE TABLE public.grammar_bank_version (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    package_bank_id uuid NOT NULL,
    name text NOT NULL,
    year integer NOT NULL,
    order_index integer NOT NULL,
    source_access_level character varying(32) NOT NULL,
    source_counts jsonb NOT NULL
);

CREATE TABLE public.grammar_difficulty_level (
    level integer NOT NULL,
    name_vi text NOT NULL,
    description_vi text
);

CREATE TABLE public.grammar_difficulty_level_version (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    level integer NOT NULL,
    package_level_id text NOT NULL,
    name_vi text NOT NULL,
    description_vi text,
    CONSTRAINT grammar_difficulty_level_version_level_check CHECK (((level >= 1) AND (level <= 5)))
);

CREATE TABLE public.grammar_membership (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    package_membership_id text NOT NULL,
    item_id uuid NOT NULL,
    mode character varying(16) NOT NULL,
    topic_version_id uuid,
    subtopic_version_id uuid,
    bank_version_id uuid,
    difficulty_level integer,
    order_index integer NOT NULL,
    source_url text NOT NULL,
    source_test_id text,
    source_question_number integer,
    CONSTRAINT grammar_membership_check CHECK (((((mode)::text = 'topic'::text) AND (topic_version_id IS NOT NULL) AND (bank_version_id IS NULL)) OR (((mode)::text = 'bank'::text) AND (topic_version_id IS NULL) AND (subtopic_version_id IS NULL) AND (bank_version_id IS NOT NULL)) OR (((mode)::text = 'difficulty'::text) AND (topic_version_id IS NULL) AND (subtopic_version_id IS NULL) AND (bank_version_id IS NULL) AND (difficulty_level IS NOT NULL)))),
    CONSTRAINT grammar_membership_mode_check CHECK (((mode)::text = ANY ((ARRAY['topic'::character varying, 'bank'::character varying, 'difficulty'::character varying])::text[])))
);

CREATE TABLE public.grammar_practice_attempt (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    item_id uuid NOT NULL,
    answer character varying(8),
    correct boolean NOT NULL,
    answered_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.grammar_subtopic_catalog (
    id uuid NOT NULL,
    topic_catalog_id uuid NOT NULL,
    publication_status character varying(16) NOT NULL,
    access_mode character varying(16) NOT NULL,
    required_feature_key character varying(64),
    reconciliation_status character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT grammar_subtopic_catalog_access_mode_check CHECK (((access_mode)::text = ANY ((ARRAY['PUBLIC'::character varying, 'FEATURE'::character varying])::text[]))),
    CONSTRAINT grammar_subtopic_catalog_check CHECK (((((access_mode)::text = 'PUBLIC'::text) AND (required_feature_key IS NULL)) OR (((access_mode)::text = 'FEATURE'::text) AND (required_feature_key IS NOT NULL)))),
    CONSTRAINT grammar_subtopic_catalog_publication_status_check CHECK (((publication_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'HIDDEN'::character varying, 'ARCHIVED'::character varying])::text[]))),
    CONSTRAINT grammar_subtopic_catalog_reconciliation_status_check CHECK (((reconciliation_status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'PROVISIONAL'::character varying])::text[])))
);

CREATE TABLE public.grammar_subtopic_version (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    topic_catalog_id uuid NOT NULL,
    package_subtopic_id uuid NOT NULL,
    package_topic_id uuid NOT NULL,
    topic_version_id uuid NOT NULL,
    title_en text NOT NULL,
    title_vi text NOT NULL,
    description_en text,
    description_vi text,
    difficulty_level integer,
    order_index integer NOT NULL,
    source_access_level character varying(32) NOT NULL,
    source_is_hidden boolean NOT NULL
);

CREATE TABLE public.grammar_topic_catalog (
    id uuid NOT NULL,
    publication_status character varying(16) NOT NULL,
    access_mode character varying(16) NOT NULL,
    required_feature_key character varying(64),
    reconciliation_status character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT grammar_topic_catalog_access_mode_check CHECK (((access_mode)::text = ANY ((ARRAY['PUBLIC'::character varying, 'FEATURE'::character varying])::text[]))),
    CONSTRAINT grammar_topic_catalog_check CHECK (((((access_mode)::text = 'PUBLIC'::text) AND (required_feature_key IS NULL)) OR (((access_mode)::text = 'FEATURE'::text) AND (required_feature_key IS NOT NULL)))),
    CONSTRAINT grammar_topic_catalog_publication_status_check CHECK (((publication_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'HIDDEN'::character varying, 'ARCHIVED'::character varying])::text[]))),
    CONSTRAINT grammar_topic_catalog_reconciliation_status_check CHECK (((reconciliation_status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'PROVISIONAL'::character varying])::text[])))
);

CREATE TABLE public.grammar_topic_version (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    package_topic_id uuid NOT NULL,
    title_en text NOT NULL,
    title_vi text NOT NULL,
    description_en text,
    description_vi text,
    icon text,
    order_index integer NOT NULL,
    source_access_level character varying(32) NOT NULL,
    source_is_hidden boolean NOT NULL
);

CREATE TABLE public.learner_daily_activity (
    learner_id uuid NOT NULL,
    activity_date date NOT NULL,
    study_seconds bigint DEFAULT 0 NOT NULL,
    lesson_count integer DEFAULT 0 NOT NULL,
    vocabulary_review_count integer DEFAULT 0 NOT NULL,
    grammar_answer_count integer DEFAULT 0 NOT NULL,
    toeic_question_count integer DEFAULT 0 NOT NULL
);

CREATE TABLE public.learner_level (
    learner_id uuid NOT NULL,
    xp bigint DEFAULT 0 NOT NULL,
    level integer DEFAULT 1 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT learner_level_level_check CHECK ((level >= 1)),
    CONSTRAINT learner_level_xp_check CHECK ((xp >= 0))
);

CREATE TABLE public.learner_profile (
    learner_id uuid NOT NULL,
    display_name text,
    current_level character varying(32),
    goal character varying(128),
    daily_minutes integer,
    focus_area character varying(128),
    preferences_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    onboarding_completed_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT learner_profile_daily_minutes_check CHECK (((daily_minutes IS NULL) OR ((daily_minutes >= 5) AND (daily_minutes <= 240))))
);

CREATE TABLE public.learner_skill_summary (
    learner_id uuid NOT NULL,
    skill_code character varying(64) NOT NULL,
    score numeric(6,2),
    sample_count integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.learner_weakness (
    learner_id uuid NOT NULL,
    weakness_type character varying(64) NOT NULL,
    weakness_key text NOT NULL,
    severity numeric(6,2) NOT NULL,
    sample_count integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.lesson (
    id uuid NOT NULL,
    title text NOT NULL,
    source_type character varying(32) NOT NULL,
    source_text text,
    source_reference text,
    canonical_audio_object_key text,
    status character varying(32) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    CONSTRAINT lesson_source_type_check CHECK (((source_type)::text = ANY ((ARRAY['TEXT'::character varying, 'AUDIO'::character varying, 'YOUTUBE'::character varying])::text[])))
);

CREATE TABLE public.lesson_activity (
    id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    activity_type character varying(64) NOT NULL,
    "position" integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    config_json jsonb DEFAULT '{}'::jsonb NOT NULL
);

CREATE TABLE public.lesson_activity_progress (
    learner_id uuid NOT NULL,
    activity_id uuid NOT NULL,
    status character varying(32) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    completed_items integer DEFAULT 0 NOT NULL,
    total_items integer DEFAULT 0 NOT NULL,
    best_score integer,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    last_practiced_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lesson_activity_progress_best_score_check CHECK (((best_score >= 0) AND (best_score <= 100))),
    CONSTRAINT lesson_activity_progress_completed_items_check CHECK ((completed_items >= 0)),
    CONSTRAINT lesson_activity_progress_status_check CHECK (((status)::text = ANY ((ARRAY['NOT_STARTED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT lesson_activity_progress_total_items_check CHECK ((total_items >= 0))
);

CREATE TABLE public.lesson_activity_sentence (
    activity_id uuid NOT NULL,
    sentence_id uuid NOT NULL
);

CREATE TABLE public.lesson_annotation (
    id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    sentence_id uuid,
    annotation_type character varying(64) NOT NULL,
    start_char integer,
    end_char integer,
    payload_json jsonb NOT NULL,
    generated_by character varying(64),
    provider character varying(64),
    model text,
    text_hash character varying(128),
    status character varying(32) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.lesson_audio_source (
    id uuid NOT NULL,
    object_key text NOT NULL,
    content_type character varying(100) NOT NULL,
    duration_ms bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    original_filename character varying(255),
    CONSTRAINT lesson_audio_source_duration_ms_check CHECK (((duration_ms > 0) AND (duration_ms <= 7200000)))
);

CREATE TABLE public.lesson_authoring (
    lesson_id uuid NOT NULL,
    video_id character varying(32),
    source_start_ms bigint NOT NULL,
    source_end_ms bigint,
    revision bigint DEFAULT 0 NOT NULL,
    transcript_revision bigint DEFAULT 0 NOT NULL,
    approved_revision bigint,
    karaoke_reviewed_revision bigint,
    semantic_revision bigint,
    timing_status character varying(32) DEFAULT 'MISSING'::character varying NOT NULL,
    active_job_id uuid,
    document jsonb DEFAULT '[]'::jsonb NOT NULL,
    published_at timestamp with time zone,
    source_selected_at timestamp with time zone DEFAULT now() CONSTRAINT lesson_authoring_rights_confirmed_at_not_null NOT NULL,
    timing_evidence jsonb DEFAULT '{}'::jsonb NOT NULL,
    generation_options jsonb DEFAULT '{"accent": "US", "locale": "vi-VN", "density": "AUTO", "activities": ["DICTATION", "SHADOWING"], "annotations": ["TRANSLATION", "LEXICAL", "GRAMMAR", "ENTITY_HINTS", "DICTATION_HINTS", "LEARNING_TIPS", "THOUGHT_GROUPS"], "grammarLimit": 5, "vocabularyLimit": 12, "pronunciationStrategy": "ON_DEMAND"}'::jsonb NOT NULL,
    audio_source_id uuid,
    tts_voice character varying(100),
    content_profile jsonb DEFAULT '{}'::jsonb NOT NULL,
    auto_continue boolean DEFAULT false NOT NULL,
    published_by uuid,
    CONSTRAINT lesson_authoring_check CHECK ((source_end_ms > source_start_ms)),
    CONSTRAINT lesson_authoring_document_check CHECK ((jsonb_typeof(document) = 'array'::text)),
    CONSTRAINT lesson_authoring_source_start_ms_check CHECK ((source_start_ms >= 0))
);

CREATE TABLE public.lesson_authoring_job (
    job_id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    operation character varying(16) NOT NULL,
    transcript_revision bigint NOT NULL,
    completed boolean DEFAULT false NOT NULL,
    checkpoints jsonb DEFAULT '{}'::jsonb NOT NULL,
    usage_json jsonb DEFAULT '[]'::jsonb NOT NULL,
    result_json jsonb,
    CONSTRAINT lesson_authoring_job_operation_check CHECK (((operation)::text = ANY ((ARRAY['PREPARE'::character varying, 'ALIGN'::character varying, 'SEMANTIC'::character varying, 'IPA'::character varying, 'TTS'::character varying, 'CONTENT'::character varying])::text[]))),
    CONSTRAINT lesson_authoring_job_result_object CHECK (((result_json IS NULL) OR (jsonb_typeof(result_json) = 'object'::text)))
);

CREATE TABLE public.lesson_metadata (
    lesson_id uuid NOT NULL,
    title character varying(500) NOT NULL,
    description character varying(5000) DEFAULT ''::character varying NOT NULL,
    image_url text,
    visible boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    title_automatic boolean DEFAULT false NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT lesson_metadata_title_check CHECK ((length(TRIM(BOTH FROM title)) > 0))
);

CREATE TABLE public.lesson_youtube_channel (
    channel_id character varying(128) NOT NULL,
    title character varying(500) NOT NULL,
    avatar_url text,
    synced_at timestamp with time zone,
    sync_error character varying(500),
    sync_job_id uuid,
    sync_completed boolean DEFAULT false NOT NULL,
    visible boolean DEFAULT true NOT NULL
);

CREATE TABLE public.lesson_youtube_source (
    video_id character varying(11) NOT NULL,
    title text NOT NULL,
    duration_ms bigint NOT NULL,
    embed_allowed boolean NOT NULL,
    audio_object_key text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    channel_id character varying(128),
    channel_title character varying(500),
    thumbnail_url text,
    CONSTRAINT lesson_youtube_source_duration_ms_check CHECK ((duration_ms > 0))
);

CREATE VIEW public.lesson_availability AS
 SELECT l.id AS lesson_id,
    (((l.status)::text = 'PUBLISHED'::text) AND (m.deleted_at IS NULL) AND m.visible AND COALESCE(c.visible, true)) AS available,
    COALESCE(c.visible, true) AS channel_visible
   FROM ((((public.lesson l
     JOIN public.lesson_metadata m ON ((m.lesson_id = l.id)))
     LEFT JOIN public.lesson_authoring a ON ((a.lesson_id = l.id)))
     LEFT JOIN public.lesson_youtube_source s ON (((s.video_id)::text = (a.video_id)::text)))
     LEFT JOIN public.lesson_youtube_channel c ON (((c.channel_id)::text = (s.channel_id)::text)));

CREATE TABLE public.lesson_build_job (
    job_id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    build_plan_json jsonb NOT NULL,
    provider_snapshot_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.lesson_build_job_step (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    step character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    ai_invocation_id uuid,
    output_artifact_key text,
    error_message text
);

CREATE TABLE public.lesson_category (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    translations jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT lesson_category_name_check CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT lesson_category_translations_check CHECK ((jsonb_typeof(translations) = 'object'::text))
);

CREATE TABLE public.lesson_category_membership (
    lesson_id uuid NOT NULL,
    category_id uuid NOT NULL
);

CREATE TABLE public.lesson_job_diagnostic (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    invocation_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone DEFAULT (now() + '7 days'::interval) NOT NULL,
    payload jsonb NOT NULL
);

CREATE TABLE public.lesson_practice_attempt (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    activity_id uuid NOT NULL,
    sentence_id uuid,
    activity_type character varying(64) NOT NULL,
    server_score integer NOT NULL,
    answer_text text,
    detail_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lesson_practice_attempt_server_score_check CHECK (((server_score >= 0) AND (server_score <= 100)))
);

CREATE TABLE public.lesson_progress (
    learner_id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    status character varying(32) DEFAULT 'NOT_STARTED'::character varying NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    last_activity_at timestamp with time zone
);

CREATE TABLE public.lesson_sentence (
    id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    "position" integer NOT NULL,
    text text NOT NULL,
    audio_start_ms bigint,
    audio_end_ms bigint,
    audio_clip_object_key text,
    CONSTRAINT lesson_sentence_audio_start_ms_check CHECK (((audio_start_ms IS NULL) OR (audio_start_ms >= 0))),
    CONSTRAINT lesson_sentence_check CHECK (((audio_end_ms IS NULL) OR (audio_end_ms >= audio_start_ms)))
);

CREATE TABLE public.lesson_source_preview (
    id uuid NOT NULL,
    video_id character varying(32) NOT NULL,
    start_ms bigint NOT NULL,
    end_ms bigint NOT NULL,
    job_id uuid NOT NULL,
    audio_object_key text,
    peaks jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lesson_source_preview_check CHECK (((end_ms > start_ms) AND ((end_ms - start_ms) <= 600000))),
    CONSTRAINT lesson_source_preview_start_ms_check CHECK ((start_ms >= 0))
);

CREATE TABLE public.lesson_word_timestamp (
    id uuid NOT NULL,
    sentence_id uuid NOT NULL,
    "position" integer NOT NULL,
    surface_text text NOT NULL,
    start_ms bigint NOT NULL,
    end_ms bigint NOT NULL,
    CONSTRAINT lesson_word_timestamp_check CHECK ((end_ms >= start_ms)),
    CONSTRAINT lesson_word_timestamp_start_ms_check CHECK ((start_ms >= 0))
);

CREATE TABLE public.lesson_youtube_segment (
    video_id character varying(11) NOT NULL,
    start_ms bigint NOT NULL,
    end_ms bigint NOT NULL,
    normalization_version character varying(32) NOT NULL,
    audio_object_key text NOT NULL,
    CONSTRAINT lesson_youtube_segment_check CHECK ((end_ms > start_ms)),
    CONSTRAINT lesson_youtube_segment_start_ms_check CHECK ((start_ms >= 0))
);

CREATE TABLE public.lexicon_entry (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    headword_id uuid NOT NULL,
    package_entry_id uuid NOT NULL,
    display_form text NOT NULL,
    lookup_form text NOT NULL,
    entry_type character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT lexicon_entry_entry_type_check CHECK (((entry_type)::text = ANY ((ARRAY['WORD'::character varying, 'PHRASE'::character varying, 'PHRASAL_VERB'::character varying, 'IDIOM'::character varying, 'COLLOCATION'::character varying])::text[])))
);

CREATE TABLE public.lexicon_form (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    item_id uuid NOT NULL,
    package_form_id uuid NOT NULL,
    form text NOT NULL,
    normalized_form text NOT NULL,
    tags jsonb DEFAULT '[]'::jsonb NOT NULL
);

CREATE TABLE public.lexicon_headword (
    id uuid NOT NULL,
    language character varying(16) NOT NULL,
    identity_form text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.lexicon_item (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    package_item_id uuid NOT NULL,
    part_of_speech text NOT NULL,
    pos_title text NOT NULL,
    etymology_number integer,
    etymology_text text,
    order_index integer NOT NULL
);

CREATE TABLE public.lexicon_pronunciation (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    item_id uuid NOT NULL,
    package_pronunciation_id uuid NOT NULL,
    accent text,
    ipa text,
    audio_file text,
    audio_url text,
    source_url text,
    cached_audio_object_key text,
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    pronunciation_context jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT lexicon_pronunciation_context_object CHECK ((jsonb_typeof(pronunciation_context) = 'object'::text))
);

CREATE TABLE public.lexicon_sense (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    item_id uuid NOT NULL,
    package_sense_id uuid NOT NULL,
    "position" integer NOT NULL,
    definition_en text,
    raw_glosses jsonb DEFAULT '[]'::jsonb NOT NULL,
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    examples jsonb DEFAULT '[]'::jsonb NOT NULL,
    translation_vi text,
    matched_qualifier text,
    translation_status character varying(32) NOT NULL,
    definitions jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_context jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT lexicon_definitions_object CHECK ((jsonb_typeof(definitions) = 'object'::text))
);

CREATE TABLE public.lexicon_translation (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    item_id uuid,
    sense_id uuid,
    package_translation_id uuid NOT NULL,
    word_vi text NOT NULL,
    source text NOT NULL,
    source_scope text NOT NULL,
    source_sense_qualifier text,
    link_status character varying(32) NOT NULL,
    unlinked_reason text,
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_pos text,
    source_context jsonb,
    CONSTRAINT lexicon_translation_check CHECK ((((link_status)::text <> ALL ((ARRAY['DIRECT_SENSE'::character varying, 'QUALIFIER_MATCH'::character varying])::text[])) OR (sense_id IS NOT NULL))),
    CONSTRAINT lexicon_translation_check1 CHECK (((sense_id IS NULL) OR (item_id IS NOT NULL))),
    CONSTRAINT lexicon_translation_entry_candidate_scope CHECK ((((link_status)::text <> 'ENTRY_CANDIDATE'::text) OR ((item_id IS NULL) AND (sense_id IS NULL)))),
    CONSTRAINT lexicon_translation_item_candidate_scope CHECK ((((link_status)::text <> 'ITEM_CANDIDATE'::text) OR ((item_id IS NOT NULL) AND (sense_id IS NULL)))),
    CONSTRAINT lexicon_translation_link_status_check CHECK (((link_status)::text = ANY ((ARRAY['DIRECT_SENSE'::character varying, 'QUALIFIER_MATCH'::character varying, 'ITEM_CANDIDATE'::character varying, 'ENTRY_CANDIDATE'::character varying, 'UNLINKED'::character varying])::text[]))),
    CONSTRAINT lexicon_translation_source_context_object CHECK (((source_context IS NULL) OR (jsonb_typeof(source_context) = 'object'::text)))
);

CREATE TABLE public.media_asset_use (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    package_use_id text NOT NULL,
    asset_id character varying(64) NOT NULL,
    role character varying(16) NOT NULL,
    group_id uuid,
    item_id uuid,
    source_reference text,
    media_version integer,
    CONSTRAINT media_asset_use_check CHECK (((group_id IS NULL) <> (item_id IS NULL))),
    CONSTRAINT media_asset_use_role_check CHECK (((role)::text = ANY ((ARRAY['audio'::character varying, 'image'::character varying])::text[])))
);

CREATE TABLE public.media_blob (
    sha256 character varying(64) NOT NULL,
    mime_type character varying(64) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_object_key text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT media_blob_sha256_check CHECK (((sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT media_blob_size_bytes_check CHECK ((size_bytes >= 0)),
    CONSTRAINT media_blob_storage_object_key_check CHECK (((storage_object_key <> ''::text) AND (storage_object_key !~ '^[A-Za-z][A-Za-z0-9+.-]*:'::text) AND (storage_object_key !~ '[?#]'::text) AND (storage_object_key !~ '^/'::text)))
);

CREATE TABLE public.mission_definition (
    id uuid NOT NULL,
    code character varying(100) NOT NULL,
    title text NOT NULL,
    metric character varying(64) NOT NULL,
    target integer NOT NULL,
    diamond_reward integer DEFAULT 0 NOT NULL,
    recurrence character varying(32) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT mission_definition_diamond_reward_check CHECK ((diamond_reward >= 0)),
    CONSTRAINT mission_definition_target_check CHECK ((target > 0))
);

CREATE TABLE public.mission_progress (
    learner_id uuid NOT NULL,
    mission_id uuid NOT NULL,
    period_key character varying(64) NOT NULL,
    progress integer DEFAULT 0 NOT NULL,
    completed_at timestamp with time zone,
    claimed_at timestamp with time zone
);

CREATE TABLE public.module_runtime_config (
    owner_module character varying(100) NOT NULL,
    config_key character varying(100) NOT NULL,
    schema_version integer NOT NULL,
    config_json jsonb NOT NULL,
    updated_by uuid,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT module_runtime_config_schema_version_check CHECK ((schema_version >= 1))
);

CREATE TABLE public.release_media_asset (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    package_asset_id character varying(64) NOT NULL,
    blob_sha256 character varying(64) NOT NULL,
    package_path text NOT NULL,
    source_paths jsonb NOT NULL
);

CREATE TABLE public.sentence_pronunciation (
    id uuid NOT NULL,
    sentence_id uuid NOT NULL,
    accent character varying(16) NOT NULL,
    ipa text NOT NULL,
    provider character varying(64),
    model text,
    text_hash character varying(128) NOT NULL,
    generated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.speech_attempt (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    lesson_id uuid,
    sentence_id uuid,
    recording_object_key text NOT NULL,
    asr_text text,
    word_accuracy integer,
    timing_score integer,
    fluency_score integer,
    deep_judge_score integer,
    raw_result_object_key text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT speech_attempt_deep_judge_score_check CHECK (((deep_judge_score >= 0) AND (deep_judge_score <= 100))),
    CONSTRAINT speech_attempt_fluency_score_check CHECK (((fluency_score >= 0) AND (fluency_score <= 100))),
    CONSTRAINT speech_attempt_timing_score_check CHECK (((timing_score >= 0) AND (timing_score <= 100))),
    CONSTRAINT speech_attempt_word_accuracy_check CHECK (((word_accuracy >= 0) AND (word_accuracy <= 100)))
);

CREATE TABLE public.toeic_attempt (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    test_version_id uuid NOT NULL,
    mode character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    raw_listening_correct integer,
    raw_reading_correct integer,
    listening_score integer,
    reading_score integer,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    submitted_at timestamp with time zone,
    CONSTRAINT toeic_attempt_mode_check CHECK (((mode)::text = ANY ((ARRAY['FULL_TEST'::character varying, 'DRILL'::character varying])::text[])))
);

CREATE TABLE public.toeic_attempt_answer (
    attempt_id uuid NOT NULL,
    placement_id uuid NOT NULL,
    test_version_id uuid NOT NULL,
    answer character varying(8),
    correct boolean,
    answered_at timestamp with time zone
);

CREATE TABLE public.toeic_document (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    group_id uuid NOT NULL,
    package_doc_id text NOT NULL,
    ordinal integer NOT NULL,
    document_type character varying(64),
    html text NOT NULL,
    parse_status character varying(32),
    CONSTRAINT toeic_document_ordinal_check CHECK (((ordinal >= 1) AND (ordinal <= 3)))
);

CREATE TABLE public.toeic_placement (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    package_placement_id uuid NOT NULL,
    test_version_id uuid NOT NULL,
    item_id uuid NOT NULL,
    group_id uuid,
    section character varying(16) NOT NULL,
    part integer NOT NULL,
    question_number integer NOT NULL,
    gap_number integer,
    order_index integer NOT NULL,
    CONSTRAINT toeic_placement_part_check CHECK (((part >= 1) AND (part <= 7))),
    CONSTRAINT toeic_placement_question_number_check CHECK (((question_number >= 1) AND (question_number <= 200))),
    CONSTRAINT toeic_placement_section_check CHECK (((section)::text = ANY ((ARRAY['listening'::character varying, 'reading'::character varying])::text[])))
);

CREATE TABLE public.toeic_stimulus_group (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    test_version_id uuid NOT NULL,
    package_group_id uuid NOT NULL,
    part integer NOT NULL,
    kind character varying(16) NOT NULL,
    title text,
    order_index integer,
    difficulty_level integer,
    question_numbers integer[] NOT NULL,
    transcript_en text,
    content_translation_vi text,
    vocabulary_note_vi text,
    render_html text,
    source_html text,
    source_html_sha256 character varying(64),
    document_parse_status character varying(32),
    document_count integer NOT NULL,
    source_issues jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT toeic_source_issues_array CHECK ((jsonb_typeof(source_issues) = 'array'::text)),
    CONSTRAINT toeic_stimulus_group_kind_check CHECK (((kind)::text = ANY ((ARRAY['AUDIO'::character varying, 'TEXT'::character varying])::text[]))),
    CONSTRAINT toeic_stimulus_group_part_check CHECK ((part = ANY (ARRAY[3, 4, 6, 7])))
);

CREATE TABLE public.toeic_test_catalog (
    id uuid NOT NULL,
    year integer NOT NULL,
    test_number integer NOT NULL,
    publication_status character varying(16) NOT NULL,
    access_mode character varying(16) NOT NULL,
    required_feature_key character varying(64),
    reconciliation_status character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT toeic_test_catalog_access_mode_check CHECK (((access_mode)::text = ANY ((ARRAY['PUBLIC'::character varying, 'FEATURE'::character varying])::text[]))),
    CONSTRAINT toeic_test_catalog_check CHECK (((((access_mode)::text = 'PUBLIC'::text) AND (required_feature_key IS NULL)) OR (((access_mode)::text = 'FEATURE'::text) AND (required_feature_key IS NOT NULL)))),
    CONSTRAINT toeic_test_catalog_publication_status_check CHECK (((publication_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'HIDDEN'::character varying, 'ARCHIVED'::character varying])::text[]))),
    CONSTRAINT toeic_test_catalog_reconciliation_status_check CHECK (((reconciliation_status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'PROVISIONAL'::character varying])::text[])))
);

CREATE TABLE public.toeic_test_version (
    id uuid NOT NULL,
    release_id uuid NOT NULL,
    catalog_id uuid NOT NULL,
    package_test_id uuid NOT NULL,
    name text NOT NULL,
    year integer NOT NULL,
    test_number integer NOT NULL,
    set_id uuid NOT NULL,
    order_index integer,
    source_label text,
    listening_duration_seconds integer,
    reading_duration_seconds integer,
    total_questions integer NOT NULL,
    difficulty_level integer,
    source_is_free boolean NOT NULL,
    source_is_hidden boolean NOT NULL,
    media_version integer,
    CONSTRAINT toeic_test_version_total_questions_check CHECK ((total_questions = 200))
);

CREATE TABLE public.user_entitlement_grant (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    feature_key character varying(64) NOT NULL,
    status character varying(16) NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_until timestamp with time zone,
    source_type character varying(64) NOT NULL,
    source_reference text,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    revoked_by text,
    revocation_reason text,
    CONSTRAINT user_entitlement_grant_check CHECK (((valid_until IS NULL) OR (valid_until > valid_from))),
    CONSTRAINT user_entitlement_grant_check1 CHECK (((((status)::text = 'ACTIVE'::text) AND (revoked_at IS NULL) AND (revoked_by IS NULL)) OR (((status)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL) AND (revoked_by IS NOT NULL)))),
    CONSTRAINT user_entitlement_grant_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE public.vocabulary_card (
    id uuid NOT NULL,
    learner_id uuid NOT NULL,
    headword_id uuid NOT NULL,
    source_context_type character varying(64),
    source_context_id uuid,
    next_review_at timestamp with time zone NOT NULL,
    stability numeric(12,4) DEFAULT 1 NOT NULL,
    difficulty numeric(12,4) DEFAULT 5 NOT NULL,
    lapse_count integer DEFAULT 0 NOT NULL,
    review_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    suspended_at timestamp with time zone
);

CREATE TABLE public.vocabulary_review (
    id uuid NOT NULL,
    card_id uuid NOT NULL,
    rating character varying(16) NOT NULL,
    previous_due_at timestamp with time zone,
    next_due_at timestamp with time zone NOT NULL,
    stability numeric(12,4),
    difficulty numeric(12,4),
    reviewed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT vocabulary_review_rating_check CHECK (((rating)::text = ANY ((ARRAY['AGAIN'::character varying, 'HARD'::character varying, 'GOOD'::character varying, 'EASY'::character varying])::text[])))
);

ALTER TABLE ONLY public.ai_capability_route
    ADD CONSTRAINT ai_capability_route_capability_provider_id_model_key UNIQUE (capability, provider_id, model);

ALTER TABLE ONLY public.ai_capability_route
    ADD CONSTRAINT ai_capability_route_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.ai_invocation
    ADD CONSTRAINT ai_invocation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.ai_provider
    ADD CONSTRAINT ai_provider_code_key UNIQUE (code);

ALTER TABLE ONLY public.ai_provider
    ADD CONSTRAINT ai_provider_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_keycloak_subject_key UNIQUE (keycloak_subject);

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.assessment_item
    ADD CONSTRAINT assessment_item_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.assessment_item
    ADD CONSTRAINT assessment_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.assessment_item
    ADD CONSTRAINT assessment_item_release_id_package_item_id_key UNIQUE (release_id, package_item_id);

ALTER TABLE ONLY public.assessment_session
    ADD CONSTRAINT assessment_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.background_job
    ADD CONSTRAINT background_job_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.chat_conversation
    ADD CONSTRAINT chat_conversation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT chat_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.content_ai_request
    ADD CONSTRAINT content_ai_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.content_asset
    ADD CONSTRAINT content_asset_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.content_audit
    ADD CONSTRAINT content_audit_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.content_document
    ADD CONSTRAINT content_document_pkey PRIMARY KEY (namespace, id);

ALTER TABLE ONLY public.content_publication
    ADD CONSTRAINT content_publication_id_namespace_document_id_key UNIQUE (id, namespace, document_id);

ALTER TABLE ONLY public.content_publication
    ADD CONSTRAINT content_publication_namespace_document_id_revision_key UNIQUE (namespace, document_id, revision);

ALTER TABLE ONLY public.content_publication
    ADD CONSTRAINT content_publication_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.content_quality_report
    ADD CONSTRAINT content_quality_report_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.content_search_term
    ADD CONSTRAINT content_search_term_pkey PRIMARY KEY (namespace, document_id, kind, term, display_form);

ALTER TABLE ONLY public.content_source_alias
    ADD CONSTRAINT content_source_alias_pkey PRIMARY KEY (namespace, source_id);

ALTER TABLE ONLY public.content_source_binding
    ADD CONSTRAINT content_source_binding_pkey PRIMARY KEY (namespace, document_id);

ALTER TABLE ONLY public.curriculum_enrollment
    ADD CONSTRAINT curriculum_enrollment_pkey PRIMARY KEY (learner_id, path_id);

ALTER TABLE ONLY public.curriculum_item
    ADD CONSTRAINT curriculum_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.curriculum_item_progress
    ADD CONSTRAINT curriculum_item_progress_pkey PRIMARY KEY (learner_id, item_id);

ALTER TABLE ONLY public.curriculum_item
    ADD CONSTRAINT curriculum_item_section_id_position_key UNIQUE (section_id, "position");

ALTER TABLE ONLY public.curriculum_path
    ADD CONSTRAINT curriculum_path_code_key UNIQUE (code);

ALTER TABLE ONLY public.curriculum_path
    ADD CONSTRAINT curriculum_path_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.curriculum_section
    ADD CONSTRAINT curriculum_section_path_id_position_key UNIQUE (path_id, "position");

ALTER TABLE ONLY public.curriculum_section
    ADD CONSTRAINT curriculum_section_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.data_license
    ADD CONSTRAINT data_license_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.data_maintenance_run
    ADD CONSTRAINT data_maintenance_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.data_maintenance_work
    ADD CONSTRAINT data_maintenance_work_pkey PRIMARY KEY (fingerprint);

ALTER TABLE ONLY public.data_source
    ADD CONSTRAINT data_source_code_key UNIQUE (code);

ALTER TABLE ONLY public.data_source
    ADD CONSTRAINT data_source_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.dataset_active_release
    ADD CONSTRAINT dataset_active_release_pkey PRIMARY KEY (domain);

ALTER TABLE ONLY public.dataset_import_run
    ADD CONSTRAINT dataset_import_run_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.dataset_release
    ADD CONSTRAINT dataset_release_domain_checksum_sha256_key UNIQUE (domain, checksum_sha256);

ALTER TABLE ONLY public.dataset_release
    ADD CONSTRAINT dataset_release_domain_package_version_key UNIQUE (domain, package_version);

ALTER TABLE ONLY public.dataset_release
    ADD CONSTRAINT dataset_release_id_domain_key UNIQUE (id, domain);

ALTER TABLE ONLY public.dataset_release
    ADD CONSTRAINT dataset_release_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.diamond_transaction
    ADD CONSTRAINT diamond_transaction_idempotency_key_key UNIQUE (idempotency_key);

ALTER TABLE ONLY public.diamond_transaction
    ADD CONSTRAINT diamond_transaction_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.diamond_wallet
    ADD CONSTRAINT diamond_wallet_pkey PRIMARY KEY (learner_id);

ALTER TABLE ONLY public.entitlement_feature
    ADD CONSTRAINT entitlement_feature_pkey PRIMARY KEY (feature_key);

ALTER TABLE ONLY public.event_publication
    ADD CONSTRAINT event_publication_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_bank_catalog
    ADD CONSTRAINT grammar_bank_catalog_id_year_key UNIQUE (id, year);

ALTER TABLE ONLY public.grammar_bank_catalog
    ADD CONSTRAINT grammar_bank_catalog_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_bank_version
    ADD CONSTRAINT grammar_bank_version_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.grammar_bank_version
    ADD CONSTRAINT grammar_bank_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_bank_version
    ADD CONSTRAINT grammar_bank_version_release_id_catalog_id_key UNIQUE (release_id, catalog_id);

ALTER TABLE ONLY public.grammar_bank_version
    ADD CONSTRAINT grammar_bank_version_release_id_package_bank_id_key UNIQUE (release_id, package_bank_id);

ALTER TABLE ONLY public.grammar_difficulty_level
    ADD CONSTRAINT grammar_difficulty_level_pkey PRIMARY KEY (level);

ALTER TABLE ONLY public.grammar_difficulty_level_version
    ADD CONSTRAINT grammar_difficulty_level_versio_release_id_package_level_id_key UNIQUE (release_id, package_level_id);

ALTER TABLE ONLY public.grammar_difficulty_level_version
    ADD CONSTRAINT grammar_difficulty_level_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_difficulty_level_version
    ADD CONSTRAINT grammar_difficulty_level_version_release_id_level_key UNIQUE (release_id, level);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_release_id_package_membership_id_key UNIQUE (release_id, package_membership_id);

ALTER TABLE ONLY public.grammar_practice_attempt
    ADD CONSTRAINT grammar_practice_attempt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_subtopic_catalog
    ADD CONSTRAINT grammar_subtopic_catalog_id_topic_catalog_id_key UNIQUE (id, topic_catalog_id);

ALTER TABLE ONLY public.grammar_subtopic_catalog
    ADD CONSTRAINT grammar_subtopic_catalog_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_id_topic_version_id_release_id_key UNIQUE (id, topic_version_id, release_id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_release_id_catalog_id_key UNIQUE (release_id, catalog_id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_release_id_package_subtopic_id_key UNIQUE (release_id, package_subtopic_id);

ALTER TABLE ONLY public.grammar_topic_catalog
    ADD CONSTRAINT grammar_topic_catalog_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_id_catalog_id_release_id_key UNIQUE (id, catalog_id, release_id);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_id_package_topic_id_release_id_key UNIQUE (id, package_topic_id, release_id);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_release_id_catalog_id_key UNIQUE (release_id, catalog_id);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_release_id_package_topic_id_key UNIQUE (release_id, package_topic_id);

ALTER TABLE ONLY public.learner_daily_activity
    ADD CONSTRAINT learner_daily_activity_pkey PRIMARY KEY (learner_id, activity_date);

ALTER TABLE ONLY public.learner_level
    ADD CONSTRAINT learner_level_pkey PRIMARY KEY (learner_id);

ALTER TABLE ONLY public.learner_profile
    ADD CONSTRAINT learner_profile_pkey PRIMARY KEY (learner_id);

ALTER TABLE ONLY public.learner_skill_summary
    ADD CONSTRAINT learner_skill_summary_pkey PRIMARY KEY (learner_id, skill_code);

ALTER TABLE ONLY public.learner_weakness
    ADD CONSTRAINT learner_weakness_pkey PRIMARY KEY (learner_id, weakness_type, weakness_key);

ALTER TABLE ONLY public.lesson_activity
    ADD CONSTRAINT lesson_activity_lesson_id_activity_type_key UNIQUE (lesson_id, activity_type);

ALTER TABLE ONLY public.lesson_activity
    ADD CONSTRAINT lesson_activity_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_activity_progress
    ADD CONSTRAINT lesson_activity_progress_pkey PRIMARY KEY (learner_id, activity_id);

ALTER TABLE ONLY public.lesson_activity_sentence
    ADD CONSTRAINT lesson_activity_sentence_pkey PRIMARY KEY (activity_id, sentence_id);

ALTER TABLE ONLY public.lesson_annotation
    ADD CONSTRAINT lesson_annotation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_audio_source
    ADD CONSTRAINT lesson_audio_source_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_authoring_job
    ADD CONSTRAINT lesson_authoring_job_pkey PRIMARY KEY (job_id);

ALTER TABLE ONLY public.lesson_authoring
    ADD CONSTRAINT lesson_authoring_pkey PRIMARY KEY (lesson_id);

ALTER TABLE ONLY public.lesson_build_job
    ADD CONSTRAINT lesson_build_job_pkey PRIMARY KEY (job_id);

ALTER TABLE ONLY public.lesson_build_job_step
    ADD CONSTRAINT lesson_build_job_step_job_id_step_key UNIQUE (job_id, step);

ALTER TABLE ONLY public.lesson_build_job_step
    ADD CONSTRAINT lesson_build_job_step_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_category_membership
    ADD CONSTRAINT lesson_category_membership_pkey PRIMARY KEY (lesson_id, category_id);

ALTER TABLE ONLY public.lesson_category
    ADD CONSTRAINT lesson_category_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_job_diagnostic
    ADD CONSTRAINT lesson_job_diagnostic_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_metadata
    ADD CONSTRAINT lesson_metadata_pkey PRIMARY KEY (lesson_id);

ALTER TABLE ONLY public.lesson
    ADD CONSTRAINT lesson_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_practice_attempt
    ADD CONSTRAINT lesson_practice_attempt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_progress
    ADD CONSTRAINT lesson_progress_pkey PRIMARY KEY (learner_id, lesson_id);

ALTER TABLE ONLY public.lesson_sentence
    ADD CONSTRAINT lesson_sentence_lesson_id_position_key UNIQUE (lesson_id, "position");

ALTER TABLE ONLY public.lesson_sentence
    ADD CONSTRAINT lesson_sentence_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_source_preview
    ADD CONSTRAINT lesson_source_preview_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_word_timestamp
    ADD CONSTRAINT lesson_word_timestamp_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lesson_word_timestamp
    ADD CONSTRAINT lesson_word_timestamp_sentence_id_position_key UNIQUE (sentence_id, "position");

ALTER TABLE ONLY public.lesson_youtube_channel
    ADD CONSTRAINT lesson_youtube_channel_pkey PRIMARY KEY (channel_id);

ALTER TABLE ONLY public.lesson_youtube_segment
    ADD CONSTRAINT lesson_youtube_segment_pkey PRIMARY KEY (video_id, start_ms, end_ms, normalization_version);

ALTER TABLE ONLY public.lesson_youtube_source
    ADD CONSTRAINT lesson_youtube_source_pkey PRIMARY KEY (video_id);

ALTER TABLE ONLY public.lexicon_entry
    ADD CONSTRAINT lexicon_entry_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.lexicon_entry
    ADD CONSTRAINT lexicon_entry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lexicon_entry
    ADD CONSTRAINT lexicon_entry_release_id_headword_id_key UNIQUE (release_id, headword_id);

ALTER TABLE ONLY public.lexicon_entry
    ADD CONSTRAINT lexicon_entry_release_id_package_entry_id_key UNIQUE (release_id, package_entry_id);

ALTER TABLE ONLY public.lexicon_form
    ADD CONSTRAINT lexicon_form_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lexicon_form
    ADD CONSTRAINT lexicon_form_release_id_package_form_id_key UNIQUE (release_id, package_form_id);

ALTER TABLE ONLY public.lexicon_headword
    ADD CONSTRAINT lexicon_headword_language_identity_form_key UNIQUE (language, identity_form);

ALTER TABLE ONLY public.lexicon_headword
    ADD CONSTRAINT lexicon_headword_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lexicon_item
    ADD CONSTRAINT lexicon_item_id_entry_id_release_id_key UNIQUE (id, entry_id, release_id);

ALTER TABLE ONLY public.lexicon_item
    ADD CONSTRAINT lexicon_item_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.lexicon_item
    ADD CONSTRAINT lexicon_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lexicon_item
    ADD CONSTRAINT lexicon_item_release_id_package_item_id_key UNIQUE (release_id, package_item_id);

ALTER TABLE ONLY public.lexicon_pronunciation
    ADD CONSTRAINT lexicon_pronunciation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lexicon_pronunciation
    ADD CONSTRAINT lexicon_pronunciation_release_id_package_pronunciation_id_key UNIQUE (release_id, package_pronunciation_id);

ALTER TABLE ONLY public.lexicon_sense
    ADD CONSTRAINT lexicon_sense_id_item_id_entry_id_release_id_key UNIQUE (id, item_id, entry_id, release_id);

ALTER TABLE ONLY public.lexicon_sense
    ADD CONSTRAINT lexicon_sense_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.lexicon_sense
    ADD CONSTRAINT lexicon_sense_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lexicon_sense
    ADD CONSTRAINT lexicon_sense_release_id_package_sense_id_key UNIQUE (release_id, package_sense_id);

ALTER TABLE ONLY public.lexicon_translation
    ADD CONSTRAINT lexicon_translation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.lexicon_translation
    ADD CONSTRAINT lexicon_translation_release_id_package_translation_id_key UNIQUE (release_id, package_translation_id);

ALTER TABLE ONLY public.media_asset_use
    ADD CONSTRAINT media_asset_use_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.media_asset_use
    ADD CONSTRAINT media_asset_use_release_id_package_use_id_key UNIQUE (release_id, package_use_id);

ALTER TABLE ONLY public.media_blob
    ADD CONSTRAINT media_blob_pkey PRIMARY KEY (sha256);

ALTER TABLE ONLY public.media_blob
    ADD CONSTRAINT media_blob_storage_object_key_key UNIQUE (storage_object_key);

ALTER TABLE ONLY public.mission_definition
    ADD CONSTRAINT mission_definition_code_key UNIQUE (code);

ALTER TABLE ONLY public.mission_definition
    ADD CONSTRAINT mission_definition_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.mission_progress
    ADD CONSTRAINT mission_progress_pkey PRIMARY KEY (learner_id, mission_id, period_key);

ALTER TABLE ONLY public.module_runtime_config
    ADD CONSTRAINT module_runtime_config_pkey PRIMARY KEY (owner_module, config_key);

ALTER TABLE ONLY public.release_media_asset
    ADD CONSTRAINT release_media_asset_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.release_media_asset
    ADD CONSTRAINT release_media_asset_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.release_media_asset
    ADD CONSTRAINT release_media_asset_release_id_package_asset_id_key UNIQUE (release_id, package_asset_id);

ALTER TABLE ONLY public.sentence_pronunciation
    ADD CONSTRAINT sentence_pronunciation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.sentence_pronunciation
    ADD CONSTRAINT sentence_pronunciation_sentence_id_accent_text_hash_key UNIQUE (sentence_id, accent, text_hash);

ALTER TABLE ONLY public.speech_attempt
    ADD CONSTRAINT speech_attempt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.toeic_attempt_answer
    ADD CONSTRAINT toeic_attempt_answer_pkey PRIMARY KEY (attempt_id, placement_id);

ALTER TABLE ONLY public.toeic_attempt
    ADD CONSTRAINT toeic_attempt_id_test_version_id_key UNIQUE (id, test_version_id);

ALTER TABLE ONLY public.toeic_attempt
    ADD CONSTRAINT toeic_attempt_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.toeic_document
    ADD CONSTRAINT toeic_document_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.toeic_document
    ADD CONSTRAINT toeic_document_release_id_package_doc_id_key UNIQUE (release_id, package_doc_id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_id_test_version_id_key UNIQUE (id, test_version_id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_release_id_package_placement_id_key UNIQUE (release_id, package_placement_id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_test_version_id_question_number_key UNIQUE (test_version_id, question_number);

ALTER TABLE ONLY public.toeic_stimulus_group
    ADD CONSTRAINT toeic_stimulus_group_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.toeic_stimulus_group
    ADD CONSTRAINT toeic_stimulus_group_id_test_version_id_release_id_key UNIQUE (id, test_version_id, release_id);

ALTER TABLE ONLY public.toeic_stimulus_group
    ADD CONSTRAINT toeic_stimulus_group_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.toeic_stimulus_group
    ADD CONSTRAINT toeic_stimulus_group_release_id_package_group_id_key UNIQUE (release_id, package_group_id);

ALTER TABLE ONLY public.toeic_test_catalog
    ADD CONSTRAINT toeic_test_catalog_id_year_test_number_key UNIQUE (id, year, test_number);

ALTER TABLE ONLY public.toeic_test_catalog
    ADD CONSTRAINT toeic_test_catalog_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.toeic_test_catalog
    ADD CONSTRAINT toeic_test_catalog_year_test_number_key UNIQUE (year, test_number);

ALTER TABLE ONLY public.toeic_test_version
    ADD CONSTRAINT toeic_test_version_id_release_id_key UNIQUE (id, release_id);

ALTER TABLE ONLY public.toeic_test_version
    ADD CONSTRAINT toeic_test_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.toeic_test_version
    ADD CONSTRAINT toeic_test_version_release_id_catalog_id_key UNIQUE (release_id, catalog_id);

ALTER TABLE ONLY public.toeic_test_version
    ADD CONSTRAINT toeic_test_version_release_id_package_test_id_key UNIQUE (release_id, package_test_id);

ALTER TABLE ONLY public.user_entitlement_grant
    ADD CONSTRAINT user_entitlement_grant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.vocabulary_card
    ADD CONSTRAINT vocabulary_card_learner_id_headword_id_key UNIQUE (learner_id, headword_id);

ALTER TABLE ONLY public.vocabulary_card
    ADD CONSTRAINT vocabulary_card_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.vocabulary_review
    ADD CONSTRAINT vocabulary_review_pkey PRIMARY KEY (id);

CREATE INDEX ai_capability_route_lookup_idx ON public.ai_capability_route USING btree (capability, enabled, priority);

CREATE INDEX ai_invocation_capability_idx ON public.ai_invocation USING btree (capability, provider_code, model);

CREATE INDEX ai_invocation_time_idx ON public.ai_invocation USING btree (started_at DESC);

CREATE INDEX assessment_session_history_idx ON public.assessment_session USING btree (learner_id, domain, created_at DESC, id);

CREATE INDEX background_job_claim_idx ON public.background_job USING btree (status, next_retry_at, priority DESC, created_at);

CREATE INDEX background_job_lease_idx ON public.background_job USING btree (status, lease_until);

CREATE INDEX background_job_owner_idx ON public.background_job USING btree (owner_module, owner_reference_id);

CREATE INDEX content_audit_document_idx ON public.content_audit USING btree (namespace, document_id, id DESC);

CREATE INDEX content_document_list_idx ON public.content_document USING btree (namespace, status, updated_at DESC, id);

CREATE INDEX content_publication_history_idx ON public.content_publication USING btree (namespace, document_id, published_at DESC);

CREATE INDEX content_quality_latest ON public.content_quality_report USING btree (namespace, target_id, created_at DESC);

CREATE INDEX content_search_prefix_idx ON public.content_search_term USING btree (namespace, term text_pattern_ops, document_id);

CREATE INDEX curriculum_item_ref_idx ON public.curriculum_item USING btree (content_type, content_reference_id);

CREATE INDEX dataset_import_run_success_idx ON public.dataset_import_run USING btree (release_id) WHERE (((run_type)::text = 'APPLY'::text) AND ((status)::text = 'SUCCEEDED'::text));

CREATE INDEX diamond_tx_learner_idx ON public.diamond_transaction USING btree (learner_id, created_at DESC);

CREATE INDEX event_publication_by_completion_date_idx ON public.event_publication USING btree (completion_date);

CREATE INDEX event_publication_serialized_event_hash_idx ON public.event_publication USING hash (serialized_event);

CREATE INDEX grammar_attempt_learner_idx ON public.grammar_practice_attempt USING btree (learner_id, answered_at DESC);

CREATE INDEX grammar_membership_bank_idx ON public.grammar_membership USING btree (bank_version_id, order_index);

CREATE INDEX grammar_membership_difficulty_idx ON public.grammar_membership USING btree (release_id, difficulty_level, order_index);

CREATE INDEX grammar_membership_topic_idx ON public.grammar_membership USING btree (topic_version_id, subtopic_version_id, order_index);

CREATE INDEX lesson_activity_progress_activity_idx ON public.lesson_activity_progress USING btree (activity_id, status);

CREATE INDEX lesson_annotation_sentence_idx ON public.lesson_annotation USING btree (sentence_id, annotation_type);

CREATE INDEX lesson_attempt_learner_idx ON public.lesson_practice_attempt USING btree (learner_id, created_at DESC);

CREATE INDEX lesson_authoring_source_idx ON public.lesson_authoring USING btree (video_id, source_start_ms, source_end_ms);

CREATE INDEX lesson_category_membership_category_idx ON public.lesson_category_membership USING btree (category_id, lesson_id);

CREATE UNIQUE INDEX lesson_category_name_idx ON public.lesson_category USING btree (lower((name)::text));

CREATE INDEX lesson_job_diagnostic_job_idx ON public.lesson_job_diagnostic USING btree (job_id, created_at);

CREATE INDEX lesson_metadata_available_idx ON public.lesson_metadata USING btree (lesson_id) WHERE ((deleted_at IS NULL) AND visible);

CREATE INDEX lesson_source_preview_range_idx ON public.lesson_source_preview USING btree (video_id, start_ms, end_ms);

CREATE INDEX lexicon_entry_admin_cursor_idx ON public.lexicon_entry USING btree (release_id, id);

CREATE INDEX lexicon_entry_headword_idx ON public.lexicon_entry USING btree (headword_id, release_id);

CREATE INDEX lexicon_entry_lookup_idx ON public.lexicon_entry USING btree (release_id, lookup_form text_pattern_ops);

CREATE INDEX lexicon_form_entry_idx ON public.lexicon_form USING btree (release_id, entry_id);

CREATE INDEX lexicon_form_lookup_idx ON public.lexicon_form USING btree (release_id, normalized_form text_pattern_ops);

CREATE INDEX lexicon_item_admin_pos_idx ON public.lexicon_item USING btree (release_id, part_of_speech, entry_id);

CREATE INDEX lexicon_item_entry_idx ON public.lexicon_item USING btree (entry_id, order_index);

CREATE INDEX lexicon_pronunciation_entry_idx ON public.lexicon_pronunciation USING btree (release_id, entry_id);

CREATE INDEX lexicon_sense_admin_translation_idx ON public.lexicon_sense USING btree (release_id, translation_status, entry_id);

CREATE INDEX lexicon_sense_entry_idx ON public.lexicon_sense USING btree (release_id, entry_id);

CREATE INDEX lexicon_sense_item_idx ON public.lexicon_sense USING btree (item_id, "position");

CREATE INDEX lexicon_translation_entry_idx ON public.lexicon_translation USING btree (entry_id);

CREATE INDEX toeic_placement_test_idx ON public.toeic_placement USING btree (test_version_id, question_number);

CREATE INDEX user_entitlement_grant_lookup_idx ON public.user_entitlement_grant USING btree (user_id, feature_key, status, valid_from, valid_until);

CREATE INDEX vocabulary_due_idx ON public.vocabulary_card USING btree (learner_id, next_review_at);

CREATE TRIGGER assessment_item_finalized_insert AFTER INSERT ON public.assessment_item FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER assessment_item_snapshot_immutable BEFORE DELETE OR UPDATE ON public.assessment_item FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER assessment_item_snapshot_no_truncate BEFORE TRUNCATE ON public.assessment_item FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER assessment_session_snapshot_immutable BEFORE UPDATE ON public.assessment_session FOR EACH ROW EXECUTE FUNCTION public.protect_assessment_session();

CREATE TRIGGER content_audit_immutable BEFORE DELETE OR UPDATE ON public.content_audit FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER content_publication_immutable BEFORE DELETE OR UPDATE ON public.content_publication FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER dataset_active_release_guard BEFORE INSERT OR UPDATE OF release_id ON public.dataset_active_release FOR EACH ROW EXECUTE FUNCTION public.verify_release_activation();

CREATE TRIGGER dataset_release_snapshot_immutable BEFORE DELETE OR UPDATE ON public.dataset_release FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER dataset_release_snapshot_no_truncate BEFORE TRUNCATE ON public.dataset_release FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_bank_version_finalized_insert AFTER INSERT ON public.grammar_bank_version FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER grammar_bank_version_snapshot_immutable BEFORE DELETE OR UPDATE ON public.grammar_bank_version FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_bank_version_snapshot_no_truncate BEFORE TRUNCATE ON public.grammar_bank_version FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_difficulty_level_version_finalized_insert AFTER INSERT ON public.grammar_difficulty_level_version FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER grammar_difficulty_level_version_snapshot_immutable BEFORE DELETE OR UPDATE ON public.grammar_difficulty_level_version FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_difficulty_level_version_snapshot_no_truncate BEFORE TRUNCATE ON public.grammar_difficulty_level_version FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_membership_finalized_insert AFTER INSERT ON public.grammar_membership FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER grammar_membership_snapshot_immutable BEFORE DELETE OR UPDATE ON public.grammar_membership FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_membership_snapshot_no_truncate BEFORE TRUNCATE ON public.grammar_membership FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_subtopic_version_finalized_insert AFTER INSERT ON public.grammar_subtopic_version FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER grammar_subtopic_version_snapshot_immutable BEFORE DELETE OR UPDATE ON public.grammar_subtopic_version FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_subtopic_version_snapshot_no_truncate BEFORE TRUNCATE ON public.grammar_subtopic_version FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_topic_version_finalized_insert AFTER INSERT ON public.grammar_topic_version FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER grammar_topic_version_snapshot_immutable BEFORE DELETE OR UPDATE ON public.grammar_topic_version FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER grammar_topic_version_snapshot_no_truncate BEFORE TRUNCATE ON public.grammar_topic_version FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lesson_activities_published_guard BEFORE INSERT OR DELETE OR UPDATE ON public.lesson_activity FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lesson_annotation_same_lesson BEFORE INSERT OR UPDATE ON public.lesson_annotation FOR EACH ROW EXECUTE FUNCTION public.guard_lesson_annotation_scope();

CREATE TRIGGER lesson_annotations_published_guard BEFORE INSERT OR DELETE OR UPDATE ON public.lesson_annotation FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lesson_authoring_published_guard BEFORE DELETE OR UPDATE ON public.lesson_authoring FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lesson_candidate_same_lesson BEFORE INSERT OR UPDATE ON public.lesson_activity_sentence FOR EACH ROW EXECUTE FUNCTION public.guard_lesson_candidate_membership();

CREATE TRIGGER lesson_candidates_published_guard BEFORE INSERT OR DELETE OR UPDATE ON public.lesson_activity_sentence FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lesson_metadata_created AFTER INSERT ON public.lesson FOR EACH ROW EXECUTE FUNCTION public.initialize_lesson_metadata();

CREATE TRIGGER lesson_pronunciation_published_guard BEFORE INSERT OR DELETE OR UPDATE ON public.sentence_pronunciation FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lesson_published_guard BEFORE DELETE OR UPDATE ON public.lesson FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lesson_sentence_published_guard BEFORE INSERT OR DELETE OR UPDATE ON public.lesson_sentence FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lesson_words_published_guard BEFORE INSERT OR DELETE OR UPDATE ON public.lesson_word_timestamp FOR EACH ROW EXECUTE FUNCTION public.guard_published_lesson_content();

CREATE TRIGGER lexicon_entry_finalized_insert AFTER INSERT ON public.lexicon_entry FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER lexicon_entry_snapshot_immutable BEFORE DELETE OR UPDATE ON public.lexicon_entry FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_entry_snapshot_no_truncate BEFORE TRUNCATE ON public.lexicon_entry FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_form_finalized_insert AFTER INSERT ON public.lexicon_form FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER lexicon_form_snapshot_immutable BEFORE DELETE OR UPDATE ON public.lexicon_form FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_form_snapshot_no_truncate BEFORE TRUNCATE ON public.lexicon_form FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_headword_snapshot_immutable BEFORE DELETE OR UPDATE ON public.lexicon_headword FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_headword_snapshot_no_truncate BEFORE TRUNCATE ON public.lexicon_headword FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_item_finalized_insert AFTER INSERT ON public.lexicon_item FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER lexicon_item_snapshot_immutable BEFORE DELETE OR UPDATE ON public.lexicon_item FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_item_snapshot_no_truncate BEFORE TRUNCATE ON public.lexicon_item FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_pronunciation_finalized_insert AFTER INSERT ON public.lexicon_pronunciation FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER lexicon_pronunciation_snapshot_immutable BEFORE DELETE OR UPDATE ON public.lexicon_pronunciation FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_pronunciation_snapshot_no_truncate BEFORE TRUNCATE ON public.lexicon_pronunciation FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_sense_finalized_insert AFTER INSERT ON public.lexicon_sense FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER lexicon_sense_snapshot_immutable BEFORE DELETE OR UPDATE ON public.lexicon_sense FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_sense_snapshot_no_truncate BEFORE TRUNCATE ON public.lexicon_sense FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_translation_finalized_insert AFTER INSERT ON public.lexicon_translation FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER lexicon_translation_snapshot_immutable BEFORE DELETE OR UPDATE ON public.lexicon_translation FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER lexicon_translation_snapshot_no_truncate BEFORE TRUNCATE ON public.lexicon_translation FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER media_asset_use_finalized_insert AFTER INSERT ON public.media_asset_use FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER media_asset_use_snapshot_immutable BEFORE DELETE OR UPDATE ON public.media_asset_use FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER media_asset_use_snapshot_no_truncate BEFORE TRUNCATE ON public.media_asset_use FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER media_blob_snapshot_immutable BEFORE DELETE OR UPDATE ON public.media_blob FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER media_blob_snapshot_no_truncate BEFORE TRUNCATE ON public.media_blob FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER release_media_asset_finalized_insert AFTER INSERT ON public.release_media_asset FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER release_media_asset_snapshot_immutable BEFORE DELETE OR UPDATE ON public.release_media_asset FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER release_media_asset_snapshot_no_truncate BEFORE TRUNCATE ON public.release_media_asset FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_document_finalized_insert AFTER INSERT ON public.toeic_document FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER toeic_document_snapshot_immutable BEFORE DELETE OR UPDATE ON public.toeic_document FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_document_snapshot_no_truncate BEFORE TRUNCATE ON public.toeic_document FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_placement_finalized_insert AFTER INSERT ON public.toeic_placement FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER toeic_placement_snapshot_immutable BEFORE DELETE OR UPDATE ON public.toeic_placement FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_placement_snapshot_no_truncate BEFORE TRUNCATE ON public.toeic_placement FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_stimulus_group_finalized_insert AFTER INSERT ON public.toeic_stimulus_group FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER toeic_stimulus_group_snapshot_immutable BEFORE DELETE OR UPDATE ON public.toeic_stimulus_group FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_stimulus_group_snapshot_no_truncate BEFORE TRUNCATE ON public.toeic_stimulus_group FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_test_version_finalized_insert AFTER INSERT ON public.toeic_test_version FOR EACH ROW EXECUTE FUNCTION public.reject_finalized_release_insert();

CREATE TRIGGER toeic_test_version_snapshot_immutable BEFORE DELETE OR UPDATE ON public.toeic_test_version FOR EACH ROW EXECUTE FUNCTION public.reject_snapshot_mutation();

CREATE TRIGGER toeic_test_version_snapshot_no_truncate BEFORE TRUNCATE ON public.toeic_test_version FOR EACH STATEMENT EXECUTE FUNCTION public.reject_snapshot_mutation();

ALTER TABLE ONLY public.ai_capability_route
    ADD CONSTRAINT ai_capability_route_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES public.ai_provider(id);

ALTER TABLE ONLY public.assessment_item
    ADD CONSTRAINT assessment_item_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.assessment_session
    ADD CONSTRAINT assessment_session_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id);

ALTER TABLE ONLY public.chat_conversation
    ADD CONSTRAINT chat_conversation_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT chat_message_ai_invocation_id_fkey FOREIGN KEY (ai_invocation_id) REFERENCES public.ai_invocation(id);

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT chat_message_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.chat_conversation(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.content_ai_request
    ADD CONSTRAINT content_ai_request_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.background_job(id);

ALTER TABLE ONLY public.content_publication
    ADD CONSTRAINT content_publication_namespace_document_id_fkey FOREIGN KEY (namespace, document_id) REFERENCES public.content_document(namespace, id);

ALTER TABLE ONLY public.content_document
    ADD CONSTRAINT content_publication_owner_fk FOREIGN KEY (publication_id, namespace, id) REFERENCES public.content_publication(id, namespace, document_id);

ALTER TABLE ONLY public.content_quality_report
    ADD CONSTRAINT content_quality_report_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.background_job(id);

ALTER TABLE ONLY public.content_search_term
    ADD CONSTRAINT content_search_term_namespace_document_id_fkey FOREIGN KEY (namespace, document_id) REFERENCES public.content_document(namespace, id);

ALTER TABLE ONLY public.content_source_alias
    ADD CONSTRAINT content_source_alias_namespace_document_id_fkey FOREIGN KEY (namespace, document_id) REFERENCES public.content_source_binding(namespace, document_id);

ALTER TABLE ONLY public.content_source_binding
    ADD CONSTRAINT content_source_binding_base_release_id_fkey FOREIGN KEY (base_release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.content_source_binding
    ADD CONSTRAINT content_source_binding_namespace_document_id_fkey FOREIGN KEY (namespace, document_id) REFERENCES public.content_document(namespace, id);

ALTER TABLE ONLY public.curriculum_enrollment
    ADD CONSTRAINT curriculum_enrollment_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.curriculum_enrollment
    ADD CONSTRAINT curriculum_enrollment_path_id_fkey FOREIGN KEY (path_id) REFERENCES public.curriculum_path(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.curriculum_item_progress
    ADD CONSTRAINT curriculum_item_progress_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.curriculum_item(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.curriculum_item_progress
    ADD CONSTRAINT curriculum_item_progress_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.curriculum_item
    ADD CONSTRAINT curriculum_item_section_id_fkey FOREIGN KEY (section_id) REFERENCES public.curriculum_section(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.curriculum_section
    ADD CONSTRAINT curriculum_section_path_id_fkey FOREIGN KEY (path_id) REFERENCES public.curriculum_path(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.data_maintenance_work
    ADD CONSTRAINT data_maintenance_work_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.data_maintenance_run(id);

ALTER TABLE ONLY public.data_source
    ADD CONSTRAINT data_source_license_id_fkey FOREIGN KEY (license_id) REFERENCES public.data_license(id);

ALTER TABLE ONLY public.dataset_active_release
    ADD CONSTRAINT dataset_active_release_release_id_domain_fkey FOREIGN KEY (release_id, domain) REFERENCES public.dataset_release(id, domain);

ALTER TABLE ONLY public.dataset_import_run
    ADD CONSTRAINT dataset_import_run_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.diamond_transaction
    ADD CONSTRAINT diamond_transaction_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.diamond_wallet
    ADD CONSTRAINT diamond_wallet_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.grammar_bank_catalog
    ADD CONSTRAINT grammar_bank_catalog_required_feature_key_fkey FOREIGN KEY (required_feature_key) REFERENCES public.entitlement_feature(feature_key);

ALTER TABLE ONLY public.grammar_bank_version
    ADD CONSTRAINT grammar_bank_version_catalog_id_year_fkey FOREIGN KEY (catalog_id, year) REFERENCES public.grammar_bank_catalog(id, year);

ALTER TABLE ONLY public.grammar_bank_version
    ADD CONSTRAINT grammar_bank_version_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.grammar_difficulty_level_version
    ADD CONSTRAINT grammar_difficulty_level_version_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_bank_version_id_release_id_fkey FOREIGN KEY (bank_version_id, release_id) REFERENCES public.grammar_bank_version(id, release_id);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_item_id_release_id_fkey FOREIGN KEY (item_id, release_id) REFERENCES public.assessment_item(id, release_id);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_release_id_difficulty_level_fkey FOREIGN KEY (release_id, difficulty_level) REFERENCES public.grammar_difficulty_level_version(release_id, level);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_subtopic_version_id_topic_version_id_re_fkey FOREIGN KEY (subtopic_version_id, topic_version_id, release_id) REFERENCES public.grammar_subtopic_version(id, topic_version_id, release_id);

ALTER TABLE ONLY public.grammar_membership
    ADD CONSTRAINT grammar_membership_topic_version_id_release_id_fkey FOREIGN KEY (topic_version_id, release_id) REFERENCES public.grammar_topic_version(id, release_id);

ALTER TABLE ONLY public.grammar_practice_attempt
    ADD CONSTRAINT grammar_practice_attempt_item_id_fkey FOREIGN KEY (item_id) REFERENCES public.assessment_item(id);

ALTER TABLE ONLY public.grammar_practice_attempt
    ADD CONSTRAINT grammar_practice_attempt_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.grammar_subtopic_catalog
    ADD CONSTRAINT grammar_subtopic_catalog_required_feature_key_fkey FOREIGN KEY (required_feature_key) REFERENCES public.entitlement_feature(feature_key);

ALTER TABLE ONLY public.grammar_subtopic_catalog
    ADD CONSTRAINT grammar_subtopic_catalog_topic_catalog_id_fkey FOREIGN KEY (topic_catalog_id) REFERENCES public.grammar_topic_catalog(id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES public.grammar_subtopic_catalog(id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_catalog_id_topic_catalog_id_fkey FOREIGN KEY (catalog_id, topic_catalog_id) REFERENCES public.grammar_subtopic_catalog(id, topic_catalog_id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_topic_version_id_package_topic_id_fkey FOREIGN KEY (topic_version_id, package_topic_id, release_id) REFERENCES public.grammar_topic_version(id, package_topic_id, release_id);

ALTER TABLE ONLY public.grammar_subtopic_version
    ADD CONSTRAINT grammar_subtopic_version_topic_version_id_topic_catalog_id_fkey FOREIGN KEY (topic_version_id, topic_catalog_id, release_id) REFERENCES public.grammar_topic_version(id, catalog_id, release_id);

ALTER TABLE ONLY public.grammar_topic_catalog
    ADD CONSTRAINT grammar_topic_catalog_required_feature_key_fkey FOREIGN KEY (required_feature_key) REFERENCES public.entitlement_feature(feature_key);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_catalog_id_fkey FOREIGN KEY (catalog_id) REFERENCES public.grammar_topic_catalog(id);

ALTER TABLE ONLY public.grammar_topic_version
    ADD CONSTRAINT grammar_topic_version_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.learner_daily_activity
    ADD CONSTRAINT learner_daily_activity_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.learner_level
    ADD CONSTRAINT learner_level_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.learner_profile
    ADD CONSTRAINT learner_profile_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.learner_skill_summary
    ADD CONSTRAINT learner_skill_summary_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.learner_weakness
    ADD CONSTRAINT learner_weakness_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_activity
    ADD CONSTRAINT lesson_activity_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_activity_progress
    ADD CONSTRAINT lesson_activity_progress_activity_id_fkey FOREIGN KEY (activity_id) REFERENCES public.lesson_activity(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_activity_progress
    ADD CONSTRAINT lesson_activity_progress_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_activity_sentence
    ADD CONSTRAINT lesson_activity_sentence_activity_id_fkey FOREIGN KEY (activity_id) REFERENCES public.lesson_activity(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.lesson_activity_sentence
    ADD CONSTRAINT lesson_activity_sentence_sentence_id_fkey FOREIGN KEY (sentence_id) REFERENCES public.lesson_sentence(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.lesson_annotation
    ADD CONSTRAINT lesson_annotation_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_annotation
    ADD CONSTRAINT lesson_annotation_sentence_id_fkey FOREIGN KEY (sentence_id) REFERENCES public.lesson_sentence(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_authoring
    ADD CONSTRAINT lesson_authoring_active_job_id_fkey FOREIGN KEY (active_job_id) REFERENCES public.background_job(id);

ALTER TABLE ONLY public.lesson_authoring
    ADD CONSTRAINT lesson_authoring_audio_source_id_fkey FOREIGN KEY (audio_source_id) REFERENCES public.lesson_audio_source(id);

ALTER TABLE ONLY public.lesson_authoring_job
    ADD CONSTRAINT lesson_authoring_job_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.background_job(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.lesson_authoring_job
    ADD CONSTRAINT lesson_authoring_job_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson_authoring(lesson_id);

ALTER TABLE ONLY public.lesson_authoring
    ADD CONSTRAINT lesson_authoring_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.lesson_build_job
    ADD CONSTRAINT lesson_build_job_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.background_job(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_build_job
    ADD CONSTRAINT lesson_build_job_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_build_job_step
    ADD CONSTRAINT lesson_build_job_step_ai_invocation_id_fkey FOREIGN KEY (ai_invocation_id) REFERENCES public.ai_invocation(id);

ALTER TABLE ONLY public.lesson_build_job_step
    ADD CONSTRAINT lesson_build_job_step_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.lesson_build_job(job_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_category_membership
    ADD CONSTRAINT lesson_category_membership_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.lesson_category(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.lesson_category_membership
    ADD CONSTRAINT lesson_category_membership_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.lesson_job_diagnostic
    ADD CONSTRAINT lesson_job_diagnostic_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.background_job(id);

ALTER TABLE ONLY public.lesson_metadata
    ADD CONSTRAINT lesson_metadata_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.lesson_practice_attempt
    ADD CONSTRAINT lesson_practice_attempt_activity_id_fkey FOREIGN KEY (activity_id) REFERENCES public.lesson_activity(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_practice_attempt
    ADD CONSTRAINT lesson_practice_attempt_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_practice_attempt
    ADD CONSTRAINT lesson_practice_attempt_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_practice_attempt
    ADD CONSTRAINT lesson_practice_attempt_sentence_id_fkey FOREIGN KEY (sentence_id) REFERENCES public.lesson_sentence(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.lesson_progress
    ADD CONSTRAINT lesson_progress_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_progress
    ADD CONSTRAINT lesson_progress_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_sentence
    ADD CONSTRAINT lesson_sentence_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_source_preview
    ADD CONSTRAINT lesson_source_preview_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.background_job(id);

ALTER TABLE ONLY public.lesson_source_preview
    ADD CONSTRAINT lesson_source_preview_video_id_fkey FOREIGN KEY (video_id) REFERENCES public.lesson_youtube_source(video_id);

ALTER TABLE ONLY public.lesson_word_timestamp
    ADD CONSTRAINT lesson_word_timestamp_sentence_id_fkey FOREIGN KEY (sentence_id) REFERENCES public.lesson_sentence(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.lesson_youtube_channel
    ADD CONSTRAINT lesson_youtube_channel_sync_job_id_fkey FOREIGN KEY (sync_job_id) REFERENCES public.background_job(id);

ALTER TABLE ONLY public.lesson_youtube_segment
    ADD CONSTRAINT lesson_youtube_segment_video_id_fkey FOREIGN KEY (video_id) REFERENCES public.lesson_youtube_source(video_id);

ALTER TABLE ONLY public.lexicon_entry
    ADD CONSTRAINT lexicon_entry_headword_id_fkey FOREIGN KEY (headword_id) REFERENCES public.lexicon_headword(id);

ALTER TABLE ONLY public.lexicon_entry
    ADD CONSTRAINT lexicon_entry_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.lexicon_form
    ADD CONSTRAINT lexicon_form_item_id_entry_id_release_id_fkey FOREIGN KEY (item_id, entry_id, release_id) REFERENCES public.lexicon_item(id, entry_id, release_id);

ALTER TABLE ONLY public.lexicon_item
    ADD CONSTRAINT lexicon_item_entry_id_release_id_fkey FOREIGN KEY (entry_id, release_id) REFERENCES public.lexicon_entry(id, release_id);

ALTER TABLE ONLY public.lexicon_pronunciation
    ADD CONSTRAINT lexicon_pronunciation_item_id_entry_id_release_id_fkey FOREIGN KEY (item_id, entry_id, release_id) REFERENCES public.lexicon_item(id, entry_id, release_id);

ALTER TABLE ONLY public.lexicon_sense
    ADD CONSTRAINT lexicon_sense_item_id_entry_id_release_id_fkey FOREIGN KEY (item_id, entry_id, release_id) REFERENCES public.lexicon_item(id, entry_id, release_id);

ALTER TABLE ONLY public.lexicon_translation
    ADD CONSTRAINT lexicon_translation_entry_id_release_id_fkey FOREIGN KEY (entry_id, release_id) REFERENCES public.lexicon_entry(id, release_id);

ALTER TABLE ONLY public.lexicon_translation
    ADD CONSTRAINT lexicon_translation_item_id_entry_id_release_id_fkey FOREIGN KEY (item_id, entry_id, release_id) REFERENCES public.lexicon_item(id, entry_id, release_id);

ALTER TABLE ONLY public.lexicon_translation
    ADD CONSTRAINT lexicon_translation_sense_id_item_id_entry_id_release_id_fkey FOREIGN KEY (sense_id, item_id, entry_id, release_id) REFERENCES public.lexicon_sense(id, item_id, entry_id, release_id);

ALTER TABLE ONLY public.media_asset_use
    ADD CONSTRAINT media_asset_use_group_id_release_id_fkey FOREIGN KEY (group_id, release_id) REFERENCES public.toeic_stimulus_group(id, release_id);

ALTER TABLE ONLY public.media_asset_use
    ADD CONSTRAINT media_asset_use_item_id_release_id_fkey FOREIGN KEY (item_id, release_id) REFERENCES public.assessment_item(id, release_id);

ALTER TABLE ONLY public.media_asset_use
    ADD CONSTRAINT media_asset_use_release_id_asset_id_fkey FOREIGN KEY (release_id, asset_id) REFERENCES public.release_media_asset(release_id, package_asset_id);

ALTER TABLE ONLY public.mission_progress
    ADD CONSTRAINT mission_progress_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.mission_progress
    ADD CONSTRAINT mission_progress_mission_id_fkey FOREIGN KEY (mission_id) REFERENCES public.mission_definition(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.module_runtime_config
    ADD CONSTRAINT module_runtime_config_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.app_user(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.release_media_asset
    ADD CONSTRAINT release_media_asset_blob_sha256_fkey FOREIGN KEY (blob_sha256) REFERENCES public.media_blob(sha256);

ALTER TABLE ONLY public.release_media_asset
    ADD CONSTRAINT release_media_asset_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.sentence_pronunciation
    ADD CONSTRAINT sentence_pronunciation_sentence_id_fkey FOREIGN KEY (sentence_id) REFERENCES public.lesson_sentence(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.speech_attempt
    ADD CONSTRAINT speech_attempt_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.speech_attempt
    ADD CONSTRAINT speech_attempt_lesson_id_fkey FOREIGN KEY (lesson_id) REFERENCES public.lesson(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.speech_attempt
    ADD CONSTRAINT speech_attempt_sentence_id_fkey FOREIGN KEY (sentence_id) REFERENCES public.lesson_sentence(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.toeic_attempt_answer
    ADD CONSTRAINT toeic_attempt_answer_attempt_id_test_version_id_fkey FOREIGN KEY (attempt_id, test_version_id) REFERENCES public.toeic_attempt(id, test_version_id) ON DELETE CASCADE;

ALTER TABLE ONLY public.toeic_attempt_answer
    ADD CONSTRAINT toeic_attempt_answer_placement_id_test_version_id_fkey FOREIGN KEY (placement_id, test_version_id) REFERENCES public.toeic_placement(id, test_version_id);

ALTER TABLE ONLY public.toeic_attempt
    ADD CONSTRAINT toeic_attempt_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.toeic_attempt
    ADD CONSTRAINT toeic_attempt_test_version_id_fkey FOREIGN KEY (test_version_id) REFERENCES public.toeic_test_version(id);

ALTER TABLE ONLY public.toeic_document
    ADD CONSTRAINT toeic_document_group_id_release_id_fkey FOREIGN KEY (group_id, release_id) REFERENCES public.toeic_stimulus_group(id, release_id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_group_id_test_version_id_release_id_fkey FOREIGN KEY (group_id, test_version_id, release_id) REFERENCES public.toeic_stimulus_group(id, test_version_id, release_id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_item_id_release_id_fkey FOREIGN KEY (item_id, release_id) REFERENCES public.assessment_item(id, release_id);

ALTER TABLE ONLY public.toeic_placement
    ADD CONSTRAINT toeic_placement_test_version_id_release_id_fkey FOREIGN KEY (test_version_id, release_id) REFERENCES public.toeic_test_version(id, release_id);

ALTER TABLE ONLY public.toeic_stimulus_group
    ADD CONSTRAINT toeic_stimulus_group_test_version_id_release_id_fkey FOREIGN KEY (test_version_id, release_id) REFERENCES public.toeic_test_version(id, release_id);

ALTER TABLE ONLY public.toeic_test_catalog
    ADD CONSTRAINT toeic_test_catalog_required_feature_key_fkey FOREIGN KEY (required_feature_key) REFERENCES public.entitlement_feature(feature_key);

ALTER TABLE ONLY public.toeic_test_version
    ADD CONSTRAINT toeic_test_version_catalog_id_year_test_number_fkey FOREIGN KEY (catalog_id, year, test_number) REFERENCES public.toeic_test_catalog(id, year, test_number);

ALTER TABLE ONLY public.toeic_test_version
    ADD CONSTRAINT toeic_test_version_release_id_fkey FOREIGN KEY (release_id) REFERENCES public.dataset_release(id);

ALTER TABLE ONLY public.user_entitlement_grant
    ADD CONSTRAINT user_entitlement_grant_feature_key_fkey FOREIGN KEY (feature_key) REFERENCES public.entitlement_feature(feature_key);

ALTER TABLE ONLY public.user_entitlement_grant
    ADD CONSTRAINT user_entitlement_grant_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.vocabulary_card
    ADD CONSTRAINT vocabulary_card_headword_id_fkey FOREIGN KEY (headword_id) REFERENCES public.lexicon_headword(id);

ALTER TABLE ONLY public.vocabulary_card
    ADD CONSTRAINT vocabulary_card_learner_id_fkey FOREIGN KEY (learner_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.vocabulary_review
    ADD CONSTRAINT vocabulary_review_card_id_fkey FOREIGN KEY (card_id) REFERENCES public.vocabulary_card(id) ON DELETE CASCADE;

--

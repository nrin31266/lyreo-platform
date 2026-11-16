-- Draft documents are separate from published content and existing learner history.
CREATE TABLE lesson_authoring (
    lesson_id UUID PRIMARY KEY REFERENCES lesson(id) ON DELETE RESTRICT,
    video_id VARCHAR(32) NOT NULL,
    source_start_ms BIGINT NOT NULL CHECK(source_start_ms >= 0),
    source_end_ms BIGINT NOT NULL CHECK(source_end_ms > source_start_ms),
    revision BIGINT NOT NULL DEFAULT 0,
    transcript_revision BIGINT NOT NULL DEFAULT 0,
    approved_revision BIGINT,
    karaoke_reviewed_revision BIGINT,
    semantic_revision BIGINT,
    timing_status VARCHAR(32) NOT NULL DEFAULT 'MISSING',
    active_job_id UUID REFERENCES background_job(id),
    document JSONB NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(document) = 'array'),
    published_at TIMESTAMPTZ,
    rights_confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX lesson_authoring_source_idx ON lesson_authoring(video_id,source_start_ms,source_end_ms);
CREATE TABLE lesson_authoring_job (
    job_id UUID PRIMARY KEY REFERENCES background_job(id) ON DELETE RESTRICT,
    lesson_id UUID NOT NULL REFERENCES lesson_authoring(lesson_id),
    operation VARCHAR(16) NOT NULL CHECK(operation IN ('PREPARE','ALIGN','SEMANTIC')),
    transcript_revision BIGINT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    checkpoints JSONB NOT NULL DEFAULT '{}'::jsonb,
    usage_json JSONB NOT NULL DEFAULT '[]'::jsonb
);
CREATE TABLE lesson_activity_sentence (
    activity_id UUID NOT NULL REFERENCES lesson_activity(id) ON DELETE RESTRICT,
    sentence_id UUID NOT NULL REFERENCES lesson_sentence(id) ON DELETE RESTRICT,
    PRIMARY KEY(activity_id,sentence_id)
);
CREATE FUNCTION guard_published_lesson_content() RETURNS trigger LANGUAGE plpgsql AS $$
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

CREATE TRIGGER lesson_published_guard BEFORE UPDATE OR DELETE ON lesson
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE TRIGGER lesson_authoring_published_guard BEFORE UPDATE OR DELETE ON lesson_authoring
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE TRIGGER lesson_sentence_published_guard BEFORE INSERT OR UPDATE OR DELETE ON lesson_sentence
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE TRIGGER lesson_words_published_guard BEFORE INSERT OR UPDATE OR DELETE ON lesson_word_timestamp
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE TRIGGER lesson_annotations_published_guard BEFORE INSERT OR UPDATE OR DELETE ON lesson_annotation
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE TRIGGER lesson_activities_published_guard BEFORE INSERT OR UPDATE OR DELETE ON lesson_activity
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE TRIGGER lesson_candidates_published_guard BEFORE INSERT OR UPDATE OR DELETE ON lesson_activity_sentence
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE TRIGGER lesson_pronunciation_published_guard BEFORE INSERT OR UPDATE OR DELETE ON sentence_pronunciation
    FOR EACH ROW EXECUTE FUNCTION guard_published_lesson_content();
CREATE FUNCTION guard_lesson_candidate_membership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM lesson_activity a JOIN lesson_sentence s ON s.lesson_id=a.lesson_id
        WHERE a.id=NEW.activity_id AND s.id=NEW.sentence_id) THEN
        RAISE EXCEPTION 'Candidate must belong to the same lesson as its activity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lesson_candidate_same_lesson BEFORE INSERT OR UPDATE ON lesson_activity_sentence
    FOR EACH ROW EXECUTE FUNCTION guard_lesson_candidate_membership();
-- Enforce new writes without rewriting legacy annotation rows.
CREATE FUNCTION guard_lesson_annotation_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sentence_id IS NOT NULL AND NOT EXISTS(
        SELECT 1 FROM lesson_sentence WHERE id=NEW.sentence_id AND lesson_id=NEW.lesson_id
    ) THEN
        RAISE EXCEPTION 'Annotation sentence must belong to the same lesson' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER lesson_annotation_same_lesson BEFORE INSERT OR UPDATE ON lesson_annotation
    FOR EACH ROW EXECUTE FUNCTION guard_lesson_annotation_scope();

CREATE TABLE lesson_youtube_source (
    video_id VARCHAR(11) PRIMARY KEY,
    title TEXT NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    embed_allowed BOOLEAN NOT NULL,
    audio_object_key TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE lesson_youtube_segment (
    video_id VARCHAR(11) NOT NULL REFERENCES lesson_youtube_source(video_id),
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    end_ms BIGINT NOT NULL CHECK (end_ms > start_ms),
    normalization_version VARCHAR(32) NOT NULL,
    audio_object_key TEXT NOT NULL,
    PRIMARY KEY (video_id, start_ms, end_ms, normalization_version)
);

ALTER TABLE lesson_youtube_source ADD COLUMN channel_id VARCHAR(128), ADD COLUMN channel_title VARCHAR(500);
CREATE TABLE lesson_category (
    id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL CHECK(length(trim(name)) > 0)
);
CREATE UNIQUE INDEX lesson_category_name_idx ON lesson_category(lower(name));
CREATE TABLE lesson_category_membership (
    lesson_id UUID NOT NULL REFERENCES lesson(id) ON DELETE RESTRICT,
    category_id UUID NOT NULL REFERENCES lesson_category(id) ON DELETE RESTRICT,
    PRIMARY KEY(lesson_id,category_id)
);
CREATE INDEX lesson_category_membership_category_idx ON lesson_category_membership(category_id,lesson_id);
CREATE TABLE lesson_source_preview (
    id UUID PRIMARY KEY, video_id VARCHAR(32) NOT NULL REFERENCES lesson_youtube_source(video_id),
    start_ms BIGINT NOT NULL CHECK(start_ms >= 0), end_ms BIGINT NOT NULL,
    job_id UUID NOT NULL REFERENCES background_job(id), audio_object_key TEXT,
    peaks JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(end_ms > start_ms AND end_ms - start_ms <= 600000)
);
CREATE INDEX lesson_source_preview_range_idx ON lesson_source_preview(video_id,start_ms,end_ms);
ALTER TABLE lesson_authoring ADD COLUMN timing_evidence JSONB NOT NULL DEFAULT '{}'::jsonb;


-- Presentation and availability are independent of immutable learning content.
CREATE TABLE lesson_metadata (
    lesson_id UUID PRIMARY KEY REFERENCES lesson(id) ON DELETE RESTRICT,
    title VARCHAR(500) NOT NULL CHECK(length(trim(title)) > 0),
    description VARCHAR(5000) NOT NULL DEFAULT '',
    image_url TEXT,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO lesson_metadata(lesson_id,title) SELECT id,title FROM lesson;
CREATE FUNCTION initialize_lesson_metadata() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO lesson_metadata(lesson_id,title) VALUES(NEW.id,NEW.title);
    RETURN NEW;
END $$;
CREATE TRIGGER lesson_metadata_created AFTER INSERT ON lesson
    FOR EACH ROW EXECUTE FUNCTION initialize_lesson_metadata();
ALTER TABLE lesson_youtube_source ADD COLUMN thumbnail_url TEXT;
CREATE TABLE lesson_youtube_channel (
    channel_id VARCHAR(128) PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    avatar_url TEXT,
    synced_at TIMESTAMPTZ,
    sync_error VARCHAR(500),
    sync_job_id UUID REFERENCES background_job(id),
    sync_completed BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO lesson_youtube_channel(channel_id,title)
    SELECT channel_id,max(channel_title) FROM lesson_youtube_source
    WHERE channel_id IS NOT NULL AND channel_id<>'' GROUP BY channel_id;
CREATE INDEX lesson_metadata_available_idx ON lesson_metadata(lesson_id) WHERE deleted_at IS NULL AND visible;

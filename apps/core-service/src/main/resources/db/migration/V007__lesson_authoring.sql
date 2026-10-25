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

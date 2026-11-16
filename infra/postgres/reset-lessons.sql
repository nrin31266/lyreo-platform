-- Operator-only development reset. Schema and imported/reference data remain intact.
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE lesson,lesson_authoring,lesson_sentence,lesson_word_timestamp,lesson_annotation,
    lesson_activity,lesson_activity_sentence,sentence_pronunciation,lesson_metadata,
    lesson_category_membership,lesson_authoring_job,lesson_build_job,lesson_build_job_step,
    lesson_progress,lesson_activity_progress,lesson_practice_attempt,speech_attempt,background_job,curriculum_item,vocabulary_card
    IN ACCESS EXCLUSIVE MODE;
CREATE TEMP TABLE reset_lesson_ids ON COMMIT DROP AS SELECT id FROM lesson;
CREATE TEMP TABLE reset_sentence_ids ON COMMIT DROP AS SELECT id FROM lesson_sentence;
CREATE TEMP TABLE reset_job_ids ON COMMIT DROP AS
    SELECT id FROM background_job WHERE owner_module='lesson'
      AND owner_reference_id IN (SELECT id FROM reset_lesson_ids)
      AND job_type IN ('LESSON_AUTHORING','LESSON_BUILD');
DO $$
BEGIN
    IF EXISTS(SELECT 1 FROM background_job WHERE id IN (SELECT id FROM reset_job_ids)
        AND status IN ('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED')) THEN
        RAISE EXCEPTION 'Stop or cancel active lesson jobs before reset';
    END IF;
    IF EXISTS(SELECT 1 FROM background_job WHERE owner_reference_id IN (SELECT id FROM reset_lesson_ids)
        AND id NOT IN (SELECT id FROM reset_job_ids)) THEN
        RAISE EXCEPTION 'Unexpected job references a lesson; reset refused';
    END IF;
    IF EXISTS(SELECT job_id FROM lesson_authoring_job WHERE job_id NOT IN (SELECT id FROM reset_job_ids))
        OR EXISTS(SELECT job_id FROM lesson_build_job WHERE job_id NOT IN (SELECT id FROM reset_job_ids))
        OR EXISTS(SELECT active_job_id FROM lesson_authoring WHERE active_job_id IS NOT NULL
            AND active_job_id NOT IN (SELECT id FROM reset_job_ids)) THEN
        RAISE EXCEPTION 'Lesson job ownership is inconsistent; reset refused';
    END IF;
    IF EXISTS(SELECT 1 FROM curriculum_item WHERE content_reference_id IN (SELECT id FROM reset_lesson_ids))
        OR EXISTS(SELECT 1 FROM vocabulary_card WHERE source_context_id IN (SELECT id FROM reset_lesson_ids)
            OR source_context_id IN (SELECT id FROM reset_sentence_ids)) THEN
        RAISE EXCEPTION 'Detach curriculum or vocabulary source references before resetting lessons';
    END IF;
END $$;
-- Only named publication guards are suspended, behind exclusive locks in this transaction.
ALTER TABLE lesson DISABLE TRIGGER lesson_published_guard;
ALTER TABLE lesson_authoring DISABLE TRIGGER lesson_authoring_published_guard;
ALTER TABLE lesson_sentence DISABLE TRIGGER lesson_sentence_published_guard;
ALTER TABLE lesson_word_timestamp DISABLE TRIGGER lesson_words_published_guard;
ALTER TABLE lesson_annotation DISABLE TRIGGER lesson_annotations_published_guard;
ALTER TABLE lesson_activity DISABLE TRIGGER lesson_activities_published_guard;
ALTER TABLE lesson_activity_sentence DISABLE TRIGGER lesson_candidates_published_guard;
ALTER TABLE sentence_pronunciation DISABLE TRIGGER lesson_pronunciation_published_guard;
DELETE FROM speech_attempt WHERE lesson_id IN (SELECT id FROM reset_lesson_ids) OR sentence_id IN (SELECT id FROM reset_sentence_ids);
DELETE FROM lesson_practice_attempt;
DELETE FROM lesson_activity_progress;
DELETE FROM lesson_progress;
DELETE FROM lesson_activity_sentence;
DELETE FROM lesson_word_timestamp;
DELETE FROM lesson_annotation;
DELETE FROM sentence_pronunciation;
DELETE FROM lesson_activity;
DELETE FROM lesson_sentence;
DELETE FROM lesson_build_job_step;
DELETE FROM lesson_build_job;
DELETE FROM lesson_job_diagnostic WHERE job_id IN (SELECT id FROM reset_job_ids);
DELETE FROM lesson_authoring_job;
DELETE FROM lesson_authoring;
DELETE FROM lesson_category_membership;
DELETE FROM lesson_metadata;
DELETE FROM lesson;
DELETE FROM background_job WHERE id IN (SELECT id FROM reset_job_ids);
ALTER TABLE lesson ENABLE TRIGGER lesson_published_guard;
ALTER TABLE lesson_authoring ENABLE TRIGGER lesson_authoring_published_guard;
ALTER TABLE lesson_sentence ENABLE TRIGGER lesson_sentence_published_guard;
ALTER TABLE lesson_word_timestamp ENABLE TRIGGER lesson_words_published_guard;
ALTER TABLE lesson_annotation ENABLE TRIGGER lesson_annotations_published_guard;
ALTER TABLE lesson_activity ENABLE TRIGGER lesson_activities_published_guard;
ALTER TABLE lesson_activity_sentence ENABLE TRIGGER lesson_candidates_published_guard;
ALTER TABLE sentence_pronunciation ENABLE TRIGGER lesson_pronunciation_published_guard;
COMMIT;

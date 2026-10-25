-- Serialize legacy content writers with immutable authoring publication.
CREATE OR REPLACE FUNCTION guard_published_lesson_content() RETURNS trigger LANGUAGE plpgsql AS $$
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

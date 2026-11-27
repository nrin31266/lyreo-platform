-- Vocabulary flashcard study mode (curated entry SRS), guess-the-word hint/check state,
-- and pack-wide / multi-mode practice sessions.

ALTER TABLE vocabulary_entry_progress
    ADD COLUMN srs_next_review_at timestamptz,
    ADD COLUMN srs_stability numeric(12,4) NOT NULL DEFAULT 1,
    ADD COLUMN srs_difficulty numeric(12,4) NOT NULL DEFAULT 5,
    ADD COLUMN srs_review_count integer NOT NULL DEFAULT 0 CHECK (srs_review_count >= 0),
    ADD COLUMN srs_lapse_count integer NOT NULL DEFAULT 0 CHECK (srs_lapse_count >= 0),
    ADD COLUMN srs_last_reviewed_at timestamptz;

CREATE INDEX vocabulary_entry_progress_srs_due_idx
    ON vocabulary_entry_progress (learner_id, pack_id, unit_id, srs_next_review_at)
    WHERE srs_next_review_at IS NOT NULL;

CREATE TABLE vocabulary_entry_review (
    id uuid PRIMARY KEY,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    entry_id uuid NOT NULL REFERENCES vocabulary_unit_entry(id) ON DELETE CASCADE,
    unit_id uuid NOT NULL REFERENCES vocabulary_unit(id) ON DELETE CASCADE,
    pack_id uuid NOT NULL REFERENCES vocabulary_pack(id) ON DELETE CASCADE,
    rating varchar(16) NOT NULL CHECK (rating IN ('AGAIN', 'HARD', 'GOOD', 'EASY')),
    previous_due_at timestamptz,
    next_due_at timestamptz NOT NULL,
    stability numeric(12,4) NOT NULL,
    difficulty numeric(12,4) NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX vocabulary_entry_review_learner_idx
    ON vocabulary_entry_review (learner_id, entry_id, reviewed_at DESC);

ALTER TABLE vocabulary_study_session
    ALTER COLUMN unit_id DROP NOT NULL;

ALTER TABLE vocabulary_study_session
    DROP CONSTRAINT vocabulary_study_session_mode_check;

ALTER TABLE vocabulary_study_session
    ADD CONSTRAINT vocabulary_study_session_mode_check CHECK (mode IN (
        'CHOOSE_MEANING', 'CHOOSE_WORD', 'LETTER_SCRAMBLE',
        'MISSING_LETTERS', 'LISTEN_WRITE', 'CONTEXT_CLOZE',
        'GUESS_WORD', 'MIXED'
    ));

ALTER TABLE vocabulary_study_session
    ADD COLUMN question_state jsonb NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX vocabulary_study_session_pack_idx
    ON vocabulary_study_session (learner_id, pack_id, created_at DESC);

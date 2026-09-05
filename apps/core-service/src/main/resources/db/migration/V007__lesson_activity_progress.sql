-- Learner progress is owned by the Lesson module, not a global Progress god-table.
-- Detailed attempts remain append-only; this table is a compact projection used by Home/Lesson UI.
CREATE TABLE lesson_activity_progress (
    learner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    activity_id UUID NOT NULL REFERENCES lesson_activity(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS'
        CHECK (status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED')),
    completed_items INT NOT NULL DEFAULT 0 CHECK (completed_items >= 0),
    total_items INT NOT NULL DEFAULT 0 CHECK (total_items >= 0),
    best_score INT CHECK (best_score BETWEEN 0 AND 100),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    last_practiced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(learner_id, activity_id)
);

CREATE INDEX lesson_activity_progress_activity_idx
    ON lesson_activity_progress(activity_id, status);

ALTER TABLE learner_profile ADD COLUMN first_seen_at timestamptz;

CREATE INDEX learner_profile_directory_idx
    ON learner_profile (first_seen_at DESC, learner_id DESC)
    WHERE first_seen_at IS NOT NULL;

CREATE VIEW identity_user_directory AS
    SELECT id, email_snapshot FROM app_user;

COMMENT ON VIEW identity_user_directory IS
    'Identity-owned read contract for product directories. Consumers must apply their own membership criteria.';

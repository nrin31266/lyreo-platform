CREATE TABLE admin_pronunciation_attempt (
    id uuid PRIMARY KEY,
    admin_subject text NOT NULL,
    idempotency_key uuid NOT NULL,
    fingerprint text NOT NULL,
    lesson_id uuid NOT NULL,
    sentence_id uuid NOT NULL,
    revision bigint NOT NULL,
    job_id uuid UNIQUE REFERENCES background_job(id),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
    reference_text text,
    reference_start_ms bigint,
    reference_end_ms bigint,
    invocation_id uuid REFERENCES ai_invocation(id),
    result_json jsonb,
    UNIQUE (admin_subject, idempotency_key)
);

CREATE INDEX admin_pronunciation_attempt_expiry_idx ON admin_pronunciation_attempt (expires_at);

ALTER TABLE lesson_authoring_job DROP CONSTRAINT lesson_authoring_job_operation_check;
ALTER TABLE lesson_authoring_job ADD CONSTRAINT lesson_authoring_job_operation_check
    CHECK (operation IN ('PREPARE','ALIGN','SEMANTIC','IPA','TTS','CONTENT'));

ALTER TABLE lesson_authoring ADD COLUMN content_profile JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE lesson_authoring ADD COLUMN auto_continue BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lesson_authoring ADD COLUMN published_by UUID;
CREATE TABLE lesson_job_diagnostic (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES background_job(id),
    invocation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '7 days',
    payload JSONB NOT NULL
);
CREATE INDEX lesson_job_diagnostic_job_idx ON lesson_job_diagnostic(job_id,created_at);

ALTER TABLE lesson_metadata ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE lesson_authoring_job ADD COLUMN result_json JSONB;
ALTER TABLE lesson_authoring_job ADD CONSTRAINT lesson_authoring_job_result_object
    CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object');

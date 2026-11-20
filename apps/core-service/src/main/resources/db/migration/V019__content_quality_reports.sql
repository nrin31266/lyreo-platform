CREATE TABLE content_quality_report (
    id uuid PRIMARY KEY,
    namespace text NOT NULL,
    target_id uuid NOT NULL,
    revision bigint NOT NULL,
    fingerprint text NOT NULL,
    rules_version text NOT NULL,
    job_id uuid NOT NULL REFERENCES background_job(id),
    status text NOT NULL DEFAULT 'RUNNING',
    issues jsonb NOT NULL DEFAULT '[]'::jsonb,
    context jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    checked_at timestamptz,
    CHECK (status IN ('RUNNING','PASSED','WARNING','BLOCKED'))
);
CREATE INDEX content_quality_latest ON content_quality_report(namespace,target_id,created_at DESC);

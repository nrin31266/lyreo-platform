CREATE TABLE data_maintenance_run (
    id text PRIMARY KEY,
    model text NOT NULL,
    prompt_version text NOT NULL,
    pricing_date date NOT NULL,
    budget_usd numeric(12,8) NOT NULL CHECK (budget_usd > 0 AND budget_usd <= 1),
    reserved_usd numeric(12,8) NOT NULL DEFAULT 0 CHECK (reserved_usd >= 0 AND reserved_usd <= budget_usd),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 100),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE data_maintenance_work (
    fingerprint text PRIMARY KEY,
    run_id text NOT NULL REFERENCES data_maintenance_run(id),
    domain text NOT NULL,
    target_id text NOT NULL,
    input jsonb NOT NULL,
    output jsonb,
    status text NOT NULL CHECK (status IN ('RESERVED','SUCCEEDED','FAILED')),
    reserved_usd numeric(12,8) NOT NULL CHECK (reserved_usd > 0),
    actual_usd numeric(12,8),
    invocation_id uuid,
    error_code text,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

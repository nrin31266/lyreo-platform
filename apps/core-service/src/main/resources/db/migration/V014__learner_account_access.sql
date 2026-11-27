ALTER TABLE app_user
    ADD COLUMN account_locked boolean NOT NULL DEFAULT false,
    ADD COLUMN account_sync_pending boolean NOT NULL DEFAULT false,
    ADD COLUMN account_tokens_valid_after timestamptz;

CREATE TABLE learner_account_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    learner_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    actor text NOT NULL,
    action text NOT NULL,
    reason text,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

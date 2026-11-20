ALTER TABLE data_maintenance_run
    DROP CONSTRAINT data_maintenance_run_budget_usd_check,
    DROP CONSTRAINT data_maintenance_run_attempts_check,
    ADD CONSTRAINT data_maintenance_run_budget_usd_check CHECK (budget_usd > 0),
    ADD CONSTRAINT data_maintenance_run_attempts_check CHECK (attempts >= 0);

ALTER TABLE data_maintenance_work ADD COLUMN input_tokens integer,
    ADD COLUMN output_tokens integer, ADD COLUMN reasoning_tokens integer;

-- The operator authorized USD 1.50 total, including prior attempts and failed reservations.
ALTER TABLE data_maintenance_run DROP CONSTRAINT data_maintenance_run_budget_usd_check;
ALTER TABLE data_maintenance_run ADD CONSTRAINT data_maintenance_run_budget_usd_check
    CHECK (budget_usd > 0 AND budget_usd <= 1.50);
UPDATE data_maintenance_run SET budget_usd=1.50 WHERE id='library-v2-pilot';

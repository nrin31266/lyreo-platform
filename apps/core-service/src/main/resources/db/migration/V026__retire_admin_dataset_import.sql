-- Keep CLI import history and close work that the retired handler cannot finish.
UPDATE background_job
SET status = 'CANCELLED', finished_at = now(), lease_owner = NULL, lease_until = NULL,
    next_retry_at = NULL, current_step = 'IMPORT_MOVED_TO_CLI'
WHERE job_type = 'DATA_IMPORT' AND owner_module = 'dataset'
  AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED');

DROP TABLE dataset_operation;

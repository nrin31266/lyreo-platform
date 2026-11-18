-- Technical report projection keeps job persistence out of business repositories.
CREATE VIEW content_quality_current AS
SELECT DISTINCT ON (r.namespace,r.target_id) r.namespace,r.target_id,
    CASE
        WHEN EXISTS (SELECT 1 FROM content_document d WHERE d.updated_at > r.created_at)
          OR EXISTS (SELECT 1 FROM dataset_active_release a WHERE a.activated_at > r.created_at) THEN 'STALE'
        WHEN r.status='RUNNING' AND j.status IN ('FAILED','CANCELLED') THEN 'FAILED'
        ELSE r.status
    END AS status
FROM content_quality_report r JOIN background_job j ON j.id=r.job_id
ORDER BY r.namespace,r.target_id,r.created_at DESC,r.id DESC;

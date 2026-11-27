ALTER TABLE learner_notification ALTER COLUMN status SET DEFAULT 'DRAFT';

UPDATE learner_notification SET published_at = created_at
WHERE status = 'PUBLISHED' AND published_at IS NULL;

ALTER TABLE learner_notification_audit ADD COLUMN scheduled_for timestamptz;

CREATE INDEX learner_account_audit_request_idx ON learner_account_audit (learner_id, id DESC)
    WHERE action IN ('LOCK_REQUESTED', 'UNLOCK_REQUESTED');

CREATE OR REPLACE VIEW admin_activity_directory AS
    SELECT 'plus-start:' || id AS id, 'MEMBERSHIP' AS category, 'PLUS_STARTED' AS action,
           learner_id, started_by AS actor, NULL::text AS detail, NULL::integer AS amount,
           starts_at AS occurred_at, NULL::timestamptz AS scheduled_for
    FROM learner_plus_period WHERE starts_at <= now()
    UNION ALL
    SELECT 'plus-end:' || id, 'MEMBERSHIP', 'PLUS_ENDED', learner_id, ended_by, NULL, NULL, ends_at, NULL
    FROM learner_plus_period WHERE ends_at <= now()
    UNION ALL
    SELECT 'account:' || id, 'ACCOUNT', action, learner_id, actor, reason, NULL, occurred_at, NULL
    FROM learner_account_audit WHERE action IN ('LOCKED', 'UNLOCKED', 'PASSWORD_SET', 'TEMPORARY_PASSWORD_SET')
    UNION ALL
    SELECT 'account:' || request.id, 'ACCOUNT', request.action, u.id, request.actor, request.reason,
           NULL, request.occurred_at, NULL
    FROM app_user u JOIN LATERAL (
        SELECT id, action, actor, reason, occurred_at FROM learner_account_audit
        WHERE learner_id = u.id AND action IN ('LOCK_REQUESTED', 'UNLOCK_REQUESTED')
        ORDER BY id DESC LIMIT 1
    ) request ON true WHERE u.account_sync_pending
    UNION ALL
    SELECT 'notification:' || id, 'NOTIFICATION', action, NULL, actor, title, NULL, occurred_at, scheduled_for
    FROM learner_notification_audit
    UNION ALL
    SELECT 'diamond:' || id, 'REWARD', 'DIAMOND_ADJUSTED', learner_id, NULL, NULL, amount, created_at, NULL
    FROM diamond_transaction WHERE transaction_type = 'ADMIN_ADJUSTMENT'
    UNION ALL
    SELECT 'quota:' || id, 'OTHER', 'QUOTA_UPDATED', NULL, NULL, feature_key, NULL, occurred_at, NULL
    FROM entitlement_audit_log WHERE decision = 'QUOTA_UPDATED';

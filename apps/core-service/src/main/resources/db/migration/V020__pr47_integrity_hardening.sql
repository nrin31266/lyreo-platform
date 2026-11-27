-- Harden entitlement configuration audit logging for admin actions without a learner target,
-- and enforce non-negative cached balances at the database level on diamond_wallet.

ALTER TABLE entitlement_audit_log
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE entitlement_audit_log
    ADD COLUMN IF NOT EXISTS actor text;

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
    SELECT 'reward-policy:' || id, 'REWARD', 'REWARD_POLICY_UPDATED', NULL, actor, summary, NULL, occurred_at, NULL
    FROM reward_policy_audit
    UNION ALL
    SELECT 'quota:' || id, 'OTHER', 'QUOTA_UPDATED', NULL,
           COALESCE(actor, detail_json->>'actor'), feature_key, NULL, occurred_at, NULL
    FROM entitlement_audit_log WHERE decision = 'QUOTA_UPDATED';

COMMENT ON VIEW admin_activity_directory IS
    'Shared Admin read contract for membership, account, notification, reward and configuration events.';

UPDATE diamond_wallet
   SET cached_balance = 0
 WHERE cached_balance < 0;

ALTER TABLE diamond_wallet
    ADD CONSTRAINT diamond_wallet_non_negative_check CHECK (cached_balance >= 0);
